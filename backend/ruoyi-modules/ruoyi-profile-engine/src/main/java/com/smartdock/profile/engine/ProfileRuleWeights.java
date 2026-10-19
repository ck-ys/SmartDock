package com.smartdock.profile.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 权重规则的纯函数校验/构造工具。
 *
 * <p>发布约束: 权重键必须与启用维度一一对应, 每个值在 [0,1], 合计为 1(容差 0.001),
 * 且至少一个正权重。引擎侧仍有“缺省均分”兜底, 但管理面发布必须显式对齐。</p>
 *
 * @author smartdock
 */
public final class ProfileRuleWeights {

    public static final double EPSILON = 0.001;

    private ProfileRuleWeights() {
    }

    public static List<String> validate(Map<String, Double> weights, Collection<String> enabledCodes) {
        List<String> errors = new ArrayList<>();
        Set<String> enabled = new TreeSet<>(enabledCodes == null ? List.of() : enabledCodes);
        if (enabled.isEmpty()) {
            errors.add("没有启用的评分维度, 无法发布权重规则");
            return errors;
        }
        if (weights == null || weights.isEmpty()) {
            errors.add("权重不能为空");
            return errors;
        }
        for (String code : enabled) {
            if (!weights.containsKey(code)) {
                errors.add("缺少维度权重: " + code);
            }
        }
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            if (!enabled.contains(entry.getKey())) {
                errors.add("权重引用了未启用维度: " + entry.getKey());
            }
            Double value = entry.getValue();
            if (value == null || value.isNaN() || value.isInfinite() || value < 0 || value > 1) {
                errors.add("维度 " + entry.getKey() + " 的权重必须在 0~1 之间");
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        double sum = sum(weights);
        if (Math.abs(sum - 1.0) > EPSILON) {
            errors.add("权重合计必须为 1, 当前为 " + String.format("%.4f", sum));
        }
        if (weights.values().stream().filter(Objects::nonNull).noneMatch(v -> v > 0)) {
            errors.add("至少需要一个大于 0 的权重");
        }
        return errors;
    }

    public static double sum(Map<String, Double> weights) {
        if (weights == null) {
            return 0;
        }
        return weights.values().stream()
            .filter(Objects::nonNull)
            .mapToDouble(Double::doubleValue)
            .sum();
    }

    /**
     * 均分权重; 空维度返回空 map。
     */
    public static Map<String, Double> balanced(Collection<String> codes) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) {
            return out;
        }
        List<String> ordered = new ArrayList<>(new TreeSet<>(codes));
        double each = 1.0 / ordered.size();
        for (int i = 0; i < ordered.size(); i++) {
            // 末位吸收浮点误差, 保证合计精确为 1
            out.put(ordered.get(i), i == ordered.size() - 1
                ? round(1.0 - each * (ordered.size() - 1)) : round(each));
        }
        return out;
    }

    /**
     * 用现有权重补齐缺失维度: 缺失维度均分剩余份额; 已有维度的份额保留。
     * 结果不保证合计为 1(调用方应再校验), 仅用于草稿初始化时的平滑过渡。
     */
    public static Map<String, Double> fillMissing(Map<String, Double> weights, Collection<String> codes) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (codes == null || codes.isEmpty()) {
            return out;
        }
        List<String> missing = new ArrayList<>();
        double used = 0;
        for (String code : new TreeSet<>(codes)) {
            Double v = weights == null ? null : weights.get(code);
            if (v == null) {
                missing.add(code);
            } else {
                out.put(code, v);
                used += v;
            }
        }
        if (!missing.isEmpty()) {
            double remaining = Math.max(0, 1.0 - used);
            double each = remaining / missing.size();
            for (String code : missing) {
                out.put(code, round(each));
            }
        }
        return out;
    }

    private static double round(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
