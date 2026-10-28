package com.smartdock.profile.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 场景模板定义(纯数据): 维度 + 权重 + 分群 + 漏斗四件套预填。
 *
 * <p>内置模板是代码常量({@code ProfileScenarioTemplates}), 导入的模板存
 * {@code profile_scenario_template} 表——两者共用这一个形状, 导出/导入/套用同一份定义往返。</p>
 *
 * <p>只允许 EVENT_AGG 维度: 模板要在不同环境间分享, STRATEGY 维度引用的是宿主环境
 * 的 Spring Bean, 搬过去就是死配置。</p>
 *
 * @author smartdock
 */
@Data
public class ScenarioTemplateDef implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 模板编码, 租户内唯一(内置模板与导入模板共用命名空间, 导入撞内置编码会被拒)。 */
    private String code;

    /** 模板名(展示用)。 */
    private String name;

    /** 场景标签, 如「零售商城」。 */
    private String scenario;

    /** 一句话人话说明。 */
    private String description;

    /** 是否推荐默认(仅内置「内部协作采用度」为 true)。 */
    private Boolean recommended;

    private List<DimensionDef> dimensions = new ArrayList<>();

    /** dimCode → 权重(0~1, 合计≈1)。 */
    private Map<String, Double> weights = new LinkedHashMap<>();

    private List<SegmentDef> segments = new ArrayList<>();

    private List<FunnelDef> funnels = new ArrayList<>();

    @Data
    public static class DimensionDef implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String dimCode;
        private String dimName;
        /** 固定 EVENT_AGG(见类注释)。 */
        private String calcMode = "EVENT_AGG";
        private Map<String, Object> calcConfig = new LinkedHashMap<>();
        private BigDecimal scoreMax = BigDecimal.valueOf(100);
        private Integer sortOrder = 0;
    }

    @Data
    public static class SegmentDef implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String segmentCode;
        private String segmentName;
        private String description;
        /** {@link ProfileSegmentRule} 形状的 Map: match + conditions[{field,op,value}]。 */
        private Map<String, Object> ruleConfig = new LinkedHashMap<>();
    }

    @Data
    public static class FunnelDef implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String funnelCode;
        private String funnelName;
        /** 每步 {eventName, name?}。 */
        private List<Map<String, Object>> steps = new ArrayList<>();
        private Integer windowDays = 7;
    }
}
