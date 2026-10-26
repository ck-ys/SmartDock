package com.smartdock.analytics.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * R2-3 留存周期口径锁定: 日/周/月自然期偏移 + 恰好/累计两种单元格语义(含窗口外活跃计入累计最大偏移)。
 *
 * @author smartdock
 */
@Tag("local")
class RetentionPeriodCalculatorTest {

    private static RetentionPeriodCalculator.VisitorPeriod vp(String cohort, String visitor, String period) {
        return new RetentionPeriodCalculator.VisitorPeriod(LocalDate.parse(cohort), visitor, LocalDate.parse(period));
    }

    @Test
    void offsetBetweenDayWeekMonth() {
        assertEquals(2, RetentionPeriodCalculator.offsetBetween(
            LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-26"), "day"));
        // 周=ISO 周一对齐: 同一周内偏移 0, 隔一个周一偏移 1(10-19/10-26 均为周一)
        assertEquals(0, RetentionPeriodCalculator.offsetBetween(
            LocalDate.parse("2026-10-19"), LocalDate.parse("2026-10-23"), "week"));
        assertEquals(1, RetentionPeriodCalculator.offsetBetween(
            LocalDate.parse("2026-10-19"), LocalDate.parse("2026-10-26"), "week"));
        // 月=自然月
        assertEquals(1, RetentionPeriodCalculator.offsetBetween(
            LocalDate.parse("2026-09-01"), LocalDate.parse("2026-10-01"), "month"));
    }

    @Test
    void monthOffsetAcrossYearIsTwelve() {
        assertEquals(12, RetentionPeriodCalculator.offsetBetween(
            LocalDate.parse("2025-12-01"), LocalDate.parse("2026-12-01"), "month"));
    }

    @Test
    void exactCellsCountOnlyThatPeriod() {
        List<Map<String, Object>> cohorts = RetentionPeriodCalculator.compute(List.of(
            vp("2026-10-24", "a", "2026-10-24"),
            vp("2026-10-24", "a", "2026-10-26"),
            vp("2026-10-24", "b", "2026-10-24")), 7, "day", false);
        assertEquals(1, cohorts.size());
        Map<String, Object> cohort = cohorts.get(0);
        assertEquals(2L, cohort.get("total"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cells = (List<Map<String, Object>>) cohort.get("cells");
        assertEquals(7, cells.size());
        assertEquals(2L, cells.get(0).get("visitors"));
        assertEquals(0L, cells.get(1).get("visitors"));
        assertEquals(1L, cells.get(2).get("visitors"));
        assertEquals(0, BigDecimal.valueOf(50.0).compareTo((BigDecimal) cells.get(2).get("rate")));
    }

    @Test
    void cumulativeCellsCountMaxOffsetOnward() {
        List<Map<String, Object>> cohorts = RetentionPeriodCalculator.compute(List.of(
            vp("2026-10-24", "a", "2026-10-24"),
            vp("2026-10-24", "a", "2026-10-26")), 7, "day", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cells = (List<Map<String, Object>>) cohorts.get(0).get("cells");
        // a 的最大偏移=2: 累计口径第 0/1/2 期都是 1, 第 3 期起 0
        assertEquals(1L, cells.get(0).get("visitors"));
        assertEquals(1L, cells.get(1).get("visitors"));
        assertEquals(1L, cells.get(2).get("visitors"));
        assertEquals(0L, cells.get(3).get("visitors"));
    }

    @Test
    void activityBeyondWindowFeedsCumulativeOnly() {
        // 活跃在第 12 期(窗口 7 之外): 恰好口径不进任何格, 累计口径 0..6 全 1
        List<Map<String, Object>> exact = RetentionPeriodCalculator.compute(List.of(
            vp("2026-10-24", "a", "2026-10-24"),
            vp("2026-10-24", "a", "2026-11-05")), 7, "day", false);
        List<Map<String, Object>> cumulative = RetentionPeriodCalculator.compute(List.of(
            vp("2026-10-24", "a", "2026-10-24"),
            vp("2026-10-24", "a", "2026-11-05")), 7, "day", true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> exactCells = (List<Map<String, Object>>) exact.get(0).get("cells");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cumCells = (List<Map<String, Object>>) cumulative.get(0).get("cells");
        assertEquals(1L, exactCells.get(0).get("visitors"));
        assertEquals(0L, exactCells.get(6).get("visitors"));
        for (int i = 0; i < 7; i++) {
            assertEquals(1L, cumCells.get(i).get("visitors"), "cumulative cell " + i);
        }
    }

    @Test
    void weekGranularityPeriods() {
        // cohort 周一 10-19; 访客 a 第 1 周(10-19) / 第 2 周(10-28 落 10-26 周) / 第 3 周(11-03 落 11-02 周)活跃
        List<Map<String, Object>> cohorts = RetentionPeriodCalculator.compute(List.of(
            vp("2026-10-19", "a", "2026-10-19"),
            vp("2026-10-19", "a", "2026-10-28"),
            vp("2026-10-19", "a", "2026-11-03")), 4, "week", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cells = (List<Map<String, Object>>) cohorts.get(0).get("cells");
        assertEquals(1L, cells.get(0).get("visitors"));
        assertEquals(1L, cells.get(1).get("visitors"));
        assertEquals(1L, cells.get(2).get("visitors"));
        assertEquals(0L, cells.get(3).get("visitors"));
    }

    @Test
    void periodBeforeCohortDropped() {
        List<Map<String, Object>> cohorts = RetentionPeriodCalculator.compute(List.of(
            vp("2026-10-24", "a", "2026-10-24"),
            vp("2026-10-24", "a", "2026-10-23")), 3, "day", false);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cells = (List<Map<String, Object>>) cohorts.get(0).get("cells");
        assertEquals(1L, cells.get(0).get("visitors"));
        assertEquals(1L, cohorts.get(0).get("total"));
    }
}
