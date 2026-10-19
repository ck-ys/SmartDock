package com.smartdock.profile.protocol;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.smartdock.profile.mapper.ProfileSchemaRegistryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 事件 schema 轻校验与注册。
 *
 * <p>校验范围: 根类型 object、required 必填、properties 的 string/number/integer/
 * boolean/object/array 基础类型。未注册 schema 的事件直接放行, 保证旧接入零破坏。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileSchemaService {

    private final ProfileSchemaRegistryMapper schemaMapper;

    public List<ProfileSchemaRegistry> list(String source) {
        return schemaMapper.selectBySource(currentTenant(), source);
    }

    public ProfileSchemaRegistry register(String source, ProfileSchemaBo bo, Long userId) {
        if (StringUtils.isBlank(source)) {
            throw new ServiceException("schema source 不能为空");
        }
        if (source.length() > 32) {
            throw new ServiceException("schema source 长度不能超过 32");
        }
        if (StringUtils.isBlank(bo.getEventName())) {
            throw new ServiceException("eventName 不能为空");
        }
        if (bo.getSchema() == null || bo.getSchema().isEmpty()) {
            throw new ServiceException("schema 不能为空");
        }
        Object rootType = bo.getSchema().get("type");
        if (rootType != null && !"object".equals(String.valueOf(rootType))) {
            throw new ServiceException("schema.type 必须是 object");
        }
        String version = StringUtils.isBlank(bo.getSchemaVersion()) ? "1-0-0" : bo.getSchemaVersion().trim();
        if (bo.getEventName().trim().length() > 64) {
            throw new ServiceException("eventName 长度不能超过 64");
        }
        if (version.length() > 32) {
            throw new ServiceException("schemaVersion 长度不能超过 32");
        }
        ProfileSchemaRegistry entity = new ProfileSchemaRegistry();
        entity.setSchemaId(IdWorker.getId());
        entity.setTenantId(currentTenant());
        entity.setSource(source);
        entity.setEventName(bo.getEventName().trim());
        entity.setSchemaVersion(version);
        entity.setSchemaJson(JsonUtils.toJsonString(bo.getSchema()));
        entity.setStatus("ACTIVE");
        entity.setCreateBy(userId);
        schemaMapper.insertJsonb(entity);
        ProfileSchemaRegistry saved = schemaMapper.selectActive(entity.getTenantId(), source,
            entity.getEventName(), version);
        return saved != null ? saved : entity;
    }

    /**
     * 校验事件载荷; 无 schema 或 schema 不可解析时放行(返回空错误列表)。
     */
    public List<String> validate(String tenantId, String source, String eventName, String schemaVersion,
                                 Map<String, Object> props, Map<String, Object> context, Map<String, Object> traits) {
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(source) || StringUtils.isBlank(eventName)) {
            return List.of();
        }
        String version = StringUtils.isBlank(schemaVersion) ? "1-0-0" : schemaVersion;
        ProfileSchemaRegistry registry;
        try {
            registry = schemaMapper.selectActive(tenantId, source, eventName, version);
        } catch (Exception e) {
            log.warn("[Profile] schema lookup failed, accept event {}/{}: {}", eventName, version, e.getMessage());
            return List.of();
        }
        if (registry == null || StringUtils.isBlank(registry.getSchemaJson())) {
            return List.of();
        }
        Map<String, Object> schema;
        try {
            schema = JsonUtils.parseMap(registry.getSchemaJson());
        } catch (Exception e) {
            log.warn("[Profile] schema parse failed, accept event {}/{}: {}", eventName, version, e.getMessage());
            return List.of();
        }
        if (schema == null || schema.isEmpty()) {
            return List.of();
        }
        List<String> errors = new ArrayList<>();
        checkRequired(schema, "required", props, "props", errors);
        checkRequired(schema, "requiredContext", context, "context", errors);
        checkRequired(schema, "requiredTraits", traits, "traits", errors);
        checkTypes(schema, "properties", props, "props", errors);
        checkTypes(schema, "contextProperties", context, "context", errors);
        checkTypes(schema, "traitsProperties", traits, "traits", errors);
        return errors;
    }

    private void checkRequired(Map<String, Object> schema, String key, Map<String, Object> values,
                               String label, List<String> errors) {
        Object raw = schema.get(key);
        if (!(raw instanceof List<?> list)) {
            return;
        }
        Map<String, Object> actual = values == null ? Map.of() : values;
        for (Object item : list) {
            if (item == null) {
                continue;
            }
            String field = String.valueOf(item);
            if (!actual.containsKey(field) || actual.get(field) == null) {
                errors.add(label + "." + field + " is required");
            }
        }
    }

    private void checkTypes(Map<String, Object> schema, String key, Map<String, Object> values,
                            String label, List<String> errors) {
        Object raw = schema.get(key);
        if (!(raw instanceof Map<?, ?> definition)) {
            return;
        }
        Map<String, Object> actual = values == null ? Map.of() : values;
        for (Map.Entry<?, ?> entry : definition.entrySet()) {
            String field = String.valueOf(entry.getKey());
            Object value = actual.get(field);
            if (value == null || !(entry.getValue() instanceof Map<?, ?> spec)) {
                continue;
            }
            String type = spec.get("type") == null ? null : String.valueOf(spec.get("type"));
            if (type != null && !typeMatches(type, value)) {
                errors.add(label + "." + field + " must be " + type);
            }
        }
    }

    private boolean typeMatches(String type, Object value) {
        return switch (type) {
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number n
                && n.doubleValue() == Math.rint(n.doubleValue());
            case "boolean" -> value instanceof Boolean;
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof Collection<?>;
            case "null" -> value == null;
            default -> true;
        };
    }

    private String currentTenant() {
        try {
            String t = TenantHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 无租户上下文
        }
        try {
            String t = LoginHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 非登录上下文
        }
        return "000000";
    }
}
