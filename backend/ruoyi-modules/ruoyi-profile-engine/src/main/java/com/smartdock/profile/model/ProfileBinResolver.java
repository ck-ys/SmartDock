package com.smartdock.profile.model;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 分箱纯函数: fixed / mean / percentile / topN。
 *
 * <p>方向语义: higher = 指标越高越好; lower = 越低越好。
 * topN 的 scores 按“最好档 → 最差档”排列, 不再按 direction 反转。</p>
 *
 * @author smartdock
 */
public final class ProfileBinResolver {

    private ProfileBinResolver() {
    }

    @Data
    @AllArgsConstructor
    public static class Population {
        /** 升序, 不含 null */
        private List<BigDecimal> sorted;
        private BigDecimal mean;
        private BigDecimal std;

        public static Population of(Collection<BigDecimal> raw) {
            List<BigDecimal> values = new ArrayList<>();
            if (raw != null) {
                raw.stream().filter(v -> v != null).sorted().forEach(values::add);
            }
            if (values.isEmpty()) {
                return new Population(List.of(), BigDecimal.ZERO, BigDecimal.ZERO);
            }
            BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal mean = sum.divide(BigDecimal.valueOf(values.size()), 10, RoundingMode.HALF_UP);
            BigDecimal variance = values.stream()
                .map(v -> v.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 10, RoundingMode.HALF_UP);
            BigDecimal std = variance.sqrt(MathContext.DECIMAL64).setScale(10, RoundingMode.HALF_UP);
            return new Population(values, mean, std);
        }
    }

    @Data
    public static class BinResult {
        private BigDecimal score;
        private List<BigDecimal> edges = new ArrayList<>();
        private Integer rank;
        private String method;

        public BinResult(BigDecimal score, List<BigDecimal> edges, Integer rank, String method) {
            this.score = score;
            this.edges = edges == null ? new ArrayList<>() : edges;
            this.rank = rank;
            this.method = method;
        }
    }

    public static BinResult resolve(ProfileModelDefinition.BinSpec spec, BigDecimal value, Population population) {
        if (spec == null) {
            throw new IllegalArgumentException("bins spec required");
        }
        if (value == null) {
            return new BinResult(missingScore(spec), List.of(), null, spec.getMethod());
        }
        String method = spec.getMethod() == null ? "fixed" : spec.getMethod().trim();
        return switch (method) {
            case "mean" -> mean(value, spec, population);
            case "percentile" -> percentile(value, spec, population);
            case "topN" -> topN(value, spec, population);
            default -> fixed(value, spec);
        };
    }

    public static BigDecimal maxScore(ProfileModelDefinition.BinSpec spec) {
        if (spec == null) {
            return BigDecimal.ONE;
        }
        if (spec.getMaxScore() != null && spec.getMaxScore().signum() > 0) {
            return spec.getMaxScore();
        }
        if (spec.getScores() != null && !spec.getScores().isEmpty()) {
            return spec.getScores().stream().filter(v -> v != null)
                .max(BigDecimal::compareTo).orElse(BigDecimal.ONE);
        }
        return BigDecimal.ONE;
    }

    private static BinResult fixed(BigDecimal value, ProfileModelDefinition.BinSpec spec) {
        List<BigDecimal> edges = spec.getEdges() == null ? List.of() : spec.getEdges();
        int index = binIndex(value, edges);
        return new BinResult(scoreAt(spec, index), edges, null, "fixed");
    }

    private static BinResult mean(BigDecimal value, ProfileModelDefinition.BinSpec spec, Population pop) {
        int bands = spec.getBands() == null ? 5 : spec.getBands();
        if (pop == null || pop.getSorted().isEmpty() || pop.getStd().signum() == 0) {
            // 全量相同或没有样本: 没有区分度, 给中位档
            return new BinResult(scoreAt(spec, spec.getScores().size() / 2), List.of(), null, "mean");
        }
        List<BigDecimal> edges = new ArrayList<>();
        BigDecimal half = BigDecimal.valueOf(bands / 2.0);
        for (int i = 1; i < bands; i++) {
            BigDecimal offset = BigDecimal.valueOf(i).subtract(half);
            edges.add(pop.getMean().add(pop.getStd().multiply(offset)).setScale(6, RoundingMode.HALF_UP));
        }
        int index = binIndex(value, edges);
        return new BinResult(scoreAt(spec, index), edges, null, "mean");
    }

    private static BinResult percentile(BigDecimal value, ProfileModelDefinition.BinSpec spec, Population pop) {
        List<BigDecimal> quantiles = spec.getQuantiles() == null ? List.of() : spec.getQuantiles();
        if (pop == null || pop.getSorted().isEmpty()) {
            return new BinResult(missingScore(spec), List.of(), null, "percentile");
        }
        List<BigDecimal> edges = new ArrayList<>();
        for (BigDecimal q : quantiles) {
            edges.add(quantile(pop.getSorted(), q.doubleValue()));
        }
        if (!edges.isEmpty() && edges.get(0).compareTo(edges.get(edges.size() - 1)) == 0) {
            // 全体同分: 没有分位差异, 给中位档
            return new BinResult(scoreAt(spec, spec.getScores().size() / 2), edges, null, "percentile");
        }
        int index = binIndex(value, edges);
        return new BinResult(scoreAt(spec, index), edges, null, "percentile");
    }

    private static BinResult topN(BigDecimal value, ProfileModelDefinition.BinSpec spec, Population pop) {
        int n = spec.getN() == null || spec.getN() <= 0 ? 1 : spec.getN();
        if (pop == null || pop.getSorted().isEmpty()) {
            return new BinResult(missingScore(spec), List.of(), null, "topN");
        }
        boolean lower = "lower".equalsIgnoreCase(spec.getDirection());
        int rank = 1;
        for (BigDecimal v : pop.getSorted()) {
            if (lower ? v.compareTo(value) < 0 : v.compareTo(value) > 0) {
                rank++;
            }
        }
        int bucket = (rank - 1) / n;
        List<BigDecimal> scores = spec.getScores();
        int index = Math.min(bucket, scores.size() - 1);
        return new BinResult(scores.get(index), List.of(), rank, "topN");
    }

    private static BigDecimal scoreAt(ProfileModelDefinition.BinSpec spec, int index) {
        List<BigDecimal> scores = spec.getScores();
        if (scores == null || scores.isEmpty()) {
            return BigDecimal.ZERO;
        }
        int safe = Math.max(0, Math.min(index, scores.size() - 1));
        if ("lower".equalsIgnoreCase(spec.getDirection())) {
            safe = scores.size() - 1 - safe;
        }
        return scores.get(safe);
    }

    private static BigDecimal missingScore(ProfileModelDefinition.BinSpec spec) {
        if (spec.getMissingScore() != null) {
            return spec.getMissingScore();
        }
        return BigDecimal.ZERO;
    }

    private static int binIndex(BigDecimal value, List<BigDecimal> edges) {
        for (int i = 0; i < edges.size(); i++) {
            BigDecimal edge = edges.get(i);
            if (edge != null && value.compareTo(edge) <= 0) {
                return i;
            }
        }
        return edges.size();
    }

    /**
     * 线性插值分位数(与 PostgreSQL percentile_cont 一致)。
     */
    public static BigDecimal quantile(List<BigDecimal> sorted, double q) {
        if (sorted == null || sorted.isEmpty()) {
            return BigDecimal.ZERO;
        }
        if (sorted.size() == 1) {
            return sorted.get(0);
        }
        double pos = (sorted.size() - 1) * q;
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) {
            return sorted.get(lo);
        }
        BigDecimal fraction = BigDecimal.valueOf(pos - lo);
        return sorted.get(lo)
            .add(sorted.get(hi).subtract(sorted.get(lo)).multiply(fraction))
            .setScale(10, RoundingMode.HALF_UP);
    }
}
