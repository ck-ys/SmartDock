package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smartdock.profile.domain.ProfileScoreDimension;
import com.smartdock.profile.mapper.ProfileEventMapper;
import com.smartdock.profile.mapper.ProfileScoreDimensionMapper;
import com.smartdock.profile.mapper.ProfileScoreRuleMapper;
import com.smartdock.profile.mapper.ProfileScoreSnapshotMapper;
import com.smartdock.profile.spi.DimensionResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「未配置打分口径」护栏的契约测试(单源重构第一批 B 片):
 * 无有效维度(未配置或全部跳过)的快照 detail 必须带 _noDimensions 标记,
 * 且此类快照不入低分复核队列——50 分只是占位不是评价。
 * 前四条为纯函数契约; 后两条走真引擎编排(mock 协作件), 防护栏本体被无声改掉。
 *
 * <p>纯单测基建(参照 BillingModelDecoratorInFlightUnitTest 配方):
 * JsonUtils 类初始化要 SpringUtils.getBean(ObjectMapper)——@BeforeAll 注入
 * mock ApplicationContext + 真 ObjectMapper; LambdaQueryWrapper 的 lambda 列解析
 * 要 MyBatis 表缓存——@BeforeAll 手工 initTableInfo; 租户走 TenantHelper.setDynamic
 * 线程 ThreadLocal(不碰 Redis/登录态)。</p>
 *
 * @author smartdock
 */
@Tag("local")
class ProfileScoreUnconfiguredGuardTest {

    @BeforeAll
    static void seedPureUnitTestInfrastructure() {
        org.springframework.context.ConfigurableApplicationContext ctx =
            Mockito.mock(org.springframework.context.ConfigurableApplicationContext.class);
        org.springframework.core.env.ConfigurableEnvironment env =
            Mockito.mock(org.springframework.core.env.ConfigurableEnvironment.class);
        Mockito.when(env.getProperty("tenant.enable")).thenReturn("true");
        Mockito.when(ctx.getEnvironment()).thenReturn(env);
        Mockito.when(ctx.getBean(org.springframework.core.env.Environment.class)).thenReturn(env);
        Mockito.when(ctx.getBean(ObjectMapper.class)).thenReturn(new ObjectMapper());
        new SpringUtils().setApplicationContext(ctx);

        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, ProfileScoreDimension.class);
        TableInfoHelper.initTableInfo(assistant, ProfileScoreSnapshot.class);
        TableInfoHelper.initTableInfo(assistant, com.smartdock.profile.domain.ProfileScoreRule.class);
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
    void flagKeyIsStableForFrontendContract() {
        // 前端(D 片)按此键显示「未配置打分规则」, 改名等于破坏契约
        assertTrue("_noDimensions".equals(ProfileScoreEngine.UNCONFIGURED_FLAG));
    }

    @Test
    void unconfiguredDetailIsRecognized() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put(ProfileScoreEngine.UNCONFIGURED_FLAG, true);
        assertTrue(ProfileScoreEngine.isUnconfiguredDetail(detail));
    }

    @Test
    void normalDetailIsNotUnconfigured() {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("act_freq", Map.of("dimName", "活跃度", "score", 88));
        assertFalse(ProfileScoreEngine.isUnconfiguredDetail(detail));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertFalse(ProfileScoreEngine.isUnconfiguredDetail(null));
        assertFalse(ProfileScoreEngine.isUnconfiguredDetail(new LinkedHashMap<>()));
    }

    private static ProfileScoreDimension dim(String code) {
        ProfileScoreDimension dim = new ProfileScoreDimension();
        dim.setDimCode(code);
        dim.setDimName(code + "-名称");
        dim.setCalcMode("EVENT_AGG");
        dim.setSource("dcs");
        dim.setEnabled("1");
        dim.setSortOrder(1);
        return dim;
    }

    /** 协作件全 mock 的引擎实例: 只验编排逻辑, 不连库。 */
    private static ProfileScoreEngine engine(DimensionEvaluator evaluator,
                                             ProfileReviewService review,
                                             List<ProfileScoreDimension> dims) {
        ProfileScoreDimensionMapper dimensionMapper = Mockito.mock(ProfileScoreDimensionMapper.class);
        ProfileScoreRuleMapper ruleMapper = Mockito.mock(ProfileScoreRuleMapper.class);
        ProfileScoreSnapshotMapper snapshotMapper = Mockito.mock(ProfileScoreSnapshotMapper.class);
        ProfileEventMapper eventMapper = Mockito.mock(ProfileEventMapper.class);
        Mockito.when(dimensionMapper.selectList(Mockito.any())).thenReturn(dims);
        Mockito.when(ruleMapper.selectList(Mockito.any())).thenReturn(Collections.emptyList());
        Mockito.when(snapshotMapper.selectList(Mockito.any())).thenReturn(Collections.emptyList());
        return new ProfileScoreEngine(dimensionMapper, ruleMapper, snapshotMapper, evaluator,
            eventMapper, List.of(), List.of(), review);
    }

    @Test
    void allSkippedDimensionsAreFlaggedUnconfiguredAndSkipReview() {
        // 夜审 P1: 维度配了但全部跳过(策略缺失/评估异常)——总分 50 占位必须与「未配置」同口径,
        // 打 _noDimensions 标记且不触发复核入队
        DimensionEvaluator evaluator = Mockito.mock(DimensionEvaluator.class);
        Mockito.when(evaluator.evaluate(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
            .thenReturn(DimensionResult.skipped());
        ProfileReviewService review = Mockito.mock(ProfileReviewService.class);
        ProfileScoreEngine engine = engine(evaluator, review, List.of(dim("act_freq")));

        ProfileScoreSnapshot snapshot = engine.recalcSubject("dcs", "user", "u-1");

        assertTrue(snapshot.getDimensionDetail().contains("\"" + ProfileScoreEngine.UNCONFIGURED_FLAG + "\""));
        assertEquals(0, snapshot.getTotalScore().compareTo(BigDecimal.valueOf(50)));
        Mockito.verify(review, Mockito.never()).evaluateAfterRecalc(Mockito.any());
    }

    @Test
    void partialSkipStillScoresAndEnqueuesReview() {
        // 反向护栏: 部分维度跳过、仍有有效得分时不得误标「未配置」, 复核链路照常
        DimensionEvaluator evaluator = Mockito.mock(DimensionEvaluator.class);
        Mockito.when(evaluator.evaluate(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
            .thenAnswer(inv -> "act_freq".equals(((ProfileScoreDimension) inv.getArgument(3)).getDimCode())
                ? DimensionResult.skipped()
                : new DimensionResult(new BigDecimal("80"), null, 5, new LinkedHashMap<>()));
        ProfileReviewService review = Mockito.mock(ProfileReviewService.class);
        ProfileScoreEngine engine = engine(evaluator, review, List.of(dim("act_freq"), dim("quality")));

        ProfileScoreSnapshot snapshot = engine.recalcSubject("dcs", "user", "u-1");

        assertFalse(snapshot.getDimensionDetail().contains("\"" + ProfileScoreEngine.UNCONFIGURED_FLAG + "\""));
        assertEquals(0, snapshot.getTotalScore().compareTo(BigDecimal.valueOf(80)));
        Mockito.verify(review).evaluateAfterRecalc(Mockito.any());
    }

    @Test
    void dimsAndRuleQueriesAreScopedBySubjectType() {
        // 题0-乙: 引擎载维度/权重必须带 subject_type 条件(员工/会员各一套)——
        // wrapper 断言防"查询忘了带类型、member 事件套用 user 维度"的回归
        DimensionEvaluator evaluator = Mockito.mock(DimensionEvaluator.class);
        Mockito.when(evaluator.evaluate(Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.any()))
            .thenReturn(DimensionResult.skipped());
        ProfileReviewService review = Mockito.mock(ProfileReviewService.class);
        ProfileScoreDimensionMapper dimensionMapper = Mockito.mock(ProfileScoreDimensionMapper.class);
        ProfileScoreRuleMapper ruleMapper = Mockito.mock(ProfileScoreRuleMapper.class);
        ProfileScoreSnapshotMapper snapshotMapper = Mockito.mock(ProfileScoreSnapshotMapper.class);
        Mockito.when(dimensionMapper.selectList(Mockito.any())).thenReturn(List.of(dim("act_freq")));
        Mockito.when(ruleMapper.selectList(Mockito.any())).thenReturn(Collections.emptyList());
        Mockito.when(snapshotMapper.selectList(Mockito.any())).thenReturn(Collections.emptyList());
        ProfileScoreEngine engine = new ProfileScoreEngine(dimensionMapper, ruleMapper, snapshotMapper, evaluator,
            Mockito.mock(ProfileEventMapper.class), List.of(), List.of(), review);

        engine.recalcSubject("dcs", "member", "member-1");

        @SuppressWarnings({"unchecked", "rawtypes"})
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProfileScoreDimension>> dimCaptor =
            org.mockito.ArgumentCaptor.forClass((Class) com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        Mockito.verify(dimensionMapper).selectList(dimCaptor.capture());
        String dimSql = dimCaptor.getValue().getSqlSegment();
        assertTrue(dimSql.contains("subject_type"), "维度查询缺 subject_type 条件: " + dimSql);
        assertTrue(dimCaptor.getValue().getParamNameValuePairs().containsValue("member"),
            "维度查询参数里没有 member: " + dimCaptor.getValue().getParamNameValuePairs());

        @SuppressWarnings({"unchecked", "rawtypes"})
        org.mockito.ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.smartdock.profile.domain.ProfileScoreRule>> ruleCaptor =
            org.mockito.ArgumentCaptor.forClass((Class) com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper.class);
        Mockito.verify(ruleMapper).selectList(ruleCaptor.capture());
        assertTrue(ruleCaptor.getValue().getSqlSegment().contains("subject_type"), "权重规则查询缺 subject_type 条件");
    }
}
