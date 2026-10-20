package com.smartdock.profile.engine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PSI(Population Stability Index) 稳定性纯函数（批9 拍板 Q6-A 风暴加固）。
 *
 * <p>试算预览用它量化"草稿配置对分层分布的偏移程度"：
 * PSI&lt;0.1 稳定 / 0.1~0.25 中等 / &gt;0.25 显著偏移（信用评分业口径）。</p>
 *
 * @author smartdock
 */
public final class ProfilePsi {

    public static final String STABLE = "STABLE";
    public static final String MODERATE = "MODERATE";
    public static final String SHIFTED = "SHIFTED";

    private ProfilePsi() {
    }

    /**
     * @param baseline 基线分布（当前生效版本的分层人数）
     * @param candidate 候选分布（草稿试算的分层人数）
     * @return {psi, verdict} —— 桶集合取两者并集; 人数为 0 的占比按 0.0001 下限防 ln(0)
     */
    public static Map<String, Object> of(Map<String, Long> baseline, Map<String, Long> candidate) {
        Set<String> buckets = new LinkedHashSet<>();
        if (baseline != null) {
            buckets.addAll(baseline.keySet());
        }
        if (candidate != null) {
            buckets.addAll(candidate.keySet());
        }
        long baseTotal = total(baseline);
        long candTotal = total(candidate);
        double psi = 0;
        if (baseTotal > 0 && candTotal > 0) {
            for (String bucket : buckets) {
                double baseRate = rate(baseline == null ? 0 : baseline.getOrDefault(bucket, 0L), baseTotal);
                double candRate = rate(candidate == null ? 0 : candidate.getOrDefault(bucket, 0L), candTotal);
                psi += (candRate - baseRate) * Math.log(candRate / baseRate);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("psi", BigDecimal.valueOf(psi).setScale(4, RoundingMode.HALF_UP));
        out.put("verdict", verdict(psi));
        return out;
    }

    /** 三档判级（展示层配色依据）。 */
    public static String verdict(double psi) {
        if (psi < 0.1) {
            return STABLE;
        }
        return psi <= 0.25 ? MODERATE : SHIFTED;
    }

    /** 分布表: 每层的新旧人数/占比/样本量提示（n<30 标 smallSample）。 */
    public static List<Map<String, Object>> distribution(Map<String, Long> baseline, Map<String, Long> candidate) {
        Set<String> buckets = new LinkedHashSet<>();
        if (baseline != null) {
            buckets.addAll(baseline.keySet());
        }
        if (candidate != null) {
            buckets.addAll(candidate.keySet());
        }
        long baseTotal = total(baseline);
        long candTotal = total(candidate);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String bucket : buckets) {
            long base = baseline == null ? 0 : baseline.getOrDefault(bucket, 0L);
            long cand = candidate == null ? 0 : candidate.getOrDefault(bucket, 0L);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("level", bucket);
            row.put("baseCount", base);
            row.put("candCount", cand);
            row.put("delta", cand - base);
            row.put("baseRate", percent(base, baseTotal));
            row.put("candRate", percent(cand, candTotal));
            row.put("smallSample", cand > 0 && cand < 30);
            rows.add(row);
        }
        return rows;
    }

    private static long total(Map<String, Long> map) {
        if (map == null) {
            return 0;
        }
        long t = 0;
        for (Long v : map.values()) {
            if (v != null) {
                t += v;
            }
        }
        return t;
    }

    private static double rate(Long count, long total) {
        double c = count == null ? 0 : count;
        double r = total <= 0 ? 0 : c / total;
        return Math.max(r, 0.0001);
    }

    private static BigDecimal percent(long count, long total) {
        if (total <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(count * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
    }
}
