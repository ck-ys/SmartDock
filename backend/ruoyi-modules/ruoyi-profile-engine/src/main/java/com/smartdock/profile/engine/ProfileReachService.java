package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.profile.domain.ProfileReachBatch;
import com.smartdock.profile.domain.ProfileReachItem;
import com.smartdock.profile.domain.ProfileSegment;
import com.smartdock.profile.domain.ProfileSegmentMember;
import com.smartdock.profile.mapper.ProfileReachBatchMapper;
import com.smartdock.profile.mapper.ProfileReachItemMapper;
import com.smartdock.profile.mapper.ProfileSegmentMapper;
import com.smartdock.profile.mapper.ProfileSegmentMemberMapper;
import com.smartdock.profile.query.ProfileReachSendBo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 分群触达编排（批9 拍板 Q3-A 风暴加固版）。
 *
 * <p>纪律：确认发送时冻结收件人快照（读当前成员写入逐人明细，之后分群刷新不影响本批）；
 * 逐人明细带 dedup_key（reachId:userId）幂等防重发；失败一键重试只重发 FAILED；
 * 通道走 {@link ProfileReachChannel} SPI，站内实现由宿主模块注册。导出共用本审计表（channel=export）。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileReachService {

    private static final String CHANNEL_INAPP = "inapp";
    private static final String CHANNEL_EXPORT = "export";

    private final ProfileReachBatchMapper reachMapper;
    private final ProfileReachItemMapper itemMapper;
    private final ProfileSegmentMapper segmentMapper;
    private final ProfileSegmentMemberMapper memberMapper;
    private final ObjectProvider<ProfileReachChannel> channelProvider;

    /** 预览收件人：当前成员中站内通道可覆盖的用户型主体。 */
    public Map<String, Object> preview(Long segmentId) {
        ProfileSegment segment = segment(segmentId);
        List<ProfileSegmentMember> members = currentMembers(segment);
        int userTyped = 0;
        for (ProfileSegmentMember m : members) {
            if ("user".equalsIgnoreCase(nullSafe(m.getSubjectType()))) {
                userTyped++;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("segmentId", segment.getSegmentId());
        out.put("segmentName", segment.getSegmentName());
        out.put("memberCount", members.size());
        out.put("reachableCount", userTyped);
        out.put("skippedNonUser", members.size() - userTyped);
        out.put("lastRunId", segment.getLastRunId());
        out.put("channels", channels());
        return out;
    }

    /**
     * 发送：冻结收件人 → 逐人明细 → 逐个投递 → 回写批次成败。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProfileReachBatch send(Long segmentId, ProfileReachSendBo bo) {
        if (bo == null || StringUtils.isBlank(bo.getTitle())) {
            throw new ServiceException("通知标题不能为空");
        }
        ProfileSegment segment = segment(segmentId);
        List<ProfileSegmentMember> members = currentMembers(segment);
        ProfileReachChannel channel = requireChannel(CHANNEL_INAPP);

        ProfileReachBatch batch = new ProfileReachBatch();
        batch.setReachId(IdWorker.getId());
        batch.setTenantId(segment.getTenantId());
        batch.setSource(segment.getSource());
        batch.setSegmentId(segment.getSegmentId());
        batch.setSegmentName(segment.getSegmentName());
        batch.setChannel(CHANNEL_INAPP);
        batch.setTitle(StringUtils.substring(bo.getTitle(), 0, 200));
        batch.setContent(StringUtils.substring(bo.getContent(), 0, 1000));
        batch.setMasked("0");
        batch.setRetryCount(0);
        batch.setStatus("RUNNING");
        batch.setCreateBy(currentUser());
        reachMapper.insert(batch);

        List<ProfileReachItem> items = new ArrayList<>();
        for (ProfileSegmentMember m : members) {
            if (!"user".equalsIgnoreCase(nullSafe(m.getSubjectType())) || m.getSubjectId() == null) {
                continue;
            }
            ProfileReachItem item = new ProfileReachItem();
            item.setItemId(IdWorker.getId());
            item.setTenantId(segment.getTenantId());
            item.setReachId(batch.getReachId());
            item.setSubjectType(m.getSubjectType());
            item.setSubjectId(m.getSubjectId());
            item.setUserId(parseLong(m.getSubjectId()));
            item.setUserName(m.getDisplayName());
            item.setDedupKey(batch.getReachId() + ":" + m.getSubjectId());
            item.setStatus("PENDING");
            items.add(item);
            itemMapper.insertItem(item);
        }
        batch.setTotalCount(items.size());
        if (items.isEmpty()) {
            batch.setStatus("FAILED");
            batch.setErrorMsg("分群没有可触达的用户型主体");
            reachMapper.updateById(batch);
            return batch;
        }
        dispatch(batch, items, channel);
        return batch;
    }

    /**
     * 失败重试：只重发 FAILED 明细（幂等键不变，已成功者不会重收）。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProfileReachBatch retry(Long reachId) {
        ProfileReachBatch batch = reachMapper.selectById(reachId);
        if (batch == null || "1".equals(batch.getDelFlag())) {
            throw new ServiceException("触达批次不存在");
        }
        if (!CHANNEL_INAPP.equals(batch.getChannel())) {
            throw new ServiceException("只有站内通知批次支持重试");
        }
        List<ProfileReachItem> failed = itemMapper.selectFailedItems(reachId);
        if (failed.isEmpty()) {
            return batch;
        }
        ProfileReachChannel channel = requireChannel(CHANNEL_INAPP);
        batch.setRetryCount((batch.getRetryCount() == null ? 0 : batch.getRetryCount()) + 1);
        dispatch(batch, failed, channel);
        return batch;
    }

    /**
     * 分群成员导出（批9 Q2-A）：默认脱敏列；明文导出需编辑权限（Controller 层校验）。
     * 审计与触达共表（channel=export），批11 导出审批直接挂接。
     */
    @Transactional(rollbackFor = Exception.class)
    public byte[] export(Long segmentId, boolean masked) {
        ProfileSegment segment = segment(segmentId);
        if (segment.getLastRunId() == null) {
            throw new ServiceException("分群还没有评估记录, 请先刷新");
        }
        List<ProfileSegmentMember> members = currentMembers(segment);
        StringBuilder csv = new StringBuilder();
        csv.append("\uFEFF"); // Excel 兼容 BOM
        csv.append("主体类型,主体ID,名称,入群原因\r\n");
        for (ProfileSegmentMember m : members) {
            csv.append(csvCell(nullSafe(m.getSubjectType()))).append(',')
                .append(csvCell(masked ? mask(nullSafe(m.getSubjectId())) : nullSafe(m.getSubjectId()))).append(',')
                .append(csvCell(masked ? mask(nullSafe(m.getDisplayName())) : nullSafe(m.getDisplayName()))).append(',')
                .append(csvCell(nullSafe(m.getReason()))).append("\r\n");
        }
        ProfileReachBatch batch = new ProfileReachBatch();
        batch.setReachId(IdWorker.getId());
        batch.setTenantId(segment.getTenantId());
        batch.setSource(segment.getSource());
        batch.setSegmentId(segment.getSegmentId());
        batch.setSegmentName(segment.getSegmentName());
        batch.setChannel(CHANNEL_EXPORT);
        batch.setTitle("导出成员 CSV");
        batch.setMasked(masked ? "1" : "0");
        batch.setTotalCount(members.size());
        batch.setSuccessCount(members.size());
        batch.setFailCount(0);
        batch.setRetryCount(0);
        batch.setStatus("DONE");
        batch.setCreateBy(currentUser());
        reachMapper.insert(batch);
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    public IPage<ProfileReachBatch> records(Long segmentId, String channel, int pageNum, int pageSize) {
        ProfileSegment segment = segment(segmentId);
        Page<ProfileReachBatch> page = new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 100));
        page.setOptimizeCountSql(false);
        return reachMapper.selectBatchPageScript(page, segment.getTenantId(), segmentId, channel);
    }

    public List<ProfileReachItem> items(Long reachId, int limit) {
        return itemMapper.selectItems(reachId, Math.min(Math.max(limit, 1), 500));
    }

    /** 单发（复核 SLA 提醒等系统通知复用, dedupKey 防轰炸）。 */
    public boolean sendToUser(Long userId, String title, String content, String dedupKey) {
        if (userId == null) {
            return false;
        }
        try {
            ProfileReachChannel channel = requireChannel(CHANNEL_INAPP);
            return channel.send(userId, title, content, dedupKey).ok();
        } catch (Exception e) {
            log.warn("[Profile] direct notify failed user={} err={}", userId, e.getMessage());
            return false;
        }
    }

    public List<Map<String, Object>> channels() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProfileReachChannel channel : channelProvider.stream().toList()) {
            out.add(channel.describe());
        }
        return out;
    }

    // ---------------------------------------------------------------- 内部

    private void dispatch(ProfileReachBatch batch, List<ProfileReachItem> items, ProfileReachChannel channel) {
        int success = countSuccess(batch);
        int fail = 0;
        for (ProfileReachItem item : items) {
            ProfileReachChannel.Result result;
            try {
                result = channel.send(item.getUserId(), batch.getTitle(), batch.getContent(), item.getDedupKey());
            } catch (Exception e) {
                result = ProfileReachChannel.Result.fail(StringUtils.substring(String.valueOf(e.getMessage()), 0, 200));
            }
            item.setStatus(result.ok() ? "SUCCESS" : "FAILED");
            item.setErrorMsg(result.error() == null ? null : StringUtils.substring(result.error(), 0, 500));
            item.setSentTime(new Date());
            itemMapper.updateById(item);
            if (result.ok()) {
                success++;
            } else {
                fail++;
            }
        }
        batch.setSuccessCount(success);
        batch.setFailCount(fail);
        batch.setStatus(fail == 0 ? "DONE" : success == 0 ? "FAILED" : "PARTIAL");
        batch.setUpdateBy(currentUser());
        reachMapper.updateById(batch);
    }

    private int countSuccess(ProfileReachBatch batch) {
        return batch.getSuccessCount() == null ? 0 : batch.getSuccessCount();
    }

    private ProfileReachChannel requireChannel(String code) {
        for (ProfileReachChannel channel : channelProvider.stream().toList()) {
            if (channel.channel().equals(code)) {
                return channel;
            }
        }
        throw new ServiceException("触达通道未注册: " + code);
    }

    private List<ProfileSegmentMember> currentMembers(ProfileSegment segment) {
        if (segment.getLastRunId() == null) {
            return List.of();
        }
        return memberMapper.selectList(new LambdaQueryWrapper<ProfileSegmentMember>()
            .eq(ProfileSegmentMember::getSegmentId, segment.getSegmentId())
            .eq(ProfileSegmentMember::getRunId, segment.getLastRunId())
            .eq(ProfileSegmentMember::getTenantId, segment.getTenantId()));
    }

    private ProfileSegment segment(Long segmentId) {
        ProfileSegment segment = segmentMapper.selectById(segmentId);
        if (segment == null) {
            throw new ServiceException("分群不存在");
        }
        return segment;
    }

    private String csvCell(String value) {
        String v = value == null ? "" : value;
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    private String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() <= 4) {
            return value.charAt(0) + "***";
        }
        return value.substring(0, 3) + "***" + value.substring(value.length() - 2);
    }

    private Long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private Long currentUser() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }
}
