package com.smartdock.risk;

import com.smartdock.risk.config.RolloutProperties;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("dev")
class RolloutPropertiesUnitTest {

    @Test
    void defaultsPreserveCurrentProductionBehavior() {
        RolloutProperties properties = new RolloutProperties();

        assertThat(properties.isEventCollectionEnabled()).isTrue();
        assertThat(properties.isChallengeEnabled(1)).isTrue();
        assertThat(properties.isChallengeEnabled(2)).isTrue();
        assertThat(properties.isChallengeEnabled(3)).isTrue();
        assertThat(properties.isChallengeEnabled(4)).isTrue();
        assertThat(properties.isProfileScoringEnabled()).isTrue();
        assertThat(properties.isAiAdjustmentEnabled()).isTrue();
        assertThat(properties.isRecalculationWorkersEnabled()).isTrue();
        assertThat(properties.isStrictPublishedSnapshotsEnabled()).isTrue();
    }

    @Test
    void l2AndL34CanBeRolledBackIndependentlyWithoutDisablingL1() {
        RolloutProperties properties = new RolloutProperties();
        properties.setL2EnforcementEnabled(false);
        properties.setL34EnforcementEnabled(false);

        assertThat(properties.isChallengeEnabled(1)).isTrue();
        assertThat(properties.isChallengeEnabled(2)).isFalse();
        assertThat(properties.isChallengeEnabled(3)).isFalse();
        assertThat(properties.isChallengeEnabled(4)).isFalse();
    }
}
