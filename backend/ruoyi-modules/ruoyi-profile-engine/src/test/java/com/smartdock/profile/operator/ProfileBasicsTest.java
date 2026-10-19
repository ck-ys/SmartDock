package com.smartdock.profile.operator;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 缺失策略/窗口/uid 纯逻辑单测(V1)。
 *
 * @author smartdock
 */
@Tag("local")
class ProfileBasicsTest {

    @Test
    void missingPolicyDefaultsNeutralAndParsesCaseInsensitive() {
        assertThat(MissingPolicy.of(null)).isEqualTo(MissingPolicy.NEUTRAL);
        assertThat(MissingPolicy.of("")).isEqualTo(MissingPolicy.NEUTRAL);
        assertThat(MissingPolicy.of("  ")).isEqualTo(MissingPolicy.NEUTRAL);
        assertThat(MissingPolicy.of("unknown-value")).isEqualTo(MissingPolicy.NEUTRAL);
        assertThat(MissingPolicy.of("zero")).isEqualTo(MissingPolicy.ZERO);
        assertThat(MissingPolicy.of(" SKIP ")).isEqualTo(MissingPolicy.SKIP);
        assertThat(MissingPolicy.of("Neutral")).isEqualTo(MissingPolicy.NEUTRAL);
    }

    @Test
    void windowSpecAllWhenMissingOrBad() {
        assertThat(new WindowSpec().isRolling()).isFalse();
        WindowSpec w = new WindowSpec();
        w.setType("rolling");
        w.setDays(30);
        assertThat(w.isRolling()).isTrue();
        assertThat(w.describe()).isEqualTo("rolling-30d");
        w.setDays(0);
        assertThat(w.isRolling()).isFalse(); // 0 天视为无效
        w.setDays(-5);
        assertThat(w.isRolling()).isFalse();
        w.setType("all");
        w.setDays(30);
        assertThat(w.isRolling()).isFalse();
        assertThat(w.describe()).isEqualTo("all-time");
    }

    @Test
    void uidBuildsCanonicalForms() {
        assertThat(com.smartdock.profile.ProfileUids.uid("submission", "submit", 12345L))
            .isEqualTo("submission:submit:12345");
        assertThat(com.smartdock.profile.ProfileUids.uid("submission", "submit", 12345L, "v2"))
            .isEqualTo("submission:submit:12345:v2");
    }
}
