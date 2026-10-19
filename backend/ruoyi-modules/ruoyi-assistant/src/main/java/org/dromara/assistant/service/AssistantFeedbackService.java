package org.dromara.assistant.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsAssistantFeedback;
import org.dromara.assistant.mapper.AssistantFeedbackMapper;
import org.dromara.assistant.mapper.AssistantMessageMapper;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 助手 👍👎 反馈服务（批G G6，Q13 轻量版）：toast 确认、管理端可查、喂 eval 数据集。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantFeedbackService {

    private final AssistantFeedbackMapper feedbackMapper;
    private final AssistantMessageMapper messageMapper;

    /** 提交反馈：带 messageId 时校验消息归属本人（防跨用户刷评）；
     *  AU-40(1008)：消息+评价 10 秒幂等窗——连点几下不重复入库（前端 voted 已挡同类，
     *  此处兜底网络重试/双开页场景），窗内重复直接当成功返回 */
    public void submit(String tenantId, Long userId, Long messageId, String rating, String note) {
        if (!"UP".equals(rating) && !"DOWN".equals(rating)) {
            throw new ServiceException("rating 仅支持 UP/DOWN");
        }
        if (messageId != null) {
            var msg = messageMapper.selectById(messageId);
            if (msg == null || !msg.getTenantId().equals(tenantId) || !msg.getUserId().equals(userId)) {
                throw new ServiceException("只能评价自己会话中的消息", 403);
            }
        }
        if (feedbackMapper.selectRecentDuplicate(tenantId, userId, messageId, rating) != null) {
            log.debug("[dcs-assistant] 反馈幂等命中（10s 窗内重复），跳过入库: user={} msg={} {}", userId, messageId, rating);
            return;
        }
        if (note != null && note.length() > 500) {
            note = note.substring(0, 500);
        }
        DcsAssistantFeedback row = new DcsAssistantFeedback();
        row.setId(IdWorker.getId());
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setMessageId(messageId);
        row.setRating(rating);
        row.setNote(note);
        feedbackMapper.insert(row);
    }

    /**
     * Q10（AI 缺口批 2026-10-14）：反馈聚合统计——好评率 + 点踩原因分布 + 近 4 周趋势。
     * 窗口默认 28 天（周趋势 4 周）；无样本时好评率返回 null（前端显示占位，不瞎算 0%）。
     */
    public java.util.Map<String, Object> stats(String tenantId, int days) {
        int windowDays = Math.max(7, Math.min(days <= 0 ? 28 : days, 366));
        java.util.List<java.util.Map<String, Object>> byRating =
            feedbackMapper.statsByRating(tenantId, windowDays);
        long up = 0;
        long down = 0;
        for (java.util.Map<String, Object> row : byRating) {
            long cnt = ((Number) row.get("cnt")).longValue();
            if ("UP".equals(row.get("rating"))) {
                up = cnt;
            } else if ("DOWN".equals(row.get("rating"))) {
                down = cnt;
            }
        }
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        long total = up + down;
        out.put("windowDays", windowDays);
        out.put("up", up);
        out.put("down", down);
        out.put("total", total);
        out.put("upRate", total == 0 ? null : Math.round(up * 1000.0 / total) / 10.0);
        out.put("downReasons", feedbackMapper.statsDownReasons(tenantId, windowDays, 8));
        // 周趋势：weeks = ceil(days/7)，至少 4 周才能画出「近4周」。
        // dspro P2-5 回修：先按 PG 周一口径铺满整条周轴（零样本周填 0），再叠真实数据——
        // 否则空周缺行，前端「近 N 周」标题随有数周数漂移成「近 1 周」误导窗口收窄
        int weekCount = Math.max(4, (windowDays + 6) / 7);
        java.util.List<java.util.Map<String, Object>> trend =
            feedbackMapper.statsWeeklyTrend(tenantId, weekCount);
        java.time.LocalDate thisMonday = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))
            .with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY));
        java.util.Map<String, java.util.Map<String, Object>> weeks = new java.util.LinkedHashMap<>();
        for (int i = weekCount - 1; i >= 0; i--) {
            String week = thisMonday.minusWeeks(i).toString();
            java.util.Map<String, Object> w = new java.util.LinkedHashMap<>();
            w.put("week", week);
            w.put("up", 0L);
            w.put("down", 0L);
            weeks.put(week, w);
        }
        for (java.util.Map<String, Object> row : trend) {
            java.util.Map<String, Object> w = weeks.get(String.valueOf(row.get("week")));
            if (w == null) {
                continue; // 窗口边缘的残周（早于轴起点）不进图
            }
            w.put("DOWN".equals(row.get("rating")) ? "down" : "up", ((Number) row.get("cnt")).longValue());
        }
        out.put("trend", new java.util.ArrayList<>(weeks.values()));
        return out;
    }

    /** 管理端反馈列表（feedback:query 权限，租户内全量） */
    public List<DcsAssistantFeedback> listForTenant(String tenantId, int limit, int offset) {
        return feedbackMapper.selectPage(tenantId, limit, offset);
    }

    /** 租户反馈总数（控制器分页 total） */
    public long countForTenant(String tenantId) {
        return feedbackMapper.countForTenant(tenantId);
    }
}
