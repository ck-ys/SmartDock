package com.smartdock.risk.service;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("dev")
class RiskActionPolicyUnitTest {

    private final RiskActionPolicy policy = new RiskActionPolicy();

    @Test
    void mapsOnlyL3AndL4ToReversibleActions() {
        assertThat(policy.decision(2)).isNull();
        assertThat(policy.decision(3).actionType()).isEqualTo("ENHANCED_VERIFICATION");
        assertThat(policy.decision(3).duration()).isEqualTo(Duration.ofMinutes(30));
        assertThat(policy.decision(4).actionType()).isEqualTo("TEMP_FREEZE");
        assertThat(policy.decision(4).duration()).isEqualTo(Duration.ofMinutes(60));
    }

    @Test
    void requiresAuditableReasonsAndKnownReviewConclusions() {
        assertThatThrownBy(() -> policy.requireReason(" ")).isInstanceOf(ServiceException.class);
        assertThatThrownBy(() -> policy.requireConclusion("SKIP")).isInstanceOf(ServiceException.class);
        assertThat(policy.requireReason("人工核验通过 ")).isEqualTo("人工核验通过");
        assertThat(policy.requireConclusion("dismissed")).isEqualTo("DISMISSED");
    }
}
