package com.smartdock.dcs.reach;

import com.smartdock.dcs.mapper.DcsUserLookupMapper;
import com.smartdock.dcs.service.DcsNoticeService;
import com.smartdock.profile.engine.ProfileReachChannel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站内通知触达通道(批9 拍板 Q3-A 首个 ReachChannel 实现)。
 *
 * <p>画像引擎定义契约与审计, 本类只做"发给某个系统用户"这一件事:
 * 用户不存在/已删 → 返回失败(触达明细记 FAILED, 支持重试);
 * 幂等靠 DcsNoticeService.notifyIdem 的 dedup_key 部分唯一索引。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InAppReachChannel implements ProfileReachChannel {

    private static final String NOTICE_TYPE = "PROFILE_REACH";

    private final DcsNoticeService noticeService;
    private final DcsUserLookupMapper userLookupMapper;

    @Override
    public String channel() {
        return "inapp";
    }

    @Override
    public Result send(Long userId, String title, String content, String dedupKey) {
        if (userId == null) {
            return Result.fail("收件人不是有效系统用户(主体ID非数字)");
        }
        List<Long> ids = List.of(userId);
        List<Map<String, Object>> briefs = userLookupMapper.selectBriefsByIds(ids);
        if (briefs == null || briefs.isEmpty()) {
            return Result.fail("系统用户不存在: " + userId);
        }
        try {
            boolean inserted = noticeService.notifyIdem(userId, NOTICE_TYPE,
                StringUtils.isBlank(title) ? "画像通知" : title,
                content == null ? "" : content, null, dedupKey);
            if (!inserted) {
                // dedup_key 命中: 该用户在本批次已收到(重试撞幂等键), 视为成功不重发
                log.info("[ProfileReach] inapp dedup hit user={} dedup={}", userId, dedupKey);
            }
            return Result.success();
        } catch (Exception e) {
            log.warn("[ProfileReach] inapp send failed user={} err={}", userId, e.getMessage());
            return Result.fail(StringUtils.substring(String.valueOf(e.getMessage()), 0, 200));
        }
    }

    @Override
    public List<Long> reachableUserIds(List<Long> candidateUserIds) {
        if (candidateUserIds == null || candidateUserIds.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> briefs = userLookupMapper.selectBriefsByIds(candidateUserIds);
        List<Long> out = new ArrayList<>();
        for (Map<String, Object> row : briefs) {
            Object id = row.get("user_id");
            if (id instanceof Number n) {
                out.add(n.longValue());
            }
        }
        return out;
    }

    @Override
    public Map<String, Object> describe() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channel", channel());
        out.put("name", "站内通知");
        out.put("scope", "用户型主体(subjectType=user 且主体ID为系统用户ID)");
        return out;
    }
}
