package com.smartdock.profile.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ${} 内嵌 SQL 的 props 键白名单测试(批11 Q7 收尾):
 * 白名单外的键(注入尝试/特殊字符/超长)必须整条拒绝, 不能带着进 SQL。
 */
@Tag("local")
class DimensionSafeKeyTest {

    @Test
    void legalKeysPassThrough() {
        assertThat(DimensionEvaluator.safeKey("orderAmount")).isEqualTo("orderAmount");
        assertThat(DimensionEvaluator.safeKey("a")).isEqualTo("a");
        assertThat(DimensionEvaluator.safeKey("A_b_9")).isEqualTo("A_b_9");
        assertThat(DimensionEvaluator.safeKey("x".repeat(63))).isEqualTo("x".repeat(63));
    }

    @Test
    void injectionAttemptsRejected() {
        assertThatThrownBy(() -> DimensionEvaluator.safeKey("a' OR '1'='1"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DimensionEvaluator.safeKey("a; DROP TABLE profile_event--"))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DimensionEvaluator.safeKey("a-b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DimensionEvaluator.safeKey("a b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DimensionEvaluator.safeKey("1abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DimensionEvaluator.safeKey("_abc")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DimensionEvaluator.safeKey("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DimensionEvaluator.safeKey(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void overlongKeyRejected() {
        assertThatThrownBy(() -> DimensionEvaluator.safeKey("x".repeat(64)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
