package com.smartdock.profile.protocol;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 画像协议 manifest 校验器的纯函数测试。
 *
 * @author smartdock
 */
@Tag("local")
class ProfileManifestValidatorTest {

    @Test
    void acceptsMinimalValidManifest() {
        assertThat(ProfileManifestValidator.validate(validManifest())).isEmpty();
    }

    @Test
    void rejectsMissingApiVersionAndKind() {
        Map<String, Object> manifest = validManifest();
        manifest.remove("apiVersion");
        manifest.remove("kind");

        assertThat(ProfileManifestValidator.validate(manifest))
            .anyMatch(error -> error.contains("apiVersion"))
            .anyMatch(error -> error.contains("kind"));
    }

    @Test
    void rejectsUnknownEventSubjectType() {
        Map<String, Object> manifest = validManifest();
        event(manifest, 0).put("subjectTypes", List.of("order"));

        assertThat(ProfileManifestValidator.validate(manifest))
            .anyMatch(error -> error.contains("unknown subjectType: order"));
    }

    @Test
    void rejectsDuplicateEventNames() {
        Map<String, Object> manifest = validManifest();
        List<Map<String, Object>> events = events(manifest);
        events.add(event("task.published"));

        assertThat(ProfileManifestValidator.validate(manifest))
            .anyMatch(error -> error.contains("duplicate event: task.published"));
    }

    @Test
    void rejectsNonArrayDimensions() {
        Map<String, Object> manifest = validManifest();
        manifest.put("dimensions", Map.of("name", "completion"));

        assertThat(ProfileManifestValidator.validate(manifest))
            .anyMatch(error -> error.contains("dimensions must be an array"));
    }

    @Test
    void rejectsUnnamedScoreModel() {
        Map<String, Object> manifest = validManifest();
        manifest.put("scoreModels", List.of(Map.of("type", "RFM")));

        assertThat(ProfileManifestValidator.validate(manifest))
            .anyMatch(error -> error.contains("scoreModels[0].name is required"));
    }

    @Test
    void acceptsExtendedSectionsForOtherDomains() {
        Map<String, Object> manifest = validManifest();
        manifest.put("subjectTypes", List.of(
            Map.of("name", "user"), Map.of("name", "order")));
        manifest.put("events", List.of(
            event("order.paid", "order"), event("user.login", "user")));
        manifest.put("metrics", List.of(Map.of("name", "gmv")));
        manifest.put("scoreModels", List.of(Map.of("name", "rfm", "type", "RFM")));
        manifest.put("segments", List.of(Map.of("name", "high_value")));
        manifest.put("identity", Map.of("strong", List.of("user_id")));

        assertThat(ProfileManifestValidator.validate(manifest)).isEmpty();
    }

    private Map<String, Object> validManifest() {
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("apiVersion", ProfileManifestValidator.API_VERSION);
        manifest.put("kind", ProfileManifestValidator.KIND);
        manifest.put("metadata", Map.of("namespace", "dcs", "version", "1.0.0"));
        manifest.put("subjectTypes", new ArrayList<>(List.of(Map.of("name", "user"))));
        manifest.put("events", new ArrayList<>(List.of(event("task.published"))));
        return manifest;
    }

    private Map<String, Object> event(String name, String... subjectTypes) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("name", name);
        event.put("subjectTypes", subjectTypes.length == 0
            ? new ArrayList<>(List.of("user")) : new ArrayList<>(List.of(subjectTypes)));
        return event;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> events(Map<String, Object> manifest) {
        return (List<Map<String, Object>>) manifest.get("events");
    }

    private Map<String, Object> event(Map<String, Object> manifest, int index) {
        return events(manifest).get(index);
    }
}
