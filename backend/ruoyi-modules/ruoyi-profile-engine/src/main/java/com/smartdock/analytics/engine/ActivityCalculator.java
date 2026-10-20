package com.smartdock.analytics.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 活跃口径纯函数（批8 拍板 Q4-A）：DAU 序列 + 滚动 WAU/MAU + 粘性。
 *
 * <p>滚动口径按自然日窗口：某天的 WAU = 该天与其前 6 天（共 7 天）出现过的去重访客；
 * MAU 同理 30 天。粘性 = DAU/MAU。</p>
 *
 * @author smartdock
 */
public final class ActivityCalculator {

    /**
     * 输入行：某天(yyyy-MM-dd) 活跃过的一个访客键（天×访客已去重）。
     */
    public record ActiveDay(String day, String visitor) {
    }

    /**
     * @param mauWindow MAU 滚动窗口天数（粘性口径），WAU 固定 7 天
     */
    public static Map<String, Object> compute(List<ActiveDay> rows, int mauWindow) {
        TreeMap<Long, java.util.Set<String>> byDay = new TreeMap<>();
        for (ActiveDay row : rows) {
            if (row == null || row.day() == null || row.visitor() == null) {
                continue;
            }
            byDay.computeIfAbsent(LocalDate.parse(row.day()).toEpochDay(), k -> new java.util.HashSet<>())
                .add(row.visitor());
        }
        List<String> days = new ArrayList<>();
        List<Long> dau = new ArrayList<>();
        List<Long> wau = new ArrayList<>();
        List<Long> mau = new ArrayList<>();
        List<Object> sticky = new ArrayList<>();
        Long lastDau = null;
        Long lastWau = null;
        Long lastMau = null;
        Object lastSticky = null;
        for (Map.Entry<Long, java.util.Set<String>> entry : byDay.entrySet()) {
            long epochDay = entry.getKey();
            java.util.Set<String> week = union(byDay, epochDay - 6, epochDay);
            java.util.Set<String> month = union(byDay, epochDay - (mauWindow - 1), epochDay);
            days.add(LocalDate.ofEpochDay(epochDay).toString());
            long d = entry.getValue().size();
            long w = week.size();
            long m = month.size();
            dau.add(d);
            wau.add(w);
            mau.add(m);
            Object s = m == 0 ? null : Math.round(d * 1000.0 / m) / 10.0;
            sticky.add(s);
            lastDau = d;
            lastWau = w;
            lastMau = m;
            lastSticky = s;
        }
        Map<String, Object> latest = new LinkedHashMap<>();
        latest.put("dau", lastDau);
        latest.put("wau", lastWau);
        latest.put("mau", lastMau);
        latest.put("sticky", lastSticky);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dayList", days);
        out.put("dau", dau);
        out.put("wau", wau);
        out.put("mau", mau);
        out.put("sticky", sticky);
        out.put("latest", latest);
        return out;
    }

    private static java.util.Set<String> union(TreeMap<Long, java.util.Set<String>> byDay, long fromDay, long toDay) {
        java.util.Set<String> all = new java.util.HashSet<>();
        for (java.util.Set<String> visitors : byDay.subMap(fromDay, true, toDay, true).values()) {
            all.addAll(visitors);
        }
        return all;
    }

    private ActivityCalculator() {
    }
}
