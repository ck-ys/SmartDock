package com.smartdock.profile.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 维度 calc_config 的结构校验(纯函数, 不连库)。
 *
 * <p>校验目标是“配置能被引擎安全执行”, 不替代 JSON Schema:
 * 算子/目标事件/属性键/时间窗/打分映射/缺失策略逐项检查。</p>
 *
 * @author smartdock
 */
public final class ProfileDimensionConfig {

    public static final Set<String> OPERATORS = Set.of(
        "COUNT", "COUNT_DISTINCT", "SUM", "AVG", "RATE", "LAST_VALUE");
    public static final Set<String> MISSING_POLICIES = Set.of("neutral", "zero", "skip");
    public static final Set<String> SCORE_TYPES = Set.of("linear", "bucket");
    public static final Set<String> WINDOW_TYPES = Set.of("all", "rolling");
    public static final Pattern PROP_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,62}$");

    private ProfileDimensionConfig() {
    }

    public static List<String> validate(String calcMode, Map<String, Object> cfg, BigDecimal scoreMax) {
        List<String> errors = new ArrayList<>();
        if (scoreMax == null || scoreMax.signum() <= 0) {
            errors.add("scoreMax 必须大于 0");
        }
        if (calcMode == null || calcMode.isBlank()) {
            errors.add("calcMode 不能为空");
            return errors;
        }
        if ("STRATEGY".equalsIgnoreCase(calcMode)) {
            Object bean = cfg == null ? null : cfg.get("strategyBean");
            if (bean != null && !(bean instanceof String)) {
                errors.add("strategyBean 必须是字符串");
            }
            return errors;
        }
        if (!"EVENT_AGG".equalsIgnoreCase(calcMode)) {
            errors.add("calcMode 只支持 EVENT_AGG / STRATEGY");
            return errors;
        }
        if (cfg == null || cfg.isEmpty()) {
            errors.add("calcConfig 不能为空");
            return errors;
        }

        Map<String, Object> metric = map(cfg.get("metric"));
        if (metric == null) {
            errors.add("metric 不能为空");
        } else {
            String operator = text(metric.get("operator")).toUpperCase();
            if (operator.isBlank()) {
                errors.add("metric.operator 不能为空");
            } else if (!OPERATORS.contains(operator)) {
                errors.add("不支持的算子: " + operator);
            } else {
                validateMetric(operator, metric, errors);
            }
        }

        Map<String, Object> window = map(cfg.get("window"));
        if (window != null) {
            String type = text(window.get("type"));
            if (!type.isBlank() && !WINDOW_TYPES.contains(type)) {
                errors.add("window.type 只支持 all / rolling");
            }
            if ("rolling".equalsIgnoreCase(type)) {
                Object days = window.get("days");
                if (!(days instanceof Number n) || n.intValue() <= 0) {
                    errors.add("rolling 时间窗必须配置 days > 0");
                }
            }
        }

        Map<String, Object> score = map(cfg.get("score"));
        if (score == null) {
            errors.add("score 打分映射不能为空");
        } else {
            validateScore(score, errors);
        }

        Object missing = cfg.get("missing");
        if (missing != null && !MISSING_POLICIES.contains(text(missing))) {
            errors.add("missing 只支持 neutral / zero / skip");
        }
        return errors;
    }

    private static void validateMetric(String operator, Map<String, Object> metric, List<String> errors) {
        switch (operator) {
            case "RATE" -> {
                validateTarget(map(metric.get("numerator")), "metric.numerator", errors);
                validateTarget(map(metric.get("denominator")), "metric.denominator", errors);
            }
            case "COUNT_DISTINCT" -> {
                validateTarget(map(metric.get("target")), "metric.target", errors);
                String dedupKey = text(metric.get("dedupKey"));
                if (dedupKey.isBlank()) {
                    errors.add("COUNT_DISTINCT 必须配置 dedupKey");
                } else if (!PROP_KEY.matcher(dedupKey).matches()) {
                    errors.add("dedupKey 只允许字母开头的字母/数字/下划线: " + dedupKey);
                }
            }
            case "SUM", "AVG", "LAST_VALUE" -> {
                validateTarget(map(metric.get("target")), "metric.target", errors);
                String prop = text(metric.get("prop"));
                if (prop.isBlank()) {
                    errors.add(operator + " 必须配置 prop");
                } else if (!PROP_KEY.matcher(prop).matches()) {
                    errors.add("prop 只允许字母开头的字母/数字/下划线: " + prop);
                }
            }
            default -> validateTarget(map(metric.get("target")), "metric.target", errors);
        }
    }

    private static void validateTarget(Map<String, Object> target, String label, List<String> errors) {
        if (target == null) {
            errors.add(label + " 不能为空");
            return;
        }
        if (text(target.get("eventType")).isBlank()) {
            errors.add(label + ".eventType 不能为空");
        }
        Map<String, Object> filter = map(target.get("propFilter"));
        if (filter != null) {
            if (filter.size() > 1) {
                errors.add(label + ".propFilter 目前只支持单键");
            }
            filter.keySet().forEach(key -> {
                if (!PROP_KEY.matcher(key).matches()) {
                    errors.add(label + ".propFilter 键非法: " + key);
                }
            });
        }
    }

    private static void validateScore(Map<String, Object> score, List<String> errors) {
        String type = text(score.get("type"));
        if (type.isBlank()) {
            type = "linear";
        }
        if (!SCORE_TYPES.contains(type)) {
            errors.add("score.type 只支持 linear / bucket");
            return;
        }
        if ("linear".equals(type)) {
            if (!(score.get("lo") instanceof Number)) {
                errors.add("linear 打分必须配置数值 lo");
            }
            if (!(score.get("hi") instanceof Number)) {
                errors.add("linear 打分必须配置数值 hi");
            }
            String direction = text(score.get("direction"));
            if (!direction.isBlank() && !"higher".equalsIgnoreCase(direction)
                && !"lower".equalsIgnoreCase(direction)) {
                errors.add("score.direction 只支持 higher / lower");
            }
            if (score.get("lo") instanceof Number lo && score.get("hi") instanceof Number hi
                && Double.compare(lo.doubleValue(), hi.doubleValue()) == 0) {
                errors.add("linear 打分 hi 与 lo 不能相等");
            }
        } else {
            Object rawBuckets = score.get("buckets");
            if (!(rawBuckets instanceof List<?> buckets) || buckets.isEmpty()) {
                errors.add("bucket 打分必须配置非空 buckets");
                return;
            }
            for (int i = 0; i < buckets.size(); i++) {
                Map<String, Object> bucket = map(buckets.get(i));
                if (bucket == null) {
                    errors.add("buckets[" + i + "] 必须是对象");
                    continue;
                }
                if (!(bucket.get("score") instanceof Number)) {
                    errors.add("buckets[" + i + "].score 必须是数值");
                }
                if (bucket.get("lte") != null && !(bucket.get("lte") instanceof Number)) {
                    errors.add("buckets[" + i + "].lte 必须是数值或省略");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
