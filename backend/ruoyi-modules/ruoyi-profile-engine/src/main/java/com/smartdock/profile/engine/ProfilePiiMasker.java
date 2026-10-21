package com.smartdock.profile.engine;

import org.dromara.common.core.utils.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PII 值脱敏器(批11 Q1-A): 纯函数, 规则与键清单解耦——清单在 profile_pii_field 表。
 *
 * <p>规则: MOBILE=手机 138****5678; EMAIL=邮箱 a***@x.com; IP=192.168.*.*;
 * TERM=搜索词等自由文本整体打码; EDGE=保留首尾各一字符; FULL=全打码。</p>
 *
 * @author smartdock
 */
public final class ProfilePiiMasker {

    private static final Pattern MOBILE_RE = Pattern.compile("^1\\d{10}$");
    private static final Pattern EMAIL_RE = Pattern.compile("^(.?)[^@]*(@.+)$");

    private ProfilePiiMasker() {
    }

    /** 按规则打码单个值; 不认识的规则按 FULL 处理, null/空原样返回。 */
    public static String mask(String rule, String value) {
        if (StringUtils.isBlank(value)) {
            return value;
        }
        String v = value.trim();
        return switch (rule == null ? "FULL" : rule) {
            case "MOBILE" -> maskMobile(v);
            case "EMAIL" -> maskEmail(v);
            case "IP" -> maskIp(v);
            case "EDGE" -> maskEdge(v);
            case "TERM" -> "***";
            default -> "***";
        };
    }

    /** 对 map 的值按键清单打码(拷贝不改原 map); 键名忽略大小写与中划线/下划线差异。 */
    public static Map<String, Object> maskMap(Map<String, Object> map, Map<String, String> keyRules) {
        if (map == null || map.isEmpty() || keyRules == null || keyRules.isEmpty()) {
            return map;
        }
        Map<String, String> normalized = new LinkedHashMap<>();
        keyRules.forEach((k, r) -> normalized.put(normalizeKey(k), r));
        Map<String, Object> out = new LinkedHashMap<>();
        map.forEach((k, v) -> {
            String rule = normalized.get(normalizeKey(k));
            out.put(k, rule == null || !(v instanceof String s) ? v : mask(rule, s));
        });
        return out;
    }

    static String normalizeKey(String key) {
        return key == null ? "" : key.replace("-", "").replace("_", "").trim().toLowerCase();
    }

    private static String maskMobile(String v) {
        Matcher m = MOBILE_RE.matcher(v);
        return m.matches() ? v.substring(0, 3) + "****" + v.substring(7) : maskEdge(v);
    }

    private static String maskEmail(String v) {
        Matcher m = EMAIL_RE.matcher(v);
        if (m.matches()) {
            String head = m.group(1);
            return (head.isEmpty() ? "*" : head) + "***" + m.group(2);
        }
        return maskEdge(v);
    }

    private static String maskIp(String v) {
        String[] parts = v.split("\\.");
        if (parts.length == 4) {
            return parts[0] + "." + parts[1] + ".*.*";
        }
        return "***";
    }

    private static String maskEdge(String v) {
        if (v.length() <= 2) {
            return "***";
        }
        return v.charAt(0) + "***" + v.charAt(v.length() - 1);
    }
}
