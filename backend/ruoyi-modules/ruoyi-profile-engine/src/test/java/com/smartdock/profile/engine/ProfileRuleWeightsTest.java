package com.smartdock.profile.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 权重规则纯函数测试。
 *
 * @author smartdock
 */
@Tag("local")
class ProfileRuleWeightsTest {

    private static final List<String> DIMS = List.of("completion", "response", "quality", "negative");

    @Test
    void acceptsValidWeights() {
        Map<String, Double> weights = Map.of(
            "completion", 0.35, "response", 0.25, "quality", 0.25, "negative", 0.15);
        assertThat(ProfileRuleWeights.validate(weights, DIMS)).isEmpty();
    }

    @Test
    void rejectsMissingAndUnknownDimensions() {
        Map<String, Double> weights = Map.of("completion", 1.0, "unknown", 0.0);
        assertThat(ProfileRuleWeights.validate(weights, DIMS))
            .anyMatch(e -> e.contains("缺少维度权重"))
            .anyMatch(e -> e.contains("未启用维度"));
    }

    @Test
    void rejectsSumNotOne() {
        Map<String, Double> weights = Map.of(
            "completion", 0.35, "response", 0.25, "quality", 0.25, "negative", 0.25);
        assertThat(ProfileRuleWeights.validate(weights, DIMS))
            .anyMatch(e -> e.contains("权重合计必须为 1"));
    }

    @Test
    void rejectsOutOfRangeValue() {
        Map<String, Double> weights = Map.of(
            "completion", 1.35, "response", -0.1, "quality", 0.0, "negative", -0.25);
        assertThat(ProfileRuleWeights.validate(weights, DIMS))
            .anyMatch(e -> e.contains("0~1"));
    }

    @Test
    void balancedSumsToOne() {
        Map<String, Double> weights = ProfileRuleWeights.balanced(DIMS);
        assertThat(weights).hasSize(4);
        assertThat(ProfileRuleWeights.sum(weights)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.0001));
    }

    @Test
    void fillMissingKeepsExistingShareAndFillsRest() {
        Map<String, Double> weights = ProfileRuleWeights.fillMissing(
            Map.of("completion", 0.5), DIMS);
        assertThat(weights).containsEntry("completion", 0.5);
        assertThat(weights.keySet()).containsExactlyInAnyOrderElementsOf(DIMS);
        assertThat(ProfileRuleWeights.sum(weights)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void emptyEnabledDimensionsBlockPublish() {
        assertThat(ProfileRuleWeights.validate(Map.of(), List.of()))
            .anyMatch(e -> e.contains("没有启用的评分维度"));
    }
}
