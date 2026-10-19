package com.smartdock.profile.spi;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 维度计算结果(策略与 EVENT_AGG 共用)。
 *
 * @author smartdock
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DimensionResult {

    /** 维度得分(0 ~ scoreMax, 引擎不二次缩放) */
    private BigDecimal score;

    /** 原始指标值(可空): 如完成率 0.83 / 平均响应 12.5h / 计数 7 */
    private BigDecimal metric;

    /** 样本量: 参与聚合的事件数 */
    private long sampleCount;

    /** 事实标签: 进快照 dimension_detail[dimCode].tags */
    private Map<String, Object> tags = new LinkedHashMap<>();

    /**
     * 跳过本维度(无证据且策略判定不适用)——引擎将其排除出权重归一化。
     */
    public static DimensionResult skipped() {
        return new DimensionResult(null, null, 0, new LinkedHashMap<>());
    }

    public boolean isSkipped() {
        return score == null;
    }
}
