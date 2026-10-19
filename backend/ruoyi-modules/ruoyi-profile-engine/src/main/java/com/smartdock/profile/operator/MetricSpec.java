package com.smartdock.profile.operator;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * EVENT_AGG 指标规格(calc_config.metric)——一个维度 = 一个指标算子 + 打分映射。
 *
 * @author smartdock
 */
@Data
public class MetricSpec {

    /** 算子: COUNT / COUNT_DISTINCT / SUM / AVG / RATE / LAST_VALUE */
    private String operator;

    /** 单目标算子(COUNT/COUNT_DISTINCT/SUM/AVG/LAST_VALUE)的事件定义 */
    private TargetSpec target;

    /** RATE 专用: 分子事件定义 */
    private TargetSpec numerator;

    /** RATE 专用: 分母事件定义 */
    private TargetSpec denominator;

    /** SUM/AVG/LAST_VALUE 必填: props 数值/文本键 */
    private String prop;

    /** COUNT_DISTINCT 必填: props 去重键 */
    private String dedupKey;

    /**
     * 事件目标: eventType + 可选 props 等值过滤。
     */
    @Data
    public static class TargetSpec {
        private String eventType;
        private Map<String, String> propFilter = new LinkedHashMap<>();
    }
}
