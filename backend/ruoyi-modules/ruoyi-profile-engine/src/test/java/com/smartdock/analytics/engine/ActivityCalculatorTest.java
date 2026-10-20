package com.smartdock.analytics.engine;

import com.smartdock.analytics.engine.ActivityCalculator.ActiveDay;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 活跃口径纯函数测试（批8 拍板 Q4-A：DAU/滚动 WAU/MAU/粘性）。
 */
@Tag("local")
class ActivityCalculatorTest {

    @Test
    @SuppressWarnings("unchecked")
    void dauCountsDistinctVisitorsPerDay() {
        Map<String, Object> out = ActivityCalculator.compute(List.of(
            new ActiveDay("2026-10-01", "a"), new ActiveDay("2026-10-01", "b"),
            new ActiveDay("2026-10-01", "a"), // 重复行被天×访客去重语义吞掉
            new ActiveDay("2026-10-02", "a")), 30);
        List<Long> dau = (List<Long>) out.get("dau");
        assertThat(dau).containsExactly(2L, 1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rollingWauSpansSevenDaysAcrossGaps() {
        // a 只在 10-01 活跃; 10-05 (间隔 4 天 < 7) WAU 仍含 a, 10-09 (间隔 8 天) 不含
        Map<String, Object> out = ActivityCalculator.compute(List.of(
            new ActiveDay("2026-10-01", "a"),
            new ActiveDay("2026-10-05", "b"),
            new ActiveDay("2026-10-09", "b")), 30);
        List<Long> wau = (List<Long>) out.get("wau");
        assertThat(wau).containsExactly(1L, 2L, 1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mauWindowRespected() {
        Map<String, Object> out = ActivityCalculator.compute(List.of(
            new ActiveDay("2026-09-01", "a"),
            new ActiveDay("2026-09-29", "b"), // 距 09-01 28 天, 在 30 天窗口内
            new ActiveDay("2026-10-05", "b")), 30);
        List<Long> mau = (List<Long>) out.get("mau");
        // 09-01: {a} → 1; 09-29: 窗口 [09-01+? ..09-29] 覆盖 09-01 → {a,b}=2; 10-05: 窗口 [09-06..10-05] 不含 09-01 → {b}=1
        assertThat(mau).containsExactly(1L, 2L, 1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void stickyIsDauOverMau() {
        Map<String, Object> out = ActivityCalculator.compute(List.of(
            new ActiveDay("2026-10-01", "a"), new ActiveDay("2026-10-01", "b"),
            new ActiveDay("2026-10-01", "c"), new ActiveDay("2026-10-01", "d"),
            new ActiveDay("2026-10-01", "e"), new ActiveDay("2026-10-01", "f"),
            new ActiveDay("2026-10-01", "g"), new ActiveDay("2026-10-01", "h"),
            new ActiveDay("2026-10-02", "a")), 30);
        List<Object> sticky = (List<Object>) out.get("sticky");
        // 10-02: DAU=1, MAU=8 → 12.5
        assertThat(sticky.get(sticky.size() - 1)).isEqualTo(12.5);
        Map<String, Object> latest = (Map<String, Object>) out.get("latest");
        assertThat(latest).containsEntry("dau", 1L).containsEntry("mau", 8L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyInputGivesEmptySeriesWithoutCrash() {
        Map<String, Object> out = ActivityCalculator.compute(List.of(), 30);
        assertThat((List<?>) out.get("dayList")).isEmpty();
        assertThat((Map<String, Object>) out.get("latest")).containsEntry("dau", null);
    }
}
