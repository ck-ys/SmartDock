package com.smartdock.profile.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 评分模型配置校验(纯函数, 不连库)。
 *
 * @author smartdock
 */
public final class ProfileModelConfig {

    public static final Set<String> METRIC_TYPES = Set.of(
        "RECENCY_DAYS", "COUNT", "COUNT_DISTINCT", "SUM", "AVG", "LAST_VALUE", "RATE");
    public static final Set<String> BIN_METHODS = Set.of("fixed", "mean", "percentile", "topN");
    public static final Set<String> DIRECTIONS = Set.of("higher", "lower");
    public static final Pattern DIM_CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,31}$");
    public static final Pattern PROP_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,62}$");

    private ProfileModelConfig() {
    }

    public static List<String> validate(ProfileModelDefinition def) {
        List<String> errors = new ArrayList<>();
        if (def == null) {
            errors.add("模型配置不能为空");
            return errors;
        }
        if (def.getDimensions() == null || def.getDimensions().isEmpty()) {
            errors.add("模型至少需要一个维度");
            return errors;
        }
        Set<String> codes = new HashSet<>();
        BigDecimal weightSum = BigDecimal.ZERO;
        for (int i = 0; i < def.getDimensions().size(); i++) {
            ProfileModelDefinition.ModelDimension dim = def.getDimensions().get(i);
            String prefix = "dimensions[" + i + "]";
            if (dim == null) {
                errors.add(prefix + " 不能为空");
                continue;
            }
            if (dim.getCode() == null || !DIM_CODE.matcher(dim.getCode()).matches()) {
                errors.add(prefix + ".code 只允许字母开头的字母/数字/下划线, 最长 32");
            } else if (!codes.add(dim.getCode())) {
                errors.add(prefix + ".code 重复: " + dim.getCode());
            }
            if (dim.getName() == null || dim.getName().isBlank() || dim.getName().length() > 100) {
                errors.add(prefix + ".name 不能为空且不能超过 100 字");
            }
            BigDecimal weight = dim.getWeight() == null ? BigDecimal.ONE : dim.getWeight();
            if (weight.signum() < 0) {
                errors.add(prefix + ".weight 不能为负数");
            } else {
                weightSum = weightSum.add(weight);
            }
            validateCalc(dim.getCalc(), prefix + ".calc", errors);
            validateBins(dim.getBins(), prefix + ".bins", errors);
        }
        if (weightSum.signum() <= 0) {
            errors.add("维度权重合计必须大于 0");
        }
        return errors;
    }

    private static void validateCalc(ProfileModelDefinition.MetricCalc calc, String prefix, List<String> errors) {
        if (calc == null) {
            errors.add(prefix + " 不能为空");
            return;
        }
        String type = text(calc.getType()).toUpperCase();
        if (type.isBlank()) {
            errors.add(prefix + ".type 不能为空");
            return;
        }
        if (!METRIC_TYPES.contains(type)) {
            errors.add(prefix + ".type 不支持: " + type);
            return;
        }
        if (calc.getEventTypes() != null) {
            if (calc.getEventTypes().size() > 20) {
                errors.add(prefix + ".eventTypes 最多 20 个");
            }
            calc.getEventTypes().forEach(event -> {
                if (event == null || event.isBlank() || event.length() > 64) {
                    errors.add(prefix + ".eventTypes 含空值或超长事件名");
                }
            });
        }
        switch (type) {
            case "COUNT_DISTINCT", "SUM", "AVG", "LAST_VALUE" -> {
                String prop = text(calc.getProp());
                if (prop.isBlank()) {
                    errors.add(prefix + ".prop 不能为空");
                } else if (!PROP_KEY.matcher(prop).matches()) {
                    errors.add(prefix + ".prop 只允许字母开头的字母/数字/下划线: " + prop);
                }
            }
            case "RATE" -> {
                if (text(calc.getNumerator()).isBlank()) {
                    errors.add(prefix + ".numerator 不能为空");
                }
                if (text(calc.getDenominator()).isBlank()) {
                    errors.add(prefix + ".denominator 不能为空");
                }
            }
            default -> {
                // RECENCY_DAYS / COUNT 不需要额外字段
            }
        }
    }

    private static void validateBins(ProfileModelDefinition.BinSpec bins, String prefix, List<String> errors) {
        if (bins == null) {
            errors.add(prefix + " 不能为空");
            return;
        }
        String method = text(bins.getMethod());
        if (!BIN_METHODS.contains(method)) {
            errors.add(prefix + ".method 只支持 fixed / mean / percentile / topN");
            return;
        }
        String direction = text(bins.getDirection());
        if (!direction.isBlank() && !DIRECTIONS.contains(direction)) {
            errors.add(prefix + ".direction 只支持 higher / lower");
        }
        List<BigDecimal> scores = bins.getScores();
        if (scores == null || scores.isEmpty()) {
            errors.add(prefix + ".scores 不能为空");
            return;
        }
        for (int i = 0; i < scores.size(); i++) {
            if (scores.get(i) == null || scores.get(i).signum() < 0) {
                errors.add(prefix + ".scores[" + i + "] 必须是非负数");
            }
        }
        switch (method) {
            case "fixed" -> {
                List<BigDecimal> edges = bins.getEdges();
                if (edges == null || edges.isEmpty()) {
                    errors.add(prefix + ".edges 不能为空");
                } else if (edges.size() + 1 != scores.size()) {
                    errors.add(prefix + ".edges 长度必须等于 scores 长度 - 1");
                } else {
                    for (int i = 1; i < edges.size(); i++) {
                        if (edges.get(i - 1) == null || edges.get(i) == null
                            || edges.get(i).compareTo(edges.get(i - 1)) < 0) {
                            errors.add(prefix + ".edges 必须升序");
                            break;
                        }
                    }
                }
            }
            case "mean" -> {
                int bands = bins.getBands() == null ? 5 : bins.getBands();
                if (bands < 2 || bands > 20) {
                    errors.add(prefix + ".bands 必须在 2~20 之间");
                } else if (scores.size() != bands) {
                    errors.add(prefix + ".scores 长度必须等于 bands");
                }
            }
            case "percentile" -> {
                List<BigDecimal> quantiles = bins.getQuantiles();
                if (quantiles == null || quantiles.isEmpty()) {
                    errors.add(prefix + ".quantiles 不能为空");
                } else if (quantiles.size() + 1 != scores.size()) {
                    errors.add(prefix + ".quantiles 长度必须等于 scores 长度 - 1");
                } else {
                    BigDecimal prev = BigDecimal.valueOf(-1);
                    for (BigDecimal q : quantiles) {
                        if (q == null || q.signum() < 0 || q.compareTo(BigDecimal.ONE) >= 0
                            || q.compareTo(prev) <= 0) {
                            errors.add(prefix + ".quantiles 必须是 (0,1) 内的升序值");
                            break;
                        }
                        prev = q;
                    }
                }
            }
            case "topN" -> {
                if (bins.getN() == null || bins.getN() <= 0) {
                    errors.add(prefix + ".n 必须大于 0");
                }
            }
            default -> {
                // 已在上方拦截
            }
        }
        if (bins.getMissingScore() != null && bins.getMissingScore().signum() < 0) {
            errors.add(prefix + ".missingScore 不能为负数");
        }
        if (bins.getMaxScore() != null && bins.getMaxScore().signum() <= 0) {
            errors.add(prefix + ".maxScore 必须大于 0");
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }
}
