package com.smartdock.profile.model;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 标签分群规则(纯函数): 主体快照字段 → 条件集合 → 命中/不命中。
 *
 * <p>字段白名单:
 * totalScore / eventCount / lastEventDays / subjectType / subjectId / displayName /
 * property.&lt;key&gt; / dimension.&lt;code&gt; / tag.&lt;code&gt;.&lt;key&gt;。
 * 不支持任意 SQL, 规则只在 Java 侧对候选集求值。</p>
 *
 * @author smartdock
 */
@Data
public class ProfileSegmentRule implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public static final Set<String> OPS = Set.of(
        "EQ", "NEQ", "GT", "GTE", "LT", "LTE", "IN", "NOT_IN", "CONTAINS", "EXISTS", "NOT_EXISTS");

    /** ALL / ANY */
    private String match = "ALL";

    private List<Condition> conditions = new ArrayList<>();

    @Data
    public static class Condition implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private String field;

        private String op;

        private Object value;
    }

    public static List<String> validate(ProfileSegmentRule rule) {
        List<String> errors = new ArrayList<>();
        if (rule == null) {
            errors.add("分群规则不能为空");
            return errors;
        }
        String match = rule.getMatch() == null ? "ALL" : rule.getMatch().trim().toUpperCase();
        if (!"ALL".equals(match) && !"ANY".equals(match)) {
            errors.add("match 只支持 ALL / ANY");
        }
        if (rule.getConditions() == null || rule.getConditions().isEmpty()) {
            errors.add("至少需要一个条件");
            return errors;
        }
        if (rule.getConditions().size() > 20) {
            errors.add("条件最多 20 个");
        }
        for (int i = 0; i < rule.getConditions().size(); i++) {
            Condition c = rule.getConditions().get(i);
            String prefix = "conditions[" + i + "]";
            if (c == null) {
                errors.add(prefix + " 不能为空");
                continue;
            }
            if (!validField(c.getField())) {
                errors.add(prefix + ".field 不支持: " + c.getField());
            }
            String op = c.getOp() == null ? "" : c.getOp().trim().toUpperCase();
            if (!OPS.contains(op)) {
                errors.add(prefix + ".op 不支持: " + c.getOp());
            } else if (!"EXISTS".equals(op) && !"NOT_EXISTS".equals(op)
                && (c.getValue() == null || String.valueOf(c.getValue()).isBlank())) {
                errors.add(prefix + ".value 不能为空");
            }
        }
        return errors;
    }

    public boolean matches(Map<String, Object> candidate) {
        List<Condition> list = conditions == null ? List.of() : conditions;
        if (list.isEmpty()) {
            return false;
        }
        boolean any = "ANY".equalsIgnoreCase(match);
        for (Condition c : list) {
            boolean hit = matchesOne(candidate, c);
            if (any && hit) {
                return true;
            }
            if (!any && !hit) {
                return false;
            }
        }
        return !any;
    }

    /**
     * 人类可读摘要, 用于成员 reason。
     */
    public String summary() {
        StringBuilder sb = new StringBuilder("规则命中(" + ("ANY".equalsIgnoreCase(match) ? "任一" : "全部") + "): ");
        List<Condition> list = conditions == null ? List.of() : conditions;
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) {
                sb.append(anyJoin());
            }
            Condition c = list.get(i);
            sb.append(c.getField()).append(' ').append(c.getOp());
            if (c.getValue() != null) {
                sb.append(' ').append(c.getValue());
            }
        }
        return sb.toString();
    }

    private String anyJoin() {
        return "ANY".equalsIgnoreCase(match) ? " 或 " : " 且 ";
    }

    private boolean matchesOne(Map<String, Object> candidate, Condition c) {
        if (c == null) {
            return false;
        }
        Object actual = resolve(candidate, c.getField());
        String op = c.getOp() == null ? "" : c.getOp().trim().toUpperCase();
        return switch (op) {
            case "EQ" -> equalsValue(actual, c.getValue());
            case "NEQ" -> !equalsValue(actual, c.getValue());
            case "GT" -> actual != null && c.getValue() != null && compare(actual, c.getValue()) > 0;
            case "GTE" -> actual != null && c.getValue() != null && compare(actual, c.getValue()) >= 0;
            case "LT" -> actual != null && c.getValue() != null && compare(actual, c.getValue()) < 0;
            case "LTE" -> actual != null && c.getValue() != null && compare(actual, c.getValue()) <= 0;
            case "IN" -> in(actual, c.getValue(), true);
            case "NOT_IN" -> in(actual, c.getValue(), false);
            case "CONTAINS" -> contains(actual, c.getValue());
            case "EXISTS" -> exists(actual);
            case "NOT_EXISTS" -> !exists(actual);
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private Object resolve(Map<String, Object> candidate, String field) {
        if (candidate == null || field == null) {
            return null;
        }
        String f = field.trim();
        if ("totalScore".equals(f) || "eventCount".equals(f) || "lastEventDays".equals(f)
            || "subjectType".equals(f) || "subjectId".equals(f) || "displayName".equals(f)) {
            return candidate.get(f);
        }
        if (f.startsWith("property.")) {
            return nested(candidate.get("properties"), f.substring("property.".length()));
        }
        if (f.startsWith("dimension.")) {
            Object dim = nested(candidate.get("dimensions"), f.substring("dimension.".length()));
            if (!(dim instanceof Map<?, ?> raw)) {
                return null;
            }
            Map<String, Object> m = (Map<String, Object>) raw;
            if (Boolean.TRUE.equals(m.get("skipped"))) {
                return null;
            }
            Object normalized = m.get("normalizedScore");
            return normalized != null ? normalized : m.get("score");
        }
        if (f.startsWith("tag.")) {
            String rest = f.substring("tag.".length());
            int dot = rest.indexOf('.');
            if (dot <= 0) {
                return null;
            }
            Object dim = nested(candidate.get("dimensions"), rest.substring(0, dot));
            if (!(dim instanceof Map<?, ?> raw)) {
                return null;
            }
            Map<String, Object> m = (Map<String, Object>) raw;
            return nested(m.get("tags"), rest.substring(dot + 1));
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Object nested(Object map, String key) {
        if (!(map instanceof Map<?, ?> m) || key == null) {
            return null;
        }
        return ((Map<String, Object>) m).get(key);
    }

    private boolean equalsValue(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return actual == expected;
        }
        BigDecimal a = decimal(actual);
        BigDecimal b = decimal(expected);
        if (a != null && b != null) {
            return a.compareTo(b) == 0;
        }
        return String.valueOf(actual).equalsIgnoreCase(String.valueOf(expected));
    }

    private int compare(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return -1;
        }
        BigDecimal a = decimal(actual);
        BigDecimal b = decimal(expected);
        if (a != null && b != null) {
            return a.compareTo(b);
        }
        return String.valueOf(actual).compareTo(String.valueOf(expected));
    }

    private boolean in(Object actual, Object expected, boolean positive) {
        if (actual == null) {
            return !positive;
        }
        Set<String> wanted = new LinkedHashSet<>();
        if (expected instanceof Collection<?> c) {
            c.forEach(v -> wanted.add(normalize(v)));
        } else if (expected != null) {
            for (String part : String.valueOf(expected).split(",")) {
                wanted.add(normalize(part));
            }
        }
        BigDecimal a = decimal(actual);
        boolean hit = a != null
            ? wanted.stream().map(this::decimal).filter(Objects::nonNull).anyMatch(v -> v.compareTo(a) == 0)
            : wanted.contains(normalize(actual));
        return positive == hit;
    }

    private boolean contains(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return false;
        }
        if (actual instanceof Collection<?> c) {
            return c.stream().anyMatch(v -> normalize(v).contains(normalize(expected)));
        }
        return String.valueOf(actual).contains(String.valueOf(expected));
    }

    private boolean exists(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof Collection<?> c) {
            return !c.isEmpty();
        }
        if (value instanceof Map<?, ?> m) {
            return !m.isEmpty();
        }
        return !String.valueOf(value).isBlank();
    }

    private String normalize(Object value) {
        BigDecimal d = decimal(value);
        return d == null ? String.valueOf(value) : d.stripTrailingZeros().toPlainString();
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal b) {
            return b;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean validField(String field) {
        if (field == null) {
            return false;
        }
        String f = field.trim();
        if (Set.of("totalScore", "eventCount", "lastEventDays",
            "subjectType", "subjectId", "displayName").contains(f)) {
            return true;
        }
        if (f.startsWith("property.") && f.length() > "property.".length()) {
            return true;
        }
        if (f.startsWith("dimension.") && f.length() > "dimension.".length()) {
            return true;
        }
        if (f.startsWith("tag.")) {
            String rest = f.substring("tag.".length());
            return rest.indexOf('.') > 0 && rest.length() > rest.indexOf('.') + 1;
        }
        return false;
    }
}
