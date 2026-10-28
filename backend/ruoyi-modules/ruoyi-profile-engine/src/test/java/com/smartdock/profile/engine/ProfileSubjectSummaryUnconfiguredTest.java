package com.smartdock.profile.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdock.profile.event.ProfileSubject;
import com.smartdock.profile.mapper.ProfileScoreSnapshotMapper;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import org.dromara.common.core.utils.SpringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 单主体摘要的占位快照口径(单源重构 M1 D 片): 未配置维度的 _noDimensions 快照
 * 不得冒充真分数——scored=false + unconfigured=true, 前端据此显示
 * 「未配置打分口径」而不是假的 50 分。
 *
 * @author smartdock
 */
@Tag("local")
class ProfileSubjectSummaryUnconfiguredTest {

    @BeforeAll
    static void seedPureUnitTestInfrastructure() {
        // 同 ProfileMainViewPageTest 配方: tenant.enable + ObjectMapper 两件套
        org.springframework.context.ConfigurableApplicationContext ctx =
            Mockito.mock(org.springframework.context.ConfigurableApplicationContext.class);
        org.springframework.core.env.ConfigurableEnvironment env =
            Mockito.mock(org.springframework.core.env.ConfigurableEnvironment.class);
        Mockito.when(env.getProperty("tenant.enable")).thenReturn("true");
        Mockito.when(ctx.getEnvironment()).thenReturn(env);
        Mockito.when(ctx.getBean(org.springframework.core.env.Environment.class)).thenReturn(env);
        Mockito.when(ctx.getBean(ObjectMapper.class)).thenReturn(new ObjectMapper());
        new SpringUtils().setApplicationContext(ctx);
    }

    private ProfileSubjectMapper subjectMapper;
    private ProfileScoreSnapshotMapper snapshotMapper;
    private ProfileQueryService service;

    @BeforeEach
    void buildService() {
        subjectMapper = Mockito.mock(ProfileSubjectMapper.class);
        snapshotMapper = Mockito.mock(ProfileScoreSnapshotMapper.class);
        ProfilePiiService piiService = Mockito.mock(ProfilePiiService.class);
        Mockito.when(piiService.canViewScore()).thenReturn(true);
        service = new ProfileQueryService(subjectMapper, snapshotMapper,
            Mockito.mock(ProfileScoreEngine.class),
            Mockito.mock(ProfileDimensionService.class),
            Mockito.mock(ProfileRuleService.class),
            piiService);
        TenantHelper.setDynamic("000000");
        Mockito.when(subjectMapper.selectList(Mockito.any())).thenReturn(List.of(subject("u-1")));
    }

    @AfterEach
    void unbindTenant() {
        TenantHelper.clearDynamic();
    }

    @Test
    void placeholderSnapshotIsNotScored() {
        Mockito.when(snapshotMapper.selectList(Mockito.any()))
            .thenReturn(List.of(snapshot("{\"_noDimensions\":true}", "50")));

        Map<String, Object> out = service.subjectSummary("user", "u-1", "dcs");

        assertEquals(Boolean.FALSE, out.get("scored"));
        assertEquals(Boolean.TRUE, out.get("unconfigured"));
        assertFalse(out.containsKey("totalScore"), "占位快照不得输出总分");
    }

    @Test
    void realSnapshotStillScores() {
        Mockito.when(snapshotMapper.selectList(Mockito.any()))
            .thenReturn(List.of(snapshot("{\"act_freq\":{\"score\":42}}", "42")));

        Map<String, Object> out = service.subjectSummary("user", "u-1", "dcs");

        assertEquals(Boolean.TRUE, out.get("scored"));
        assertEquals(new BigDecimal("42"), out.get("totalScore"));
        assertFalse(out.containsKey("unconfigured"));
    }

    private ProfileSubject subject(String subjectId) {
        ProfileSubject subject = new ProfileSubject();
        subject.setTenantId("000000");
        subject.setSource("dcs");
        subject.setSubjectType("user");
        subject.setSubjectId(subjectId);
        subject.setDisplayName("张三");
        return subject;
    }

    private ProfileScoreSnapshot snapshot(String detailJson, String total) {
        ProfileScoreSnapshot snapshot = new ProfileScoreSnapshot();
        snapshot.setTenantId("000000");
        snapshot.setSource("dcs");
        snapshot.setSubjectType("user");
        snapshot.setSubjectId("u-1");
        snapshot.setTotalScore(new BigDecimal(total));
        snapshot.setDimensionDetail(detailJson);
        return snapshot;
    }
}
