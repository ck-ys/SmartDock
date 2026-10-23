package com.smartdock.dcs.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.dcs.domain.DcsFeedback;
import com.smartdock.dcs.domain.bo.DcsFeedbackBo;
import com.smartdock.dcs.domain.bo.DcsFeedbackHandleBo;
import com.smartdock.dcs.mapper.DcsFeedbackMapper;
import com.smartdock.dcs.mapper.DcsUserLookupMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 产品反馈服务（N3 2026-10-23）。
 *
 * <p>状态机：新建固定 PENDING；管理端只能改为 PROCESSING / RESOLVED / CLOSED，
 * 不允许改回 PENDING（重开用 PROCESSING + 意见说明）。提交时通知本租户管理员，
 * 处理后通知提交人；通知均带跳转路由，点击直达反馈中心。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DcsFeedbackService {

    private static final String NOTICE_NEW = "FEEDBACK_NEW";
    private static final String NOTICE_REPLY = "FEEDBACK_REPLY";
    /** 管理员入口：反馈中心。 */
    private static final String ADMIN_URL = "/support/feedback";
    /** 提交人入口：首页 + 自动打开「我的反馈」弹窗（提交人没有反馈中心菜单权限，直接跳管理页会 403）。 */
    private static final String USER_URL = "/index?feedback=mine";

    private final DcsFeedbackMapper feedbackMapper;
    private final DcsUserLookupMapper userLookupMapper;
    private final DcsNoticeService noticeService;

    /** 提交反馈（全员）；提交后通知本租户管理员。 */
    @Transactional(rollbackFor = Exception.class)
    public Long submit(DcsFeedbackBo bo) {
        Long userId = LoginHelper.getUserId();
        DcsFeedback f = new DcsFeedback();
        f.setFeedbackId(IdWorker.getId());
        f.setTitle(bo.getTitle().trim());
        f.setContent(bo.getContent().trim());
        f.setFeedbackType(bo.getFeedbackType());
        f.setOssIds(normalizeOssIds(bo.getOssIds()));
        f.setContact(bo.getContact());
        f.setContextJson(bo.getContextJson());
        f.setStatus(DcsFeedback.STATUS_PENDING);
        f.setDelFlag("0");
        f.setCreateBy(userId);
        f.setCreateTime(new Date());
        feedbackMapper.insert(f);

        try {
            List<Long> admins = userLookupMapper.selectAdminUserIds();
            for (Long adminId : admins) {
                if (adminId.equals(userId)) {
                    continue;
                }
                noticeService.notifyIdem(adminId, NOTICE_NEW, "收到一条新反馈",
                    "《" + f.getTitle() + "》等待处理，点击进入反馈中心。", null,
                    "feedback:new:" + f.getFeedbackId(), ADMIN_URL);
            }
        } catch (Exception e) {
            // 通知失败不影响提交本身
            log.warn("[Feedback] 提交通知发送失败 id={}: {}", f.getFeedbackId(), e.getMessage());
        }
        return f.getFeedbackId();
    }

    /** 我的反馈（强制只看自己的）。 */
    public TableDataInfo<DcsFeedback> myPage(DcsFeedbackBo bo, PageQuery pq, Long userId) {
        Page<DcsFeedback> page = feedbackMapper.selectPage(pq.build(),
            new LambdaQueryWrapper<DcsFeedback>()
                .eq(DcsFeedback::getCreateBy, userId)
                .eq(DcsFeedback::getDelFlag, "0")
                .eq(bo.getStatus() != null && !bo.getStatus().isBlank(), DcsFeedback::getStatus, bo.getStatus())
                .orderByDesc(DcsFeedback::getCreateTime));
        enrich(page.getRecords());
        return TableDataInfo.build(page);
    }

    /** 我的反馈四态计数（缺席补 0）。 */
    public Map<String, Long> mySummary(Long userId) {
        Map<String, Long> out = emptySummary();
        for (String status : out.keySet()) {
            out.put(status, feedbackMapper.selectCount(new LambdaQueryWrapper<DcsFeedback>()
                .eq(DcsFeedback::getCreateBy, userId)
                .eq(DcsFeedback::getDelFlag, "0")
                .eq(DcsFeedback::getStatus, status)));
        }
        return out;
    }

    /** 本租户全部反馈（管理端）。 */
    public TableDataInfo<DcsFeedback> adminPage(DcsFeedbackBo bo, PageQuery pq) {
        Page<DcsFeedback> page = feedbackMapper.selectPage(pq.build(),
            new LambdaQueryWrapper<DcsFeedback>()
                .eq(DcsFeedback::getDelFlag, "0")
                .like(bo.getTitle() != null && !bo.getTitle().isBlank(), DcsFeedback::getTitle, bo.getTitle())
                .eq(bo.getFeedbackType() != null && !bo.getFeedbackType().isBlank(), DcsFeedback::getFeedbackType, bo.getFeedbackType())
                .eq(bo.getStatus() != null && !bo.getStatus().isBlank(), DcsFeedback::getStatus, bo.getStatus())
                .orderByDesc(DcsFeedback::getCreateTime));
        enrich(page.getRecords());
        return TableDataInfo.build(page);
    }

    /** 本租户四态计数（管理端）。 */
    public Map<String, Long> statusSummary() {
        Map<String, Long> out = emptySummary();
        for (String status : out.keySet()) {
            out.put(status, feedbackMapper.selectCount(new LambdaQueryWrapper<DcsFeedback>()
                .eq(DcsFeedback::getDelFlag, "0")
                .eq(DcsFeedback::getStatus, status)));
        }
        return out;
    }

    /** 详情（租户拦截器自动隔离；不存在或跨租户 = 不存在）。 */
    public DcsFeedback getById(Long feedbackId) {
        DcsFeedback f = feedbackMapper.selectOne(new LambdaQueryWrapper<DcsFeedback>()
            .eq(DcsFeedback::getFeedbackId, feedbackId)
            .eq(DcsFeedback::getDelFlag, "0"));
        if (f == null) {
            throw new ServiceException("反馈不存在或无权查看");
        }
        enrich(List.of(f));
        return f;
    }

    /** 处理反馈：四态流转 + 通知提交人。 */
    @Transactional(rollbackFor = Exception.class)
    public void handle(DcsFeedbackHandleBo bo) {
        DcsFeedback f = feedbackMapper.selectOne(new LambdaQueryWrapper<DcsFeedback>()
            .eq(DcsFeedback::getFeedbackId, bo.getFeedbackId())
            .eq(DcsFeedback::getDelFlag, "0"));
        if (f == null) {
            throw new ServiceException("反馈不存在或无权处理");
        }
        if (DcsFeedback.STATUS_PENDING.equals(bo.getStatus())) {
            throw new ServiceException("不能改回待处理；如需重开请选择「处理中」并在意见里说明");
        }
        String prevStatus = f.getStatus();
        String nextStatus = bo.getStatus();
        String nextNote = bo.getHandleNote().trim();
        if (nextStatus.equals(prevStatus)
            && nextNote.equals(f.getHandleNote() == null ? "" : f.getHandleNote().trim())) {
            // 无变化：不覆盖处理人/处理时间（审计归属不被改写），也不发重复通知
            return;
        }
        // 三审回修（dspro P1-1）：读-改-写加状态乐观闸——两管理员并发处理时后写不再静默覆盖，
        // 影响行数=0 即判定已被他人处理
        int rows = feedbackMapper.update(null, new LambdaUpdateWrapper<DcsFeedback>()
            .eq(DcsFeedback::getFeedbackId, f.getFeedbackId())
            .eq(DcsFeedback::getDelFlag, "0")
            .eq(DcsFeedback::getStatus, prevStatus)
            .set(DcsFeedback::getStatus, nextStatus)
            .set(DcsFeedback::getHandlerId, LoginHelper.getUserId())
            .set(DcsFeedback::getHandlerName, handlerName())
            .set(DcsFeedback::getHandleNote, nextNote)
            .set(DcsFeedback::getHandleTime, new Date()));
        if (rows == 0) {
            throw new ServiceException("该反馈已被其他管理员处理，请刷新后重试");
        }
        if (f.getCreateBy() == null) {
            return;
        }
        try {
            String title = switch (nextStatus) {
                case DcsFeedback.STATUS_RESOLVED -> "你的反馈已解决";
                case DcsFeedback.STATUS_CLOSED -> "你的反馈已关闭";
                default -> "你的反馈有新进展";
            };
            noticeService.notifyIdem(f.getCreateBy(), NOTICE_REPLY, title,
                "《" + f.getTitle() + "》状态更新为「" + statusLabel(nextStatus) + "」："
                    + nextNote + " 点击查看详情。", null,
                // 幂等键用「旧态→新态」表达一次真实流转：同一对状态重复保存不再打扰，
                // 去掉毫秒时间戳（否则同一结论每次保存都会生成新键，唯一索引形同虚设）
                "feedback:reply:" + f.getFeedbackId() + ":" + prevStatus + "->" + nextStatus,
                USER_URL);
        } catch (Exception e) {
            log.warn("[Feedback] 处理通知发送失败 id={}: {}", f.getFeedbackId(), e.getMessage());
        }
    }

    private void enrich(List<DcsFeedback> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Set<Long> userIds = rows.stream().map(DcsFeedback::getCreateBy)
            .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> names = new HashMap<>();
        if (!userIds.isEmpty()) {
            try {
                for (Map<String, Object> row : userLookupMapper.selectBriefsByIds(new ArrayList<>(userIds))) {
                    Object id = row.get("user_id");
                    Object nick = row.get("nick_name");
                    Object user = row.get("user_name");
                    if (id != null) {
                        names.put(((Number) id).longValue(),
                            nick != null && !String.valueOf(nick).isBlank() ? String.valueOf(nick) : String.valueOf(user));
                    }
                }
            } catch (Exception ignored) {
                // 昵称回填失败不影响主列表
            }
        }
        for (DcsFeedback f : rows) {
            f.setCreateByName(names.getOrDefault(f.getCreateBy(), ""));
            if (f.getOssIds() != null && !f.getOssIds().isBlank()) {
                f.setOssIdList(Arrays.stream(f.getOssIds().split(","))
                    .map(String::trim).filter(s -> !s.isEmpty()).toList());
            }
        }
    }

    private String normalizeOssIds(String ossIds) {
        if (ossIds == null || ossIds.isBlank()) {
            return null;
        }
        List<String> ids = Arrays.stream(ossIds.split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).distinct().limit(3).toList();
        return ids.isEmpty() ? null : String.join(",", ids);
    }

    private String handlerName() {
        try {
            String name = LoginHelper.getLoginUser().getNickname();
            return name == null ? "" : (name.length() > 64 ? name.substring(0, 64) : name);
        } catch (Exception e) {
            return "";
        }
    }

    private static Map<String, Long> emptySummary() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put(DcsFeedback.STATUS_PENDING, 0L);
        out.put(DcsFeedback.STATUS_PROCESSING, 0L);
        out.put(DcsFeedback.STATUS_RESOLVED, 0L);
        out.put(DcsFeedback.STATUS_CLOSED, 0L);
        return out;
    }

    public static String statusLabel(String status) {
        return switch (status) {
            case DcsFeedback.STATUS_PENDING -> "待处理";
            case DcsFeedback.STATUS_PROCESSING -> "处理中";
            case DcsFeedback.STATUS_RESOLVED -> "已解决";
            case DcsFeedback.STATUS_CLOSED -> "已关闭";
            default -> status;
        };
    }
}
