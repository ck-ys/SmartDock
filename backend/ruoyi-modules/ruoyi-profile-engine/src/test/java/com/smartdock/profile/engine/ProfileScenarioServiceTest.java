package com.smartdock.profile.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdock.analytics.engine.AnalyticsService;
import com.smartdock.profile.domain.ProfileScoreDimension;
import com.smartdock.profile.domain.ProfileScoreRule;
import com.smartdock.profile.domain.ProfileScenarioTemplate;
import com.smartdock.profile.mapper.ProfileScoreRuleMapper;
import com.smartdock.profile.mapper.ProfileScenarioTemplateMapper;
import com.smartdock.profile.model.ScenarioTemplateDef;
import com.smartdock.profile.query.ProfileDimensionBo;
import com.smartdock.profile.query.ProfileRuleBo;
import com.smartdock.profile.query.ProfileSegmentBo;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 场景模板服务纯函数测试(F 片): 套用不覆盖已有配置、权重合并归一、导入校验拦截。
 *
 * @author smartdock
 */
@Tag("local")
class ProfileScenarioServiceTest {

    private final ProfileScenarioTemplateMapper templateMapper = Mockito.mock(ProfileScenarioTemplateMapper.class);
    private final ProfileScoreRuleMapper ruleMapper = Mockito.mock(ProfileScoreRuleMapper.class);
    private final ProfileDimensionService dimensionService = Mockito.mock(ProfileDimensionService.class);
    private final ProfileRuleService ruleService = Mockito.mock(ProfileRuleService.class);
    private final ProfileSegmentService segmentService = Mockito.mock(ProfileSegmentService.class);
    private final AnalyticsService analyticsService = Mockito.mock(AnalyticsService.class);

    private final ProfileScenarioService service = new ProfileScenarioService(
        templateMapper, ruleMapper, dimensionService, ruleService, segmentService, analyticsService);

    @BeforeAll
    static void seedPureUnitTestInfrastructure() {
        // TenantHelper.setDynamic 要 tenant.enable 上下文才生效(无 Spring 环境下被禁用),
        // JsonUtils 静态初始化要 ObjectMapper——同 ProfileMainViewPageTest 配方
        ConfigurableApplicationContext ctx = Mockito.mock(ConfigurableApplicationContext.class);
        ConfigurableEnvironment env = Mockito.mock(ConfigurableEnvironment.class);
        Mockito.when(env.getProperty("tenant.enable")).thenReturn("true");
        Mockito.when(ctx.getEnvironment()).thenReturn(env);
        Mockito.when(ctx.getBean(ConfigurableEnvironment.class)).thenReturn(env);
        Mockito.when(ctx.getBean(ObjectMapper.class)).thenReturn(new ObjectMapper());
        new SpringUtils().setApplicationContext(ctx);
    }

    @BeforeEach
    void bindTenant() {
        TenantHelper.setDynamic("000000");
    }

    @AfterEach
    void unbindTenant() {
        TenantHelper.clearDynamic();
    }

    @Test
    void applyCreatesMissingPiecesAndMergesWeights() {
        // 现状: 只有一个与模板无关的旧维度; 无草稿无已发布规则
        Mockito.when(dimensionService.list(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(List.of(dimension("completion", "完成率")));
        Mockito.when(dimensionService.enabledCodes(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(List.of("completion", "purchase_frequency", "avg_order_amount",
                "refund_rate", "category_breadth"));
        Mockito.when(ruleService.current(Mockito.anyString(), Mockito.anyString())).thenReturn(null);
        ProfileScoreRule draft = new ProfileScoreRule();
        draft.setRuleId(42L);
        draft.setRevision(0);
        Mockito.when(ruleService.createDraftStrict(Mockito.any(ProfileRuleBo.class))).thenReturn(draft);
        Mockito.when(segmentService.list(Mockito.anyString())).thenReturn(List.of());
        Mockito.when(analyticsService.funnels(Mockito.anyString())).thenReturn(List.of());

        Map<String, Object> receipt = service.apply("retail_mall", null, "user");

        assertThat(receipt.get("dimensionsCreated")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly("purchase_frequency", "avg_order_amount", "refund_rate", "category_breadth");
        assertThat(receipt.get("dimensionsSkipped")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .isEmpty();
        assertThat(receipt.get("segmentsCreated")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .hasSize(2);
        assertThat(receipt.get("funnelsCreated")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .hasSize(1);
        assertThat(String.valueOf(receipt.get("draftNote"))).contains("草稿");

        // 权重草稿: 覆盖全部启用维度且 4 位舍入后合计精确为 1(末位吸收)
        ArgumentCaptor<ProfileRuleBo> captor = ArgumentCaptor.forClass(ProfileRuleBo.class);
        Mockito.verify(ruleService).updateDraft(captor.capture());
        Map<String, Double> weights = captor.getValue().getWeights();
        assertThat(weights).containsOnlyKeys("completion", "purchase_frequency",
            "avg_order_amount", "refund_rate", "category_breadth");
        assertThat(weights.values().stream().mapToDouble(Double::doubleValue).sum())
            .isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-9));

        Mockito.verify(ruleService, Mockito.times(1)).createDraftStrict(Mockito.any(ProfileRuleBo.class));
        Mockito.verify(dimensionService, Mockito.times(4)).create(Mockito.any(ProfileDimensionBo.class));
        Mockito.verify(segmentService, Mockito.times(2)).create(Mockito.any(ProfileSegmentBo.class));
    }

    @Test
    void applySkipsExistingCodesAndNeverTouchesOpenDraft() {
        // 与模板撞码的维度/分群/漏斗一律跳过; 已有草稿时不生成也不改
        Mockito.when(dimensionService.list(Mockito.anyString(), Mockito.anyString()))
            .thenReturn(List.of(dimension("purchase_frequency", "购买频次")));
        ProfileScoreRule open = new ProfileScoreRule();
        open.setRuleId(7L);
        Mockito.when(ruleMapper.selectOne(Mockito.any())).thenReturn(open);
        Mockito.when(segmentService.list(Mockito.anyString())).thenReturn(List.of());
        Mockito.when(analyticsService.funnels(Mockito.anyString())).thenReturn(List.of());

        Map<String, Object> receipt = service.apply("retail_mall", null, "user");

        assertThat(receipt.get("dimensionsSkipped")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
            .containsExactly("purchase_frequency");
        assertThat(String.valueOf(receipt.get("draftNote"))).contains("未改动");
        assertThat(receipt.get("draftRuleId")).isEqualTo(7L);
        // strict 创建从未被触达——绝不碰用户手上的草稿
        Mockito.verify(ruleService, Mockito.never()).createDraftStrict(Mockito.any(ProfileRuleBo.class));
        Mockito.verify(ruleService, Mockito.never()).updateDraft(Mockito.any(ProfileRuleBo.class));
        Mockito.verify(dimensionService, Mockito.times(3)).create(Mockito.any(ProfileDimensionBo.class));
    }

    @Test
    void applyPropagatesStrictCreateConflictInsteadOfSilentlyReusing() {
        // 三审回修(dspro P1): 并发下 strict 抛错必须原样冒泡(整单回滚), 不许再静默覆盖
        Mockito.when(dimensionService.list(Mockito.anyString(), Mockito.anyString())).thenReturn(List.of());
        Mockito.when(ruleMapper.selectOne(Mockito.any())).thenReturn(null);
        Mockito.when(segmentService.list(Mockito.anyString())).thenReturn(List.of());
        Mockito.when(analyticsService.funnels(Mockito.anyString())).thenReturn(List.of());
        Mockito.when(ruleService.createDraftStrict(Mockito.any(ProfileRuleBo.class)))
            .thenThrow(new DuplicateKeyException("uk_profile_rule_draft"));

        assertThatThrownBy(() -> service.apply("retail_mall", null, "user"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("并发创建");
        Mockito.verify(ruleService, Mockito.never()).updateDraft(Mockito.any(ProfileRuleBo.class));
    }

    @Test
    void importRejectsBuiltinCodeAndBrokenWeights() {
        ScenarioTemplateDef def = ProfileScenarioTemplates.byCode("retail_mall");

        ScenarioTemplateDef clash = ProfileScenarioTemplates.byCode("internal_collab");
        assertThatThrownBy(() -> service.importTemplate(clash))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("内置模板冲突");

        ScenarioTemplateDef broken = ProfileScenarioTemplates.byCode("saas_tool");
        broken.getWeights().put("usage_activity", 0.1);
        assertThatThrownBy(() -> service.importTemplate(broken))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("权重合计");

        // 正常导入: 自定义编码不撞内置命名空间, 查重通过后落库, recommended 强制关
        def.setCode("my_retail");
        def.setRecommended(true);
        Mockito.when(templateMapper.selectCount(Mockito.any())).thenReturn(0L);
        Map<String, Object> saved = service.importTemplate(def);
        assertThat(saved.get("code")).isEqualTo("my_retail");
        ArgumentCaptor<ProfileScenarioTemplate> row = ArgumentCaptor.forClass(ProfileScenarioTemplate.class);
        Mockito.verify(templateMapper).insertJsonb(row.capture());
        assertThat(row.getValue().getTemplateName()).isEqualTo("零售商城");
        assertThat(row.getValue().getDefinition()).contains("\"purchase_frequency\"");
        assertThat(row.getValue().getDefinition()).doesNotContain("\"recommended\":true");
    }

    @Test
    void importRejectsCaliberMismatchesThatApplyWouldHit() {
        // 三审回修(dspro/dsflash P1): 导入校验口径必须对齐落库校验, 防「导入过、套用炸」
        ScenarioTemplateDef oneStep = ProfileScenarioTemplates.byCode("retail_mall");
        oneStep.setCode("bad_one_step");
        oneStep.getFunnels().get(0).setSteps(oneStep.getFunnels().get(0).getSteps().subList(0, 1));
        assertThatThrownBy(() -> service.importTemplate(oneStep))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("2~10 步");

        ScenarioTemplateDef bigWindow = ProfileScenarioTemplates.byCode("retail_mall");
        bigWindow.setCode("bad_window");
        bigWindow.getFunnels().get(0).setWindowDays(365);
        assertThatThrownBy(() -> service.importTemplate(bigWindow))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("1~90");

        ScenarioTemplateDef bigScore = ProfileScenarioTemplates.byCode("retail_mall");
        bigScore.setCode("bad_score");
        bigScore.getDimensions().get(0).setScoreMax(java.math.BigDecimal.valueOf(5000));
        assertThatThrownBy(() -> service.importTemplate(bigScore))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("scoreMax");

        ScenarioTemplateDef longName = ProfileScenarioTemplates.byCode("retail_mall");
        longName.setCode("bad_name");
        longName.getDimensions().get(0).setDimName("超".repeat(101));
        assertThatThrownBy(() -> service.importTemplate(longName))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("名称不能超过 100 字");

        ScenarioTemplateDef nullDims = ProfileScenarioTemplates.byCode("retail_mall");
        nullDims.setCode("bad_null");
        nullDims.setDimensions(null);
        assertThatThrownBy(() -> service.importTemplate(nullDims))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("至少要有一个维度");

        ScenarioTemplateDef partialWeights = ProfileScenarioTemplates.byCode("retail_mall");
        partialWeights.setCode("bad_partial");
        partialWeights.getWeights().remove("refund_rate");
        partialWeights.getWeights().put("purchase_frequency", 0.5);
        assertThatThrownBy(() -> service.importTemplate(partialWeights))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("缺少维度权重");
    }

    private static ProfileScoreDimension dimension(String code, String name) {
        ProfileScoreDimension d = new ProfileScoreDimension();
        d.setDimCode(code);
        d.setDimName(name);
        return d;
    }
}
