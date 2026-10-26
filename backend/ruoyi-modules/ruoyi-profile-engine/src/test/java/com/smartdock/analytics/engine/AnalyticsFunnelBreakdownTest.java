package com.smartdock.analytics.engine;

import com.smartdock.analytics.query.AnalyticsEventRow;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R2-3 漏斗拆解口径锁定: 单访客步序推进(窗口内首满足顺序) + 首步事件 context 取值。
 *
 * @author smartdock
 */
@Tag("local")
class AnalyticsFunnelBreakdownTest {

    private static AnalyticsEventRow event(long id, String name, long ts, String context) {
        AnalyticsEventRow row = new AnalyticsEventRow();
        row.setEventId(id);
        row.setEventName(name);
        row.setOccurredTime(new Date(ts));
        row.setContext(context);
        return row;
    }

    @Test
    void walkMatchesStepsInOrderWithinWindow() {
        long day = 24L * 3600_000L;
        long t0 = 1_700_000_000_000L;
        // 窗口语义: 每一步须在「上一步命中事件」的窗口内(不是距首步), 跨会话超窗不累计
        List<AnalyticsEventRow> list = new ArrayList<>(List.of(
            event(1, "view", t0, null),
            event(2, "view", t0 + 3600_000L, null),
            event(3, "signup", t0 + 2 * day, null),        // 距 view 2 天, 在 3 天窗口内 ✓
            event(4, "view", t0 + 2 * day + 60_000L, null),
            event(5, "signup", t0 + 2 * day + 120_000L, null),
            event(6, "pay", t0 + 2 * day + 180_000L, null)));
        AnalyticsService.FunnelWalk inWindow = AnalyticsService.walkFunnel(
            list, List.of("view", "signup", "pay"), 3 * day);
        assertArrayEquals(new long[]{1, 1, 1}, inWindow.counts());
        // 同一序列收窄到 1 天窗口: signup 距 view 超窗, 链断在第二步
        AnalyticsService.FunnelWalk outOfWindow = AnalyticsService.walkFunnel(
            list, List.of("view", "signup", "pay"), day);
        assertArrayEquals(new long[]{1, 0, 0}, outOfWindow.counts());
    }

    @Test
    void walkStopsAfterLastStep() {
        long t0 = 1_700_000_000_000L;
        List<AnalyticsEventRow> list = new ArrayList<>(List.of(
            event(1, "view", t0, null),
            event(2, "signup", t0 + 1000L, null),
            event(3, "view", t0 + 2000L, null)));
        AnalyticsService.FunnelWalk walk = AnalyticsService.walkFunnel(
            list, List.of("view", "signup"), 24L * 3600_000L);
        assertArrayEquals(new long[]{1, 1}, walk.counts());
    }

    @Test
    void firstStepContextValueReadsEarliestStepOne() {
        List<AnalyticsEventRow> sorted = List.of(
            event(1, "view", 1000L, "{\"os\":\"Windows\",\"sourceChannel\":\"google\"}"),
            event(2, "view", 2000L, "{\"os\":\"macOS\",\"sourceChannel\":\"weibo\"}"));
        assertEquals("google", AnalyticsService.firstStepContextValue(sorted, "view", "sourceChannel"));
        assertEquals("Windows", AnalyticsService.firstStepContextValue(sorted, "view", "os"));
    }

    @Test
    void firstStepContextValueFallsBackToUnknown() {
        assertEquals("(未知)", AnalyticsService.firstStepContextValue(
            List.of(event(1, "view", 1000L, "{\"os\":\"Windows\"}")), "view", "sourceChannel"));
        assertEquals("(未知)", AnalyticsService.firstStepContextValue(
            List.of(event(1, "view", 1000L, "not-json")), "view", "os"));
        assertEquals("(未知)", AnalyticsService.firstStepContextValue(
            List.of(event(1, "click", 1000L, "{\"os\":\"Windows\"}")), "view", "os"));
    }
}
