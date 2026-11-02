package com.smartdock.analytics.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 小修包件9（2026-11-02 拍板「全按推荐」）：凌晨兜底从「只补昨天」扩成
 * 幂等补 D-1..D-3——00:20 前后宕机一天, 次日轮次把缺的那天捞回来。
 */
@Tag("local")
class AnalyticsBackfillWindowTest {

    @Test
    void coversThreeDaysNearToFar() {
        List<String> days = AnalyticsDailyBackfillJob.backfillDays(LocalDate.of(2026, 11, 2));
        assertEquals(List.of("2026-11-01", "2026-10-31", "2026-10-30"), days);
    }

    @Test
    void monthBoundaryCrossesBackward() {
        // 跨月边界: 11-01 凌晨跑, 补 10-31/10-30/10-29
        List<String> days = AnalyticsDailyBackfillJob.backfillDays(LocalDate.of(2026, 11, 1));
        assertEquals(List.of("2026-10-31", "2026-10-30", "2026-10-29"), days);
    }
}
