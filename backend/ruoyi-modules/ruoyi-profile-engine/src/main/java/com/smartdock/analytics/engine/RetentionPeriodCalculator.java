package com.smartdock.analytics.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * R2-3 留存周期口径(纯函数): 日/周/月自然期对齐 + 「恰好第N期/该期或之后」两种单元格语义。
 *
 * <p>周期一律 date_trunc 自然对齐(周=ISO 周一, 月=自然月), 不做滚动 30 天反直觉口径(拍板 R2-3)。
 * 「恰好第N期」= 该期恰好有活跃; 「该期或之后」= 访客的最大期偏移 ≥ N(之后任一期仍回来过)。
 * 超出窗口的更晚活跃不进单元格, 但计入累计口径的最大偏移——人还在, 只是窗口里没格子了。</p>
 *
 * @author smartdock
 */
public final class RetentionPeriodCalculator {

    private RetentionPeriodCalculator() {
    }

    /** 期偏移: day/week/month; 周期起点由 SQL date_trunc 保证对齐(周一/月初)。 */
    public static long offsetBetween(LocalDate cohort, LocalDate period, String granularity) {
        return switch (granularity) {
            case "week" -> ChronoUnit.WEEKS.between(cohort, period);
            case "month" -> ChronoUnit.MONTHS.between(cohort, period);
            default -> ChronoUnit.DAYS.between(cohort, period);
        };
    }

    /** 一个 (cohort, 访客, 活跃期) 三元组(SQL 去重后每访客每期至多一行)。 */
    public record VisitorPeriod(LocalDate cohort, String visitor, LocalDate period) {
    }

    public static List<Map<String, Object>> compute(List<VisitorPeriod> rows, int win, String granularity,
                                                    boolean cumulative) {
        // cohort → visitor → 命中期集合(累计口径用数据内最大期偏移, 不限窗口)
        Map<String, Map<String, java.util.Set<Long>>> offsetsByCohort = new LinkedHashMap<>();
        for (VisitorPeriod row : rows) {
            if (row.period().isBefore(row.cohort())) {
                continue;
            }
            long offset = offsetBetween(row.cohort(), row.period(), granularity);
            if (offset < 0) {
                continue;
            }
            offsetsByCohort.computeIfAbsent(row.cohort().toString(), k -> new LinkedHashMap<>())
                .computeIfAbsent(row.visitor(), k -> new java.util.HashSet<>()).add(offset);
        }
        List<Map<String, Object>> cohorts = new ArrayList<>();
        for (Map.Entry<String, Map<String, java.util.Set<Long>>> entry : offsetsByCohort.entrySet()) {
            long total = 0;
            Map<Long, Long> exactCounts = new LinkedHashMap<>();
            Map<Long, Long> cumulativeCounts = new LinkedHashMap<>();
            for (java.util.Set<Long> offsets : entry.getValue().values()) {
                if (offsets.contains(0L)) {
                    total++;
                }
                for (Long offset : offsets) {
                    exactCounts.merge(offset, 1L, Long::sum);
                }
                // 累计口径: 该访客在数据内最晚活跃期(不限窗口, 窗口外也算"还在")
                long maxOffset = offsets.stream().max(Long::compare).orElse(0L);
                for (long i = 0; i <= Math.min(maxOffset, win - 1); i++) {
                    cumulativeCounts.merge(i, 1L, Long::sum);
                }
            }
            List<Map<String, Object>> cells = new ArrayList<>();
            for (int i = 0; i < win; i++) {
                long visitors = cumulative ? cumulativeCounts.getOrDefault((long) i, 0L)
                    : exactCounts.getOrDefault((long) i, 0L);
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("day", i);
                cell.put("visitors", visitors);
                cell.put("rate", total == 0 ? BigDecimal.ZERO
                    : BigDecimal.valueOf(visitors * 100.0 / total).setScale(2, RoundingMode.HALF_UP));
                cells.add(cell);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cohort", entry.getKey());
            item.put("total", total);
            item.put("cells", cells);
            cohorts.add(item);
        }
        cohorts.sort(Comparator.comparing(item -> String.valueOf(item.get("cohort"))));
        return cohorts;
    }
}
