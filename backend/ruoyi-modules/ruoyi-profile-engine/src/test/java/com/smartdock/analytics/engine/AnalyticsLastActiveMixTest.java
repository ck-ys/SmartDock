package com.smartdock.analytics.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R2-1 活跃构成卡与 DAU 同锚: 尾部全 0 补写行跳过, 取最后一个有会话的天。
 *
 * @author smartdock
 */
@Tag("local")
class AnalyticsLastActiveMixTest {

    private static Map<String, Object> row(String date, long fresh, long back) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("statDate", date);
        m.put("newVisitors", fresh);
        m.put("returningVisitors", back);
        return m;
    }

    @Test
    void skipsTrailingZeroRowsAndAnchorsLastActiveDay() {
        List<Map<String, Object>> trend = new ArrayList<>();
        trend.add(row("2026-10-18", 3, 5));
        trend.add(row("2026-10-19", 2, 6));
        trend.add(row("2026-10-23", 0, 0)); // rebuild 补写行, 顶掉尾行的正是它
        Map<String, Object> mix = AnalyticsService.lastActiveMix(trend);
        assertEquals("2026-10-19", mix.get("statDate"));
        assertEquals(2L, mix.get("newVisitors"));
        assertEquals(6L, mix.get("returningVisitors"));
    }

    @Test
    void allZeroTrendYieldsEmptyMix() {
        List<Map<String, Object>> trend = new ArrayList<>();
        trend.add(row("2026-10-22", 0, 0));
        trend.add(row("2026-10-23", 0, 0));
        assertTrue(AnalyticsService.lastActiveMix(trend).isEmpty(), "全 0 趋势不应编造构成");
    }

    @Test
    void normalTrendKeepsLastRow() {
        List<Map<String, Object>> trend = new ArrayList<>();
        trend.add(row("2026-10-18", 3, 5));
        trend.add(row("2026-10-19", 4, 7));
        Map<String, Object> mix = AnalyticsService.lastActiveMix(trend);
        assertEquals("2026-10-19", mix.get("statDate"));
        assertEquals(4L, mix.get("newVisitors"));
    }
}
