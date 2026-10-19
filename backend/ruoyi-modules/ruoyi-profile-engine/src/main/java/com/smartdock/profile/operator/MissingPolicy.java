package com.smartdock.profile.operator;

/**
 * 缺失证据策略(无样本/算子返回 null 时)。
 *
 * @author smartdock
 */
public enum MissingPolicy {

    /** 中性分 50(缺省): 无证据不惩罚 */
    NEUTRAL,

    /** 0 分: 无证据视为最差 */
    ZERO,

    /** 跳过维度: 不参与本次快照与权重归一化 */
    SKIP;

    /**
     * 宽松解析: null/未知值 → NEUTRAL。
     */
    public static MissingPolicy of(String raw) {
        if (raw == null || raw.isBlank()) {
            return NEUTRAL;
        }
        try {
            return valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return NEUTRAL;
        }
    }
}
