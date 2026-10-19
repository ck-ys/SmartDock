package com.smartdock.risk;

import com.smartdock.risk.service.HybridRiskPolicy;
import com.smartdock.risk.service.HybridRiskPolicy.GlobalScore;
import com.smartdock.risk.service.HybridRiskPolicy.RuleScore;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("dev")
class HybridRiskPolicyUnitTest {

    private final HybridRiskPolicy policy = new HybridRiskPolicy();

    @Test
    void choosesHighestEventRuleLevel() {
        HybridRiskPolicy.HybridDecision decision = policy.resolve(
            List.of(
                new RuleScore("LOGIN_FAIL", 35, 2),
                new RuleScore("UPLOAD_ABUSE", 20, 1)
            ),
            new GlobalScore(15, 1)
        );

        assertThat(decision.level()).isEqualTo(2);
        assertThat(decision.ruleLevel()).isEqualTo(2);
        assertThat(decision.globalLevel()).isEqualTo(1);
        assertThat(decision.sourceTrack()).isEqualTo("RULE");
    }

    @Test
    void choosesGlobalLevelWhenItIsStrictlyHigher() {
        HybridRiskPolicy.HybridDecision decision = policy.resolve(
            List.of(
                new RuleScore("LOGIN_FAIL", 10, 0),
                new RuleScore("UPLOAD_ABUSE", 10, 0)
            ),
            new GlobalScore(45, 2)
        );

        assertThat(decision.level()).isEqualTo(2);
        assertThat(decision.ruleLevel()).isZero();
        assertThat(decision.globalLevel()).isEqualTo(2);
        assertThat(decision.sourceTrack()).isEqualTo("GLOBAL");
    }

    @Test
    void keepsRuleAsSourceTrackWhenLevelsTie() {
        HybridRiskPolicy.HybridDecision decision = policy.resolve(
            List.of(new RuleScore("REPORTED", 40, 2)),
            new GlobalScore(45, 2)
        );

        assertThat(decision.level()).isEqualTo(2);
        assertThat(decision.sourceTrack()).isEqualTo("RULE");
    }

    @Test
    void handlesNoEventRules() {
        HybridRiskPolicy.HybridDecision decision = policy.resolve(
            List.of(),
            new GlobalScore(0, 0)
        );

        assertThat(decision.level()).isZero();
        assertThat(decision.ruleLevel()).isZero();
        assertThat(decision.sourceTrack()).isEqualTo("RULE");
    }
}
