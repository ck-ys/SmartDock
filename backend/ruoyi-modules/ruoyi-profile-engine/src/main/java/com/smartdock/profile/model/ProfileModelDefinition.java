package com.smartdock.profile.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 通用评分模型配置(profile_score_model.config)。
 *
 * <p>一个模型 = N 个指标维度, 每个维度有指标口径(metric)与分箱规则(bins)。
 * 模型只描述“怎么算”, 不绑定具体业务源; RFM 只是 preset 的一种。</p>
 *
 * @author smartdock
 */
@Data
public class ProfileModelDefinition implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** GENERIC / RFM; RFM 会额外产出八类客户分层 */
    private String preset = "GENERIC";

    /** 批5A: RFM 八类分层的高低分界(1~5 分制), 缺省 3.5 与历史行为一致 */
    private BigDecimal rfmMid;

    /** 维度定义(顺序即展示顺序) */
    private List<ModelDimension> dimensions = new ArrayList<>();

    @Data
    public static class ModelDimension implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 维度码, 如 R / F / M / activity */
        private String code;

        private String name;

        /** 相对权重, 会按维度权重和归一化 */
        private BigDecimal weight = BigDecimal.ONE;

        private MetricCalc calc;

        private BinSpec bins;
    }

    /**
     * 指标口径: 从事件账本聚合出一个数值。
     */
    @Data
    public static class MetricCalc implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** RECENCY_DAYS / COUNT / COUNT_DISTINCT / SUM / AVG / LAST_VALUE / RATE */
        private String type;

        /** 空 = 全部事件 */
        private List<String> eventTypes = new ArrayList<>();

        /** COUNT_DISTINCT / SUM / AVG / LAST_VALUE 必填 */
        private String prop;

        /** RATE 分子事件 */
        private String numerator;

        /** RATE 分母事件 */
        private String denominator;
    }

    /**
     * 分箱规则: 指标值 → 0~maxScore(默认 1~5)。
     */
    @Data
    public static class BinSpec implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** fixed / mean / percentile / topN */
        private String method = "fixed";

        /** higher(默认, 越高越好) / lower(越低越好) */
        private String direction = "higher";

        /** fixed: 升序门槛, 长度 = scores.size()-1 */
        private List<BigDecimal> edges = new ArrayList<>();

        /** 各档分数, 长度 = 档位数 */
        private List<BigDecimal> scores = new ArrayList<>();

        /** percentile: 分位点(0~1 升序) */
        private List<BigDecimal> quantiles = new ArrayList<>();

        /** mean: 档位数(3/4/5 最常用), 默认 5 */
        private Integer bands = 5;

        /** topN: 每组人数 */
        private Integer n;

        /** 指标缺失时的分数, 默认取最低档 */
        private BigDecimal missingScore;

        /** 每档满分(用于归一化), 默认取 scores 最大值 */
        private BigDecimal maxScore;
    }
}
