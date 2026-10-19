package com.smartdock.profile.model;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("local")
class ProfileSegmentRuleTest {

    private ProfileSegmentRule.Condition condition(String field, String op, Object value) {
        ProfileSegmentRule.Condition c = new ProfileSegmentRule.Condition();
        c.setField(field);
        c.setOp(op);
        c.setValue(value);
        return c;
    }

    private Map<String, Object> candidate() {
        return Map.of(
            "subjectId", "u1",
            "subjectType", "user",
            "displayName", "张三",
            "totalScore", 82,
            "eventCount", 12,
            "lastEventDays", 3,
            "properties", Map.of("level", "vip"),
            "dimensions", Map.of(
                "completion", Map.of("normalizedScore", 90, "score", 90, "tags", Map.of("missing", false)),
                "negative", Map.of("normalizedScore", 50, "score", 50, "skipped", false)
            )
        );
    }

    @Test
    void allMatchRequiresEveryCondition() {
        ProfileSegmentRule rule = new ProfileSegmentRule();
        rule.setConditions(List.of(
            condition("totalScore", "GTE", 75),
            condition("dimension.completion", "GTE", 80)
        ));
        assertThat(ProfileSegmentRule.validate(rule)).isEmpty();
        assertThat(rule.matches(candidate())).isTrue();

        rule.getConditions().get(1).setValue(95);
        assertThat(rule.matches(candidate())).isFalse();
    }

    @Test
    void anyMatchShortCircuits() {
        ProfileSegmentRule rule = new ProfileSegmentRule();
        rule.setMatch("ANY");
        rule.setConditions(List.of(
            condition("totalScore", "LT", 60),
            condition("property.level", "EQ", "vip")
        ));
        assertThat(rule.matches(candidate())).isTrue();
    }

    @Test
    void inAndContainsWork() {
        ProfileSegmentRule rule = new ProfileSegmentRule();
        rule.setConditions(List.of(
            condition("subjectId", "IN", "u1,u2"),
            condition("displayName", "CONTAINS", "张")
        ));
        assertThat(rule.matches(candidate())).isTrue();
    }

    @Test
    void existsHandlesMissingProperties() {
        ProfileSegmentRule rule = new ProfileSegmentRule();
        rule.setConditions(List.of(condition("property.missingKey", "NOT_EXISTS", null)));
        assertThat(ProfileSegmentRule.validate(rule)).isEmpty();
        assertThat(rule.matches(candidate())).isTrue();
    }

    @Test
    void tagFieldResolvesNestedTag() {
        ProfileSegmentRule rule = new ProfileSegmentRule();
        rule.setConditions(List.of(condition("tag.completion.missing", "EQ", false)));
        assertThat(rule.matches(candidate())).isTrue();
    }

    @Test
    void skippedDimensionTreatedAsMissing() {
        Map<String, Object> c = new java.util.HashMap<>(candidate());
        c.put("dimensions", Map.of("negative", Map.of("skipped", true)));
        ProfileSegmentRule rule = new ProfileSegmentRule();
        rule.setConditions(List.of(condition("dimension.negative", "NOT_EXISTS", null)));
        assertThat(rule.matches(c)).isTrue();
    }

    @Test
    void rejectsUnknownFieldAndOp() {
        ProfileSegmentRule rule = new ProfileSegmentRule();
        rule.setConditions(List.of(
            condition("rawSql", "LIKE", "%x%"),
            condition("totalScore", "BETWEEN", "1,2")
        ));
        assertThat(ProfileSegmentRule.validate(rule))
            .anyMatch(e -> e.contains("field"))
            .anyMatch(e -> e.contains("op"));
    }

    @Test
    void rejectsEmptyConditions() {
        ProfileSegmentRule rule = new ProfileSegmentRule();
        assertThat(ProfileSegmentRule.validate(rule)).anyMatch(e -> e.contains("至少需要一个条件"));
    }
}
