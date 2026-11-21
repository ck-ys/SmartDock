package com.smartdock.risk;

import com.smartdock.risk.domain.RiskRule;
import com.smartdock.risk.domain.vo.RiskRuleScoreVo;
import com.smartdock.risk.mapper.RiskEventMapper;
import com.smartdock.risk.mapper.RiskRuleMapper;
import com.smartdock.risk.service.HybridRiskPolicy;
import com.smartdock.risk.service.RiskScoreService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("dev")
class RiskScoreServiceUnitTest {

    @Test
    void returnsEventRuleTrackWhenItsLevelIsHigher() {
        RiskEventMapper eventMapper = mock(RiskEventMapper.class);
        RiskRuleMapper ruleMapper = mock(RiskRuleMapper.class);
        RiskRule globalRule = globalRule();
        when(ruleMapper.selectOne(any())).thenReturn(globalRule);
        when(eventMapper.eventRuleScores(7L, "127.0.0.1", "000000")).thenReturn(List.of(
            new RiskRuleScoreVo("LOGIN_FAIL", 65, 3),
            new RiskRuleScoreVo("UPLOAD_ABUSE", 20, 1)
        ));
        when(eventMapper.sumUserRiskScore(eq(7L), any(), any())).thenReturn(45);
        when(eventMapper.sumIpRiskScore(eq("127.0.0.1"), any(), any())).thenReturn(30);

        RiskScoreService.RiskAssessment assessment = service(eventMapper, ruleMapper)
            .assess(7L, "127.0.0.1");

        assertThat(assessment.level()).isEqualTo(3);
        assertThat(assessment.ruleLevel()).isEqualTo(3);
        assertThat(assessment.globalLevel()).isEqualTo(2);
        assertThat(assessment.sourceTrack()).isEqualTo("RULE");
        assertThat(assessment.totalScore()).isEqualTo(65);
        assertThat(assessment.ruleScores()).hasSize(2);
    }

    @Test
    void returnsGlobalTrackWhenAggregateLevelIsHigher() {
        RiskEventMapper eventMapper = mock(RiskEventMapper.class);
        RiskRuleMapper ruleMapper = mock(RiskRuleMapper.class);
        when(ruleMapper.selectOne(any())).thenReturn(globalRule());
        when(eventMapper.eventRuleScores(7L, "127.0.0.1", "000000")).thenReturn(List.of(
            new RiskRuleScoreVo("LOGIN_FAIL", 10, 0),
            new RiskRuleScoreVo("UPLOAD_ABUSE", 10, 0)
        ));
        when(eventMapper.sumUserRiskScore(eq(7L), any(), any())).thenReturn(45);
        when(eventMapper.sumIpRiskScore(eq("127.0.0.1"), any(), any())).thenReturn(30);

        RiskScoreService.RiskAssessment assessment = service(eventMapper, ruleMapper)
            .assess(7L, "127.0.0.1");

        assertThat(assessment.level()).isEqualTo(2);
        assertThat(assessment.sourceTrack()).isEqualTo("GLOBAL");
        assertThat(assessment.totalScore()).isEqualTo(45);
    }

    @Test
    void doesNotUseAnEventRuleAsGlobalFallback() {
        RiskEventMapper eventMapper = mock(RiskEventMapper.class);
        RiskRuleMapper ruleMapper = mock(RiskRuleMapper.class);
        when(ruleMapper.selectOne(any())).thenReturn(null);
        when(eventMapper.eventRuleScores(7L, null, "000000")).thenReturn(List.of(
            new RiskRuleScoreVo("REPORTED", 40, 2)
        ));

        RiskScoreService.RiskAssessment assessment = service(eventMapper, ruleMapper)
            .assess(7L, null);

        assertThat(assessment.level()).isEqualTo(2);
        assertThat(assessment.globalLevel()).isZero();
        assertThat(assessment.sourceTrack()).isEqualTo("RULE");
    }

    private RiskScoreService service(RiskEventMapper eventMapper, RiskRuleMapper ruleMapper) {
        return new RiskScoreService(eventMapper, ruleMapper, new HybridRiskPolicy());
    }

    private RiskRule globalRule() {
        RiskRule rule = new RiskRule();
        rule.setRuleScope("GLOBAL");
        rule.setWindowMinutes(60);
        rule.setThresholdL1(20);
        rule.setThresholdL2(40);
        rule.setThresholdL3(60);
        rule.setThresholdL4(80);
        return rule;
    }
}
