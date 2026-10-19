package com.smartdock.risk.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.dromara.common.core.exception.ServiceException;
import com.smartdock.risk.domain.DcsRiskAction;
import com.smartdock.risk.mapper.DcsRiskActionMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("dev")
class RiskActionServiceUnitTest {

    @BeforeAll
    static void initTableInfo() {
        TableInfoHelper.initTableInfo(
            new MapperBuilderAssistant(new MybatisConfiguration(), "risk-action-service"),
            DcsRiskAction.class);
    }

    @Test
    void releasedDecisionSuppressesSameOrLowerRiskUntilOriginalExpiry() {
        DcsRiskActionMapper mapper = mock(DcsRiskActionMapper.class);
        RiskActionService service = new RiskActionService(mapper, new RiskActionPolicy());
        DcsRiskAction released = action("RELEASED", "DISMISSED");
        released.setActionId(9L);
        released.setRiskScore(80);
        released.setSourceTrack("USER");
        released.setExpireTime(new Date(System.currentTimeMillis() + 60_000));
        when(mapper.update(any(), any())).thenReturn(0);
        when(mapper.selectOne(any())).thenReturn(null, released);

        DcsRiskAction result = service.syncAssessment("200001",
            assessment(7L, 80, RiskScoreService.LEVEL_L4, "USER"), "test");

        assertThat(result).isNull();
        verify(mapper, never()).insert(any(DcsRiskAction.class));
    }

    @Test
    void higherNewRiskCanRecreateActionAfterManualRelease() {
        DcsRiskActionMapper mapper = mock(DcsRiskActionMapper.class);
        RiskActionService service = new RiskActionService(mapper, new RiskActionPolicy());
        DcsRiskAction released = action("RELEASED", "DISMISSED");
        released.setRiskScore(80);
        released.setSourceTrack("USER");
        released.setExpireTime(new Date(System.currentTimeMillis() + 60_000));
        when(mapper.update(any(), any())).thenReturn(0);
        when(mapper.selectOne(any())).thenReturn(null, released);
        when(mapper.insert(any(DcsRiskAction.class))).thenReturn(1);

        DcsRiskAction result = service.syncAssessment("200001",
            assessment(7L, 90, RiskScoreService.LEVEL_L4, "USER"), "test");

        assertThat(result).isNotNull();
        verify(mapper, times(1)).insert(any(DcsRiskAction.class));
    }

    @Test
    void dismissReviewUsesOneAtomicUpdateAndReleasesActiveAction() {
        DcsRiskActionMapper mapper = mock(DcsRiskActionMapper.class);
        RiskActionService service = new RiskActionService(mapper, new RiskActionPolicy());
        DcsRiskAction pending = action("ACTIVE", "PENDING");
        when(mapper.update(any(), any())).thenReturn(0, 1);
        when(mapper.selectOne(any())).thenReturn(pending);

        service.review("200001", 9L, 3L, "DISMISSED", "人工核实为误报");

        verify(mapper, times(2)).update(any(), any()); // expiry scan + one state transition
    }

    @Test
    void missingTenantScopedActionCannotBeReviewed() {
        DcsRiskActionMapper mapper = mock(DcsRiskActionMapper.class);
        RiskActionService service = new RiskActionService(mapper, new RiskActionPolicy());
        when(mapper.update(any(), any())).thenReturn(0);
        when(mapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> service.review("other-tenant", 9L, 3L, "CONFIRMED", "证据确认"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("不属于当前租户");
    }

    private DcsRiskAction action(String status, String reviewStatus) {
        DcsRiskAction action = new DcsRiskAction();
        action.setActionId(9L);
        action.setTenantId("200001");
        action.setUserId(7L);
        action.setActionType("TEMP_FREEZE");
        action.setStatus(status);
        action.setReviewStatus(reviewStatus);
        action.setExpireTime(new Date(System.currentTimeMillis() + 60_000));
        return action;
    }

    private RiskScoreService.RiskAssessment assessment(Long userId, int score, int level, String sourceTrack) {
        return new RiskScoreService.RiskAssessment(userId, "127.0.0.1", score, level, level, level,
            sourceTrack, List.of());
    }
}
