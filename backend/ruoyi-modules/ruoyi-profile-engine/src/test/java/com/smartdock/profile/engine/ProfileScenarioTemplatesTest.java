package com.smartdock.profile.engine;

import com.smartdock.profile.model.ProfileSegmentRule;
import com.smartdock.profile.model.ScenarioTemplateDef;
import org.dromara.common.json.utils.JsonUtils;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 内置场景模板结构护栏(F 片): 坏一处启动期就炸, 不等用户套用时才踩。
 *
 * @author smartdock
 */
@Tag("local")
class ProfileScenarioTemplatesTest {

    @Test
    void sevenBuiltinTemplatesWithUniqueCodes() {
        List<ScenarioTemplateDef> all = ProfileScenarioTemplates.builtin();
        assertThat(all).hasSize(7);
        Set<String> codes = new HashSet<>();
        for (ScenarioTemplateDef def : all) {
            assertThat(codes.add(def.getCode())).as("编码重复: " + def.getCode()).isTrue();
        }
        assertThat(codes).containsExactlyInAnyOrder(
            "internal_collab", "retail_mall", "food_delivery", "tourism",
            "community", "saas_tool", "mes_operator");
    }

    @Test
    void internalCollabIsTheOnlyRecommendedAndMatchesBuiltinCaliber() {
        List<ScenarioTemplateDef> all = ProfileScenarioTemplates.builtin();
        assertThat(all.stream().filter(d -> Boolean.TRUE.equals(d.getRecommended()))).hasSize(1);
        ScenarioTemplateDef def = ProfileScenarioTemplates.byCode(ProfileScenarioTemplates.INTERNAL_COLLAB);
        assertThat(def).isNotNull();

        // 维度与内置 dcs 四维度逐字一致(编码+名称)
        assertThat(def.getDimensions()).extracting(ScenarioTemplateDef.DimensionDef::getDimCode)
            .containsExactly("completion", "response", "quality", "negative");
        // v1-builtin 权重: completion .35 / response .25 / quality .25 / negative .15
        assertThat(def.getWeights()).containsEntry("completion", 0.35)
            .containsEntry("response", 0.25).containsEntry("quality", 0.25)
            .containsEntry("negative", 0.15);
        // 内置两分群 + 内置交付漏斗
        assertThat(def.getSegments()).extracting(ScenarioTemplateDef.SegmentDef::getSegmentCode)
            .containsExactly("high_value_active", "low_score_watch");
        assertThat(def.getFunnels()).extracting(ScenarioTemplateDef.FunnelDef::getFunnelCode)
            .containsExactly("task_delivery");
    }

    @Test
    void everyTemplatePassesFullStructuralValidation() {
        // 服务层校验是私有的, 这里等价复刻四道关键护栏(维度配置/权重/分群/漏斗),
        // 加一道总闸: 直接用 ProfileDimensionConfig + ProfileSegmentRule 的真校验器。
        for (ScenarioTemplateDef def : ProfileScenarioTemplates.builtin()) {
            Set<String> dimCodes = new HashSet<>();
            for (ScenarioTemplateDef.DimensionDef dim : def.getDimensions()) {
                assertThat(dimCodes.add(dim.getDimCode())).as(def.getCode() + " 维度编码重复").isTrue();
                assertThat(ProfileDimensionConfig.validate("EVENT_AGG", dim.getCalcConfig(),
                    dim.getScoreMax() == null ? BigDecimal.valueOf(100) : dim.getScoreMax()))
                    .as(def.getCode() + " 维度 " + dim.getDimCode() + " 配置不合法").isEmpty();
            }
            assertThat(Math.abs(ProfileRuleWeights.sum(def.getWeights()) - 1.0))
                .as(def.getCode() + " 权重合计≠1").isLessThanOrEqualTo(ProfileRuleWeights.EPSILON);
            assertThat(def.getWeights().keySet()).as(def.getCode() + " 权重引用了不存在的维度")
                .isSubsetOf(dimCodes);
            for (ScenarioTemplateDef.SegmentDef seg : def.getSegments()) {
                ProfileSegmentRule rule = JsonUtils.parseObject(
                    JsonUtils.toJsonString(seg.getRuleConfig()), ProfileSegmentRule.class);
                assertThat(ProfileSegmentRule.validate(rule))
                    .as(def.getCode() + " 分群 " + seg.getSegmentCode() + " 规则不合法").isEmpty();
            }
            for (ScenarioTemplateDef.FunnelDef funnel : def.getFunnels()) {
                assertThat(funnel.getSteps()).as(def.getCode() + " 漏斗步骤为空").isNotEmpty();
                for (var step : funnel.getSteps()) {
                    assertThat(String.valueOf(step.get("eventName"))).as(def.getCode() + " 漏斗步骤缺事件名").isNotBlank();
                }
                assertThat(funnel.getWindowDays()).as(def.getCode() + " 漏斗窗口天数越界")
                    .isBetween(1, 365);
            }
        }
    }

    @Test
    void templateJsonRoundTripsThroughSerializer() {
        // 导出/导入走同一份定义形状: 序列化→反序列化后字段不丢
        ScenarioTemplateDef def = ProfileScenarioTemplates.byCode("retail_mall");
        String json = JsonUtils.toJsonString(def);
        ScenarioTemplateDef back = JsonUtils.parseObject(json, ScenarioTemplateDef.class);
        assertThat(back.getCode()).isEqualTo("retail_mall");
        assertThat(back.getDimensions()).hasSize(def.getDimensions().size());
        assertThat(back.getWeights()).containsEntry("purchase_frequency", 0.35);
        assertThat(back.getFunnels().get(0).getSteps()).hasSize(3);
    }

    /**
     * 三审 P0 护栏(glm53+dsflash 双席实锤): 分群里 dimension.&lt;码&gt; 取的是 0~100 归一分数,
     * 模板阈值必须按分数口径写——首版 12 条按业务原值写(300 元/5%/0.98), 永空或反向圈人。
     * 钉死 7 套的条件表(改阈值必须连本表一起改), 外加值域护栏: 分数阈值应落在 [1,100]。
     */
    @Test
    void segmentConditionsUseScoreCaliberNotBusinessValues() {
        Map<String, Map<String, String>> expected = Map.of(
            "internal_collab", Map.of(
                "high_value_active", "totalScore GTE 75",
                "low_score_watch", "totalScore LT 60"),
            "retail_mall", Map.of(
                "high_value", "dimension.avg_order_amount GTE 56",
                "churn_watch", "lastEventDays GTE 60"),
            "food_delivery", Map.of(
                "heavy_users", "dimension.order_frequency GTE 30",
                "complaint_watch", "dimension.complaint_rate LTE 75"),
            "tourism", Map.of(
                "revisit_crowd", "dimension.visit_frequency GTE 25",
                "no_show_watch", "dimension.booking_completion LT 50"),
            "community", Map.of(
                "core_creators", "dimension.creation_activity GTE 30",
                "violation_watch", "dimension.violation_rate LTE 67"),
            "saas_tool", Map.of(
                "sticky_accounts", "dimension.usage_activity GTE 50",
                "trial_stall", "dimension.paid_conversion LT 10"),
            "mes_operator", Map.of(
                "excellent_operators", "dimension.qc_pass_rate GTE 98",
                "slow_response", "dimension.alarm_response LT 50"));
        for (ScenarioTemplateDef def : ProfileScenarioTemplates.builtin()) {
            Map<String, String> want = expected.get(def.getCode());
            assertThat(want).as(def.getCode() + " 缺少期望条件表").isNotNull();
            for (ScenarioTemplateDef.SegmentDef seg : def.getSegments()) {
                String condition = seg.getRuleConfig().get("conditions") instanceof List<?> list
                    && !list.isEmpty()
                    ? conditionText(list.get(0)) : "";
                assertThat(condition)
                    .as(def.getCode() + "." + seg.getSegmentCode() + " 条件被改动(阈值改动须连本测试一起改)")
                    .isEqualTo(want.get(seg.getSegmentCode()));
                // 值域护栏: dimension.* 分数阈值 <1 分没有区分度(=全员/无人), >100 永不命中
                if (condition.startsWith("dimension.")) {
                    double v = Double.parseDouble(condition.replaceAll(".* ", ""));
                    assertThat(v).as(def.getCode() + "." + seg.getSegmentCode() + " 分数阈值越界").isBetween(1.0, 100.0);
                }
            }
        }
    }

    private static String conditionText(Object raw) {
        if (raw instanceof Map<?, ?> m) {
            return String.valueOf(m.get("field")) + " " + m.get("op") + " " + m.get("value");
        }
        return String.valueOf(raw);
    }

    /** 三审回修(dspro P2-8): w() 按维度声明顺序赋权有静默错配风险——7 套权重表全钉死。 */
    @Test
    void weightTablesArePinnedPerTemplate() {
        for (ScenarioTemplateDef def : ProfileScenarioTemplates.builtin()) {
            Map<String, Double> w = def.getWeights();
            switch (def.getCode()) {
                case "internal_collab" -> assertThat(w).containsEntry("completion", 0.35)
                    .containsEntry("response", 0.25).containsEntry("quality", 0.25)
                    .containsEntry("negative", 0.15);
                case "retail_mall" -> assertThat(w).containsEntry("purchase_frequency", 0.35)
                    .containsEntry("avg_order_amount", 0.35).containsEntry("refund_rate", 0.15)
                    .containsEntry("category_breadth", 0.15);
                case "food_delivery", "tourism", "community" ->
                    assertThat(w.values().stream().mapToDouble(Double::doubleValue).sum()).isEqualTo(1.0);
                case "saas_tool" -> assertThat(w).containsEntry("usage_activity", 0.4)
                    .containsEntry("feature_breadth", 0.3).containsEntry("paid_conversion", 0.3);
                case "mes_operator" -> assertThat(w).containsEntry("job_completion", 0.35)
                    .containsEntry("alarm_response", 0.3).containsEntry("qc_pass_rate", 0.35);
                default -> throw new AssertionError("未知模板: " + def.getCode());
            }
            // 3+4 结构(前三套四维、后三套三维)之外的模板须补钉
            assertThat(def.getDimensions()).as(def.getCode() + " 维度数变化须补权重钉")
                .hasSizeLessThanOrEqualTo(4);
        }
    }
}
