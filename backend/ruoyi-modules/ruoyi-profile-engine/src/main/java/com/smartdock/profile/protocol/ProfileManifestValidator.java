package com.smartdock.profile.protocol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 画像协议 manifest 的轻量校验器。
 *
 * <p>只校验“能不能安全装载”的结构约束, 不替代完整 JSON Schema:
 * 版本/类型、主体与事件必填、命名唯一、事件引用的主体已声明、可选段落类型正确。</p>
 *
 * @author smartdock
 */
public final class ProfileManifestValidator {

    public static final String API_VERSION = "profile.protocol/v1";
    public static final String KIND = "ProfileManifest";

    private ProfileManifestValidator() {
    }

    public static List<String> validate(Map<String, Object> manifest) {
        List<String> errors = new ArrayList<>();
        if (manifest == null || manifest.isEmpty()) {
            errors.add("manifest is required");
            return errors;
        }
        if (!API_VERSION.equals(text(manifest.get("apiVersion")))) {
            errors.add("apiVersion must be " + API_VERSION);
        }
        if (!KIND.equals(text(manifest.get("kind")))) {
            errors.add("kind must be " + KIND);
        }

        Map<String, Object> metadata = asMap(manifest.get("metadata"));
        if (metadata == null) {
            errors.add("metadata is required");
        } else {
            if (text(metadata.get("namespace")).isBlank()) {
                errors.add("metadata.namespace is required");
            }
            if (text(metadata.get("version")).isBlank()) {
                errors.add("metadata.version is required");
            }
        }

        Set<String> subjectNames = new LinkedHashSet<>();
        List<Map<String, Object>> subjectTypes = namedList(manifest.get("subjectTypes"), "subjectTypes", errors);
        if (subjectTypes.isEmpty()) {
            errors.add("subjectTypes must not be empty");
        }
        for (int i = 0; i < subjectTypes.size(); i++) {
            String name = text(subjectTypes.get(i).get("name"));
            if (name.isBlank()) {
                errors.add("subjectTypes[" + i + "].name is required");
            } else if (!subjectNames.add(name)) {
                errors.add("duplicate subjectType: " + name);
            }
        }

        List<Map<String, Object>> events = namedList(manifest.get("events"), "events", errors);
        if (events.isEmpty()) {
            errors.add("events must not be empty");
        }
        Set<String> eventNames = new LinkedHashSet<>();
        for (int i = 0; i < events.size(); i++) {
            Map<String, Object> event = events.get(i);
            String name = text(event.get("name"));
            if (name.isBlank()) {
                errors.add("events[" + i + "].name is required");
            } else if (!eventNames.add(name)) {
                errors.add("duplicate event: " + name);
            }
            List<String> refs = stringList(event.get("subjectTypes"));
            if (refs.isEmpty()) {
                errors.add("events[" + i + "].subjectTypes must not be empty");
            }
            if (!subjectNames.isEmpty()) {
                for (String ref : refs) {
                    if (!subjectNames.contains(ref)) {
                        errors.add("events[" + i + "] references unknown subjectType: " + ref);
                    }
                }
            }
        }

        validateOptionalNamedList(manifest, "dimensions", errors);
        validateOptionalNamedList(manifest, "metrics", errors);
        validateOptionalNamedList(manifest, "rules", errors);
        validateOptionalNamedList(manifest, "scoreModels", errors);
        validateOptionalNamedList(manifest, "segments", errors);
        if (manifest.get("identity") != null && asMap(manifest.get("identity")) == null) {
            errors.add("identity must be an object");
        }
        return errors;
    }

    private static void validateOptionalNamedList(Map<String, Object> manifest, String key, List<String> errors) {
        Object raw = manifest.get(key);
        if (raw == null) {
            return;
        }
        if (!(raw instanceof List<?> list)) {
            errors.add(key + " must be an array");
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (!(item instanceof Map<?, ?> map)) {
                errors.add(key + "[" + i + "] must be an object");
                continue;
            }
            String name = text(map.get("name"));
            if (name.isBlank()) {
                errors.add(key + "[" + i + "].name is required");
            } else if (!names.add(name)) {
                errors.add("duplicate " + key + ": " + name);
            }
        }
    }

    private static List<Map<String, Object>> namedList(Object raw, String key, List<String> errors) {
        if (raw == null) {
            return List.of();
        }
        if (!(raw instanceof List<?> list)) {
            errors.add(key + " must be an array");
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                map.forEach((k, v) -> copy.put(String.valueOf(k), v));
                out.add(copy);
            } else {
                errors.add(key + "[" + i + "] must be an object");
            }
        }
        return out;
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object raw) {
        return raw instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private static String text(Object raw) {
        return raw == null ? "" : String.valueOf(raw).trim();
    }
}
