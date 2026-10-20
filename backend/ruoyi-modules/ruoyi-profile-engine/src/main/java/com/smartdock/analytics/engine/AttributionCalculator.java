package com.smartdock.analytics.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 渠道归因纯函数（批8 拍板 Q3-A）：首次/末次/线性三种记账法。
 *
 * <p>触点 = 访客的一次带渠道来源的访问；转化 = 目标事件（如注册）首次发生。
 * 只统计转化时间之前的触点；线性 = 每次转化按触点数平分记到各渠道头上。</p>
 *
 * @author smartdock
 */
public final class AttributionCalculator {

    public static final String NO_CHANNEL = "(无来源)";

    /**
     * 输入触点：访客键 + 毫秒时间戳 + 渠道名（空渠道归 "(无来源)"）。
     */
    public record Touch(String visitor, long time, String channel) {
    }

    /**
     * 输入转化：访客键 + 转化毫秒时间戳。
     */
    public record Conversion(String visitor, long time) {
    }

    /**
     * @return rows: channel / first / last / linear(一列小数) / touches(触点访客次数), 按首次归因降序;
     *         另有 conversions 总数与 touchedChannels 总渠道数。
     */
    public static Map<String, Object> attribute(List<Touch> touches, List<Conversion> conversions) {
        Map<String, List<Touch>> byVisitor = new LinkedHashMap<>();
        if (touches != null) {
            for (Touch t : touches) {
                if (t == null || t.visitor() == null) {
                    continue;
                }
                byVisitor.computeIfAbsent(t.visitor(), k -> new ArrayList<>()).add(t);
            }
        }
        byVisitor.values().forEach(list -> list.sort(Comparator.comparingLong(Touch::time)));

        Map<String, double[]> credits = new LinkedHashMap<>();
        Map<String, Long> touchVisitors = new LinkedHashMap<>();
        for (List<Touch> list : byVisitor.values()) {
            for (Touch t : list) {
                String channel = channelOf(t);
                touchVisitors.putIfAbsent(channel, 0L);
            }
        }
        long total = 0;
        if (conversions != null) {
            for (Conversion c : conversions) {
                if (c == null || c.visitor() == null) {
                    continue;
                }
                List<Touch> path = new ArrayList<>();
                for (Touch t : byVisitor.getOrDefault(c.visitor(), List.of())) {
                    if (t.time() <= c.time()) {
                        path.add(t);
                    }
                }
                if (path.isEmpty()) {
                    // 转化前没有任何带来源的访问: 归 "(无来源)", 渠道侧仍可解释
                    credit(credits, NO_CHANNEL, 0);
                    credit(credits, NO_CHANNEL, 1);
                    total++;
                    continue;
                }
                String first = channelOf(path.get(0));
                String last = channelOf(path.get(path.size() - 1));
                credit(credits, first, 0);
                credit(credits, last, 1);
                for (Touch t : path) {
                    creditLinear(credits, channelOf(t), 1.0 / path.size());
                }
                total++;
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, double[]> e : credits.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("channel", e.getKey());
            row.put("first", (long) e.getValue()[0]);
            row.put("last", (long) e.getValue()[1]);
            row.put("linear", Math.round(e.getValue()[2] * 100.0) / 100.0);
            row.put("visitors", touchVisitors.getOrDefault(e.getKey(), 0L));
            rows.add(row);
        }
        // 首次归因降序, 再按线性降序稳定排列
        rows.sort((a, b) -> {
            int byFirst = Long.compare((Long) b.get("first"), (Long) a.get("first"));
            return byFirst != 0 ? byFirst : Double.compare((Double) b.get("linear"), (Double) a.get("linear"));
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("conversions", total);
        out.put("channels", touchVisitors.size());
        return out;
    }

    private static String channelOf(Touch t) {
        return t.channel() == null || t.channel().isBlank() ? NO_CHANNEL : t.channel().trim();
    }

    private static void credit(Map<String, double[]> credits, String channel, int slot) {
        credits.computeIfAbsent(channel, k -> new double[3])[slot] += 1;
    }

    private static void creditLinear(Map<String, double[]> credits, String channel, double value) {
        credits.computeIfAbsent(channel, k -> new double[3])[2] += value;
    }

    private AttributionCalculator() {
    }
}
