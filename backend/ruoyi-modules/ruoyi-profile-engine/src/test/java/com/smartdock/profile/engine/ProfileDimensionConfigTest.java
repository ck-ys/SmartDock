package com.smartdock.profile.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 维度配置结构校验测试。
 *
 * @author smartdock
 */
@Tag("local")
class ProfileDimensionConfigTest {

    @Test
    void acceptsCountConfig() {
        Map<String, Object> cfg = Map.of(
            "metric", Map.of(
                "operator", "COUNT",
                "target", Map.of("eventType", "data.audit_rejected")),
            "window", Map.of("type", "rolling", "days", 30),
            "score", Map.of("type", "linear", "lo", 0, "hi", 10, "direction", "lower"),
            "missing", "neutral");
        assertThat(ProfileDimensionConfig.validate("EVENT_AGG", cfg, BigDecimal.valueOf(100))).isEmpty();
    }

    @Test
    void acceptsRateAndBucketConfig() {
        Map<String, Object> cfg = Map.of(
            "metric", Map.of(
                "operator", "RATE",
                "numerator", Map.of("eventType", "data.audit_passed"),
                "denominator", Map.of("eventType", "data.submitted")),
            "score", Map.of("type", "bucket", "buckets", List.of(
                Map.of("lte", 0.5, "score", 40),
                Map.of("lte", 0.8, "score", 70),
                Map.of("score", 100))),
            "missing", "skip");
        assertThat(ProfileDimensionConfig.validate("EVENT_AGG", cfg, BigDecimal.valueOf(100))).isEmpty();
    }

    @Test
    void rejectsUnknownOperator() {
        Map<String, Object> cfg = Map.of(
            "metric", Map.of("operator", "MEDIAN"),
            "score", Map.of("type", "linear", "lo", 0, "hi", 1));
        assertThat(ProfileDimensionConfig.validate("EVENT_AGG", cfg, BigDecimal.valueOf(100)))
            .anyMatch(e -> e.contains("不支持的算子"));
    }

    @Test
    void rejectsMissingTargetForCount() {
        Map<String, Object> cfg = Map.of(
            "metric", Map.of("operator", "COUNT"),
            "score", Map.of("type", "linear", "lo", 0, "hi", 1));
        assertThat(ProfileDimensionConfig.validate("EVENT_AGG", cfg, BigDecimal.valueOf(100)))
            .anyMatch(e -> e.contains("metric.target 不能为空"));
    }

    @Test
    void rejectsMissingPropForSum() {
        Map<String, Object> cfg = Map.of(
            "metric", Map.of("operator", "SUM", "target", Map.of("eventType", "order.paid")),
            "score", Map.of("type", "linear", "lo", 0, "hi", 100));
        assertThat(ProfileDimensionConfig.validate("EVENT_AGG", cfg, BigDecimal.valueOf(100)))
            .anyMatch(e -> e.contains("SUM 必须配置 prop"));
    }

    @Test
    void rejectsRollingWindowWithoutDays() {
        Map<String, Object> cfg = Map.of(
            "metric", Map.of("operator", "COUNT", "target", Map.of("eventType", "user.login")),
            "window", Map.of("type", "rolling"),
            "score", Map.of("type", "linear", "lo", 0, "hi", 1));
        assertThat(ProfileDimensionConfig.validate("EVENT_AGG", cfg, BigDecimal.valueOf(100)))
            .anyMatch(e -> e.contains("days > 0"));
    }

    @Test
    void rejectsInvalidScoreMaxAndEmptyBuckets() {
        Map<String, Object> cfg = Map.of(
            "metric", Map.of("operator", "COUNT", "target", Map.of("eventType", "user.login")),
            "score", Map.of("type", "bucket", "buckets", List.of()));
        assertThat(ProfileDimensionConfig.validate("EVENT_AGG", cfg, BigDecimal.ZERO))
            .anyMatch(e -> e.contains("scoreMax"))
            .anyMatch(e -> e.contains("非空 buckets"));
    }

    @Test
    void strategyOnlyValidatesBeanType() {
        assertThat(ProfileDimensionConfig.validate("STRATEGY",
            Map.of("strategyBean", "MyStrategy"), BigDecimal.valueOf(100))).isEmpty();
        assertThat(ProfileDimensionConfig.validate("STRATEGY",
            Map.of("strategyBean", 123), BigDecimal.valueOf(100)))
            .anyMatch(e -> e.contains("strategyBean 必须是字符串"));
    }
}
