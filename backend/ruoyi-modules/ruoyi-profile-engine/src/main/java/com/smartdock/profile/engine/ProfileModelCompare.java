package com.smartdock.profile.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 模型版本对比纯函数（批9 拍板 Q1-A）：两个已完成 run 的逐主体分数对比。
 *
 * <p>输入按主体聚合的新旧 {level, score}，输出：升降分布 / 等级迁移矩阵 / Top 变化主体。
 * 只做聚合，不做任何 IO —— 与 ProfilePsi 配合供 compare / dryRun 两个入口复用。</p>
 *
 * @author smartdock
 */
public final class ProfileModelCompare {

    /**
     * 单主体一版的观测值。score 允许 null（该版未覆盖此主体）。
     */
    public record SubjectVersion(String subjectType, String subjectId, String displayName,
                                 String level, BigDecimal score) {
    }

    private ProfileModelCompare() {
    }

    /**
     * @param base 旧版（版本A）逐主体观测
     * @param cand 新版（版本B）逐主体观测
     * @param topN 变化最大的主体数量上限
     */
    public static Map<String, Object> compare(List<SubjectVersion> base, List<SubjectVersion> cand, int topN) {
        Map<String, SubjectVersion> byKey = new LinkedHashMap<>();
        for (SubjectVersion v : base == null ? List.<SubjectVersion>of() : base) {
            byKey.put(key(v), v);
        }
        Map<String, SubjectVersion> candByKey = new LinkedHashMap<>();
        for (SubjectVersion v : cand == null ? List.<SubjectVersion>of() : cand) {
            candByKey.put(key(v), v);
        }
        Set<String> keys = new LinkedHashSet<>();
        keys.addAll(byKey.keySet());
        keys.addAll(candByKey.keySet());

        long up = 0;
        long down = 0;
        long flat = 0;
        long onlyBase = 0;
        long onlyCand = 0;
        Map<String, Long> migration = new LinkedHashMap<>();
        List<Map<String, Object>> movers = new ArrayList<>();
        for (String k : keys) {
            SubjectVersion b = byKey.get(k);
            SubjectVersion c = candByKey.get(k);
            if (b == null) {
                onlyCand++;
                continue;
            }
            if (c == null) {
                onlyBase++;
                continue;
            }
            BigDecimal delta = delta(b.score(), c.score());
            if (delta.signum() > 0) {
                up++;
            } else if (delta.signum() < 0) {
                down++;
            } else {
                flat++;
            }
            String from = level(b);
            String to = level(c);
            if (!from.equals(to)) {
                migration.merge(from + " → " + to, 1L, Long::sum);
            }
            if (delta.signum() != 0) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("subjectType", b.subjectType());
                m.put("subjectId", b.subjectId());
                m.put("displayName", b.displayName());
                m.put("baseScore", b.score());
                m.put("candScore", c.score());
                m.put("delta", delta);
                m.put("baseLevel", from);
                m.put("candLevel", to);
                movers.add(m);
            }
        }
        movers.sort(Comparator.comparing((Map<String, Object> m) -> ((BigDecimal) m.get("delta")).abs()).reversed());

        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> deltaStat = new LinkedHashMap<>();
        deltaStat.put("up", up);
        deltaStat.put("down", down);
        deltaStat.put("flat", flat);
        deltaStat.put("onlyInBase", onlyBase);
        deltaStat.put("onlyInCand", onlyCand);
        out.put("deltaStat", deltaStat);
        List<Map<String, Object>> migrationRows = new ArrayList<>();
        migration.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .forEach(e -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("path", e.getKey());
                row.put("count", e.getValue());
                migrationRows.add(row);
            });
        out.put("migration", migrationRows);
        out.put("topMovers", movers.subList(0, Math.min(Math.max(topN, 1), movers.size())));
        out.put("compared", (long) keys.size());
        return out;
    }

    /** 等级分布计数（喂给 PSI 用）。 */
    public static Map<String, Long> levelDistribution(List<SubjectVersion> versions) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (SubjectVersion v : versions == null ? List.<SubjectVersion>of() : versions) {
            out.merge(level(v), 1L, Long::sum);
        }
        return out;
    }

    private static String key(SubjectVersion v) {
        return (v.subjectType() == null ? "user" : v.subjectType()) + "|" + v.subjectId();
    }

    private static String level(SubjectVersion v) {
        return v.level() == null || v.level().isBlank() ? "未分层" : v.level();
    }

    private static BigDecimal delta(BigDecimal base, BigDecimal cand) {
        if (base == null || cand == null) {
            return BigDecimal.ZERO;
        }
        return cand.subtract(base).setScale(2, RoundingMode.HALF_UP);
    }
}
