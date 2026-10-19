package com.smartdock.profile.operator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 打分映射纯函数(linear / bucket)——无状态无依赖, 单测主战场。
 *
 * @author smartdock
 */
public final class ScoreFunctions {

    private ScoreFunctions() {
    }

    /**
     * 线性映射: (x - lo) / (hi - lo) × scoreMax, direction=lower 时取 1-ratio;
     * 越界钳到 [0, scoreMax]; 结果四舍五入 2 位小数。
     *
     * <p>hi == lo 视为退化配置: x &gt;= hi 得满分, 否则 0 分。</p>
     */
    public static BigDecimal linear(BigDecimal x, ScoreSpec spec, BigDecimal scoreMax) {
        require(x, spec);
        BigDecimal ratio;
        int cmp = spec.getHi().compareTo(spec.getLo());
        if (cmp == 0) {
            ratio = x.compareTo(spec.getHi()) >= 0 ? BigDecimal.ONE : BigDecimal.ZERO;
        } else {
            ratio = x.subtract(spec.getLo())
                .divide(spec.getHi().subtract(spec.getLo()), 10, RoundingMode.HALF_UP);
        }
        if ("lower".equalsIgnoreCase(spec.getDirection())) {
            ratio = BigDecimal.ONE.subtract(ratio);
        }
        BigDecimal clamped = ratio.max(BigDecimal.ZERO).min(BigDecimal.ONE);
        return clamped.multiply(scoreMax).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * 分档映射: buckets 依序首个 lte 命中(x &lt;= lte)取其 score; 全不中取最后一档(兜底)。
     * 空 buckets 返回中性分(scoreMax/2)。
     */
    public static BigDecimal bucket(BigDecimal x, ScoreSpec spec, BigDecimal scoreMax) {
        require(x, spec);
        List<ScoreSpec.Bucket> buckets = spec.getBuckets();
        if (buckets == null || buckets.isEmpty()) {
            return scoreMax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        }
        for (ScoreSpec.Bucket b : buckets) {
            if (b.getLte() == null || x.compareTo(b.getLte()) <= 0) {
                return b.getScore();
            }
        }
        ScoreSpec.Bucket last = buckets.get(buckets.size() - 1);
        return last.getScore();
    }

    /**
     * 统一分发入口。
     */
    public static BigDecimal map(BigDecimal metric, ScoreSpec spec, BigDecimal scoreMax) {
        if (scoreMax == null) {
            throw new IllegalArgumentException("scoreMax required");
        }
        return "bucket".equalsIgnoreCase(spec.getType())
            ? bucket(metric, spec, scoreMax)
            : linear(metric, spec, scoreMax);
    }

    private static void require(BigDecimal x, ScoreSpec spec) {
        if (x == null) {
            throw new IllegalArgumentException("metric required for score mapping");
        }
        if (spec == null) {
            throw new IllegalArgumentException("score spec required");
        }
    }
}
