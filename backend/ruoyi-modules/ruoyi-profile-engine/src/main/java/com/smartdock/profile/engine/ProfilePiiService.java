package com.smartdock.profile.engine;

import cn.dev33.satoken.stp.StpUtil;
import com.smartdock.profile.domain.ProfilePiiField;
import com.smartdock.profile.mapper.ProfilePiiFieldMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * PII 出参把关(批11 Q1-A): 清单来自 profile_pii_field(60s 缓存), 键名命中即打码;
 * 三类字段权限——看分数 profile:pii:score / 看联系方式 profile:pii:contact /
 * 看行为明细 profile:pii:behavior, 有 contact 权限才给联系方式类原文。
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfilePiiService {

    public static final String PERM_SCORE = "profile:pii:score";
    public static final String PERM_CONTACT = "profile:pii:contact";
    public static final String PERM_BEHAVIOR = "profile:pii:behavior";

    private static final long CACHE_MS = 60_000L;

    private final ProfilePiiFieldMapper piiMapper;

    /** 清单读失败时的兜底键集(批11 回修终审 P1: fail-closed——DB 抖动期间按已知高危键全打码) */
    private static final Map<String, String> FALLBACK_RULES = Map.of(
        "phone", "FULL", "mobile", "FULL", "email", "FULL", "idcard", "FULL", "id_card", "FULL",
        "searchterm", "FULL", "keyword", "FULL", "ip", "FULL");

    private volatile Map<String, String> cachedRules;
    private volatile long cachedAt;

    /** 键名->规则的启用清单(缓存); 匹配键名忽略大小写与 -_ 差异。 */
    public Map<String, String> enabledRules() {
        Map<String, String> rules = cachedRules;
        long at = cachedAt;
        long now = System.currentTimeMillis();
        if (rules == null || now - at > CACHE_MS) {
            Map<String, String> fresh = new LinkedHashMap<>();
            try {
                List<ProfilePiiField> rows = piiMapper.selectEnabled();
                for (ProfilePiiField row : rows) {
                    fresh.put(ProfilePiiMasker.normalizeKey(row.getFieldKey()), row.getMaskRule());
                }
            } catch (Exception e) {
                // 批11 回修(终审 P1): fail-closed——合规批次一次 DB 抖动不能等于 60 秒明文,
                // 读不到清单就按内置兜底键全打码, 并留错误日志
                log.error("[Profile] pii field list load failed, fallback to builtin full-mask rules", e);
                fresh = new LinkedHashMap<>(FALLBACK_RULES);
            }
            cachedRules = fresh;
            cachedAt = now;
            rules = fresh;
        }
        return rules;
    }

    // ------------------------------------------------------------ 清单管理(合规页)

    /** 全量清单(含停用行)。 */
    public java.util.List<ProfilePiiField> listFields() {
        return piiMapper.selectAll();
    }

    /** 新增或覆盖一行(键名为主键), 写完立刻失效缓存。 */
    public ProfilePiiField saveField(ProfilePiiField field) {
        if (field == null || field.getFieldKey() == null || field.getFieldKey().isBlank()) {
            throw new org.dromara.common.core.exception.ServiceException("fieldKey 不能为空");
        }
        String rule = field.getMaskRule() == null ? "" : field.getMaskRule().trim().toUpperCase();
        if (!java.util.Set.of("MOBILE", "EMAIL", "IP", "TERM", "EDGE", "FULL").contains(rule)) {
            throw new org.dromara.common.core.exception.ServiceException(
                "maskRule 必须是 MOBILE/EMAIL/IP/TERM/EDGE/FULL 之一");
        }
        field.setFieldKey(field.getFieldKey().trim());
        field.setMaskRule(rule);
        field.setEnabled("1".equals(field.getEnabled()) ? "1" : "0");
        piiMapper.upsert(field);
        cachedRules = null;
        return field;
    }

    /** 删除一行, 写完立刻失效缓存。 */
    public void deleteField(String fieldKey) {
        piiMapper.deleteByKey(fieldKey);
        cachedRules = null;
    }

    /** 对出参 map 打码; 有 contact 权限(或超管)时联系方式类键返回原文。 */
    public Map<String, Object> maskOut(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return map;
        }
        if (canViewContact()) {
            return map;
        }
        return ProfilePiiMasker.maskMap(map, enabledRules());
    }

    /** 打码 JSON 字符串(事件 props/context/主体 properties 都是 JSON 文本出参); 解析失败原样返回。 */
    public String maskJsonOut(String json) {
        if (json == null || json.isBlank() || canViewContact()) {
            return json;
        }
        try {
            Map<String, Object> m = org.dromara.common.json.utils.JsonUtils.parseMap(json);
            return m == null || m.isEmpty() ? json
                : org.dromara.common.json.utils.JsonUtils.toJsonString(ProfilePiiMasker.maskMap(m, enabledRules()));
        } catch (Exception e) {
            return json;
        }
    }

    /** 打码单值(如会话行的 search_term 列); 键不在清单或有权看原文时原样返回。 */
    public String maskValueForKey(String key, String value) {
        if (value == null || value.isEmpty() || canViewContact()) {
            return value;
        }
        String rule = enabledRules().get(ProfilePiiMasker.normalizeKey(key));
        return rule == null ? value : ProfilePiiMasker.mask(rule, value);
    }

    public boolean canViewScore() {
        return hasPerm(PERM_SCORE);
    }

    public boolean canViewContact() {
        return hasPerm(PERM_CONTACT);
    }

    public boolean canViewBehavior() {
        return hasPerm(PERM_BEHAVIOR);
    }

    private boolean hasPerm(String perm) {
        try {
            return StpUtil.hasPermission(perm);
        } catch (Exception e) {
            // 非登录上下文(调度线程): 按"无权"处理, 保守打码/隐藏
            return false;
        }
    }
}
