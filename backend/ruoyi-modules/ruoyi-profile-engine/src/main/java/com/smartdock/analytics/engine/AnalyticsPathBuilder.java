package com.smartdock.analytics.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 路径分析纯函数（批8 拍板 Q1-A）：把按访问者分组的页面序列聚成"下一步"流向图。
 *
 * <p>口径与漏斗一致（拍板 Q7-A）：每个访客只认第一次按顺序走过的路径；
 * 连续重复页折叠为一次（replaceState/刷新不算拐弯）。</p>
 *
 * <p>节点按"步|页"分层：第 1 步用原页名，第 2/3 步再次到达的页带 ·2/·3 后缀。
 * 边只从第 i 步指向第 i+1 步，天然无环（ECharts 桑基要求 DAG；访客回访旧页
 * 会形成 home→docs→home 的环，页级节点必然报 cycle）。</p>
 *
 * @author smartdock
 */
public final class AnalyticsPathBuilder {

    /**
     * 输入事件：访客键 + 毫秒时间戳 + 页面路径。
     */
    public record PathEvent(String visitor, long time, String page) {
    }

    /**
     * @param events    时间窗内的 page_view 事件（无序亦可，内部按访客重排）
     * @param startPage 为空 = 入口视角；非空 = 从该页首次出现处截取
     * @param depth     最多保留的路径节点数（如 3 = 入口 + 后续 2 步）
     * @param maxNodes  图上最多节点数（防长尾把桑基变毛线球）
     */
    public static Map<String, Object> build(List<PathEvent> events, String startPage, int depth, int maxNodes) {
        // key = "步|页"，如 "0|/home"
        Map<String, Map<String, Long>> transitions = new LinkedHashMap<>();
        Map<String, Long> nodeHits = new LinkedHashMap<>();
        Map<String, Long> entries = new LinkedHashMap<>();
        Map<String, List<PathEvent>> byVisitor = new LinkedHashMap<>();
        for (PathEvent e : events) {
            if (e == null || e.visitor() == null || e.page() == null || e.page().isBlank()) {
                continue;
            }
            byVisitor.computeIfAbsent(e.visitor(), k -> new ArrayList<>()).add(e);
        }
        for (List<PathEvent> list : byVisitor.values()) {
            list.sort(Comparator.comparingLong(PathEvent::time));
            List<String> seq = new ArrayList<>();
            String prev = null;
            for (PathEvent e : list) {
                String page = e.page().trim();
                if (page.equals(prev)) {
                    continue;
                }
                seq.add(page);
                prev = page;
            }
            int from = 0;
            if (startPage != null && !startPage.isBlank()) {
                String want = startPage.trim();
                from = seq.indexOf(want);
                if (from < 0) {
                    continue;
                }
            } else if (!seq.isEmpty()) {
                entries.merge(seq.get(0), 1L, Long::sum);
            }
            int limit = Math.min(from + Math.max(1, depth), seq.size());
            for (int i = from; i < limit; i++) {
                int step = i - from;
                nodeHits.merge(step + "|" + seq.get(i), 1L, Long::sum);
                if (i + 1 < limit) {
                    transitions
                        .computeIfAbsent(step + "|" + seq.get(i), k -> new LinkedHashMap<>())
                        .merge((step + 1) + "|" + seq.get(i + 1), 1L, Long::sum);
                }
            }
        }
        // 长尾截断: 只留命中次数最高的 maxNodes 个节点, 与之无关的边丢弃
        List<String> kept = new ArrayList<>(nodeHits.keySet());
        kept.sort((a, b) -> Long.compare(nodeHits.get(b), nodeHits.get(a)));
        if (kept.size() > maxNodes) {
            kept = kept.subList(0, maxNodes);
        }
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (String key : kept) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("name", nodeName(key));
            node.put("value", nodeHits.get(key));
            nodes.add(node);
        }
        List<Map<String, Object>> links = new ArrayList<>();
        for (String from : transitions.keySet()) {
            if (!kept.contains(from)) {
                continue;
            }
            for (Map.Entry<String, Long> to : transitions.get(from).entrySet()) {
                if (!kept.contains(to.getKey())) {
                    continue;
                }
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("source", nodeName(from));
                link.put("target", nodeName(to.getKey()));
                link.put("value", to.getValue());
                links.add(link);
            }
        }
        links.sort((a, b) -> Long.compare((Long) b.get("value"), (Long) a.get("value")));
        List<Map<String, Object>> entryRows = new ArrayList<>();
        entries.entrySet().stream()
            .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
            .limit(maxNodes)
            .forEach(e -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("page", e.getKey());
                row.put("visitors", e.getValue());
                entryRows.add(row);
            });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nodes", nodes);
        out.put("links", links);
        out.put("entries", entryRows);
        out.put("visitors", (long) byVisitor.size());
        return out;
    }

    /**
     * "步|页" → 展示名：第 1 步原页名，第 2/3 步带 ·N 后缀（再次到达）。
     */
    static String nodeName(String key) {
        int sep = key.indexOf('|');
        int step = Integer.parseInt(key.substring(0, sep));
        String page = key.substring(sep + 1);
        return step == 0 ? page : page + "·" + (step + 1);
    }

    private AnalyticsPathBuilder() {
    }
}
