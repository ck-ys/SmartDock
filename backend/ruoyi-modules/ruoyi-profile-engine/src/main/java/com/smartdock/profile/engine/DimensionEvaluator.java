package com.smartdock.profile.engine;

import com.smartdock.profile.domain.ProfileScoreDimension;
import com.smartdock.profile.mapper.ProfileEventMapper;
import com.smartdock.profile.operator.MetricSpec;
import com.smartdock.profile.operator.MissingPolicy;
import com.smartdock.profile.operator.ScoreFunctions;
import com.smartdock.profile.operator.ScoreSpec;
import com.smartdock.profile.operator.WindowSpec;
import com.smartdock.profile.spi.DimensionResult;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * EVENT_AGG 维度执行器: 解析 calc_config → 跑算子 SQL → 打分映射 → 维度结果。
 *
 * <p>单维度单主体一次执行; 异常一律降级为 missing 策略, 不外抛(引擎容错铁律)。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DimensionEvaluator {

    private final ProfileEventMapper eventMapper;

    /** 进 ${} 内嵌的 props 键白名单([A-Za-z0-9_]), 防注入 */
    private static final Pattern PROP_KEY = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,62}$");

    /**
     * calc_config 顶层模型。
     */
    @Data
    public static class AggConfig {
        private MetricSpec metric;
        private WindowSpec window = new WindowSpec();
        private ScoreSpec score = new ScoreSpec();
        private String missing;

        public MissingPolicy missingPolicy() {
            return MissingPolicy.of(missing);
        }
    }

    /**
     * 执行 EVENT_AGG 维度。
     */
    public DimensionResult evaluate(String source, String subjectType, String subjectId,
                                    ProfileScoreDimension dim) {
        AggConfig cfg = JsonUtils.parseObject(dim.getCalcConfig(), AggConfig.class);
        if (cfg == null || cfg.getMetric() == null || StringUtils.isBlank(cfg.getMetric().getOperator())) {
            log.warn("[Profile] dim {} calc_config invalid, fallback missing", dim.getDimCode());
            return policyMissing(MissingPolicy.NEUTRAL, dim, Map.of("error", "invalid calc_config"));
        }
        BigDecimal scoreMax = dim.getScoreMax() == null ? BigDecimal.valueOf(100) : dim.getScoreMax();
        Date windowStart = windowStart(cfg.getWindow());
        try {
            MetricSpec m = cfg.getMetric();
            String op = m.getOperator().trim().toUpperCase();
            rejectUnsupportedFilter(m, op);
            MetricValue mv = switch (op) {
                case "COUNT" -> count(m, source, subjectType, subjectId, windowStart);
                case "COUNT_DISTINCT" -> countDistinct(m, source, subjectType, subjectId, windowStart);
                case "SUM" -> sumProp(m, source, subjectType, subjectId, windowStart);
                case "AVG" -> avgProp(m, source, subjectType, subjectId, windowStart);
                case "RATE" -> rate(m, source, subjectType, subjectId, windowStart);
                case "LAST_VALUE" -> lastValue(m, source, subjectType, subjectId, windowStart);
                default -> throw new IllegalArgumentException("unknown operator " + op);
            };
            if (mv.value == null || mv.sampleCount <= 0) {
                Map<String, Object> ev = evidence(cfg, null, 0, mv);
                ev.put("missing", true);
                if (mv.tags != null && !mv.tags.isEmpty()) {
                    ev.put("tags", mv.tags);
                }
                return policyMissing(cfg.missingPolicy(), dim, ev);
            }
            BigDecimal score = ScoreFunctions.map(mv.value, cfg.getScore(), scoreMax);
            DimensionResult r = new DimensionResult(score, mv.value, mv.sampleCount, new LinkedHashMap<>());
            r.getTags().putAll(mv.tags);
            r.getTags().put("window", cfg.getWindow().describe());
            r.getTags().put("evidence", evidence(cfg, mv.value, mv.sampleCount, mv));
            return r;
        } catch (Exception e) {
            // dspro P1-2 回修: 失败也尊重维度配置的缺失策略(此前硬编码 NEUTRAL,
            // zero/skip 语义对失败路径不生效)
            log.warn("[Profile] dim {} evaluate failed, fallback missing: {}", dim.getDimCode(), e.getMessage());
            return policyMissing(cfg.missingPolicy(), dim, Map.of("error", String.valueOf(e.getMessage())));
        }
    }

    // ------------------------------------------------------------ 算子实现

    private record MetricValue(BigDecimal value, long sampleCount, Map<String, Object> tags) {
        MetricValue(BigDecimal v, long n) {
            this(v, n, new LinkedHashMap<>());
        }
    }

    /**
     * dspro P1-1 回修: propFilter 当前仅 COUNT 算子支持(且单键)——其余算子/多键配置
     * 一律 fail-fast 拒绝而非静默降级(错分不可对账); 异常走 evaluate 的 missing 兜底,
     * evidence 留 error 痕迹, 管理员可从快照回溯"配置没生效"。
     */
    private void rejectUnsupportedFilter(MetricSpec m, String op) {
        boolean count = "COUNT".equals(op);
        for (MetricSpec.TargetSpec t : new MetricSpec.TargetSpec[] {
                m.getTarget(), m.getNumerator(), m.getDenominator()}) {
            if (t == null || t.getPropFilter() == null || t.getPropFilter().isEmpty()) {
                continue;
            }
            if (!count || t.getPropFilter().size() > 1) {
                // M3 P2: 携带具体键名, 管理员不必再翻 calc_config 定位
                throw new IllegalArgumentException(
                    "propFilter unsupported for operator " + op + (count ? " (multi-key)" : "")
                        + ", keys=" + t.getPropFilter().keySet());
            }
        }
    }

    private MetricValue count(MetricSpec m, String src, String st, String sid, Date ws) {
        MetricSpec.TargetSpec t = requireTarget(m, "target");
        String fk = null, fv = null;
        if (t.getPropFilter() != null && !t.getPropFilter().isEmpty()) {
            // 单键由 rejectUnsupportedFilter 保证(dspro P1-1)
            Map.Entry<String, String> first = t.getPropFilter().entrySet().iterator().next();
            fk = safeKey(first.getKey());
            fv = first.getValue();
        }
        long c = eventMapper.countEvents(src, st, sid, t.getEventType(), ws, fk, fv);
        return new MetricValue(BigDecimal.valueOf(c), c);
    }

    private MetricValue countDistinct(MetricSpec m, String src, String st, String sid, Date ws) {
        MetricSpec.TargetSpec t = requireTarget(m, "target");
        String key = safeKey(m.getDedupKey());
        long c = eventMapper.countDistinct(src, st, sid, t.getEventType(), ws, key);
        return new MetricValue(BigDecimal.valueOf(c), c);
    }

    private MetricValue sumProp(MetricSpec m, String src, String st, String sid, Date ws) {
        MetricSpec.TargetSpec t = requireTarget(m, "target");
        String prop = safeKey(m.getProp());
        Double d = eventMapper.sumProp(src, st, sid, t.getEventType(), ws, prop);
        long n = sampleCount(t, src, st, sid, ws);
        return new MetricValue(d == null ? null : BigDecimal.valueOf(d), n);
    }

    private MetricValue avgProp(MetricSpec m, String src, String st, String sid, Date ws) {
        MetricSpec.TargetSpec t = requireTarget(m, "target");
        String prop = safeKey(m.getProp());
        Double d = eventMapper.avgProp(src, st, sid, t.getEventType(), ws, prop);
        long n = sampleCount(t, src, st, sid, ws);
        return new MetricValue(d == null ? null : BigDecimal.valueOf(d), n);
    }

    private MetricValue rate(MetricSpec m, String src, String st, String sid, Date ws) {
        MetricSpec.TargetSpec num = requireTarget(m, "numerator");
        MetricSpec.TargetSpec den = requireTarget(m, "denominator");
        long n = eventMapper.countEvents(src, st, sid, num.getEventType(), ws, null, null);
        long d = eventMapper.countEvents(src, st, sid, den.getEventType(), ws, null, null);
        if (d <= 0) {
            return new MetricValue(null, 0);
        }
        BigDecimal ratio = BigDecimal.valueOf(n)
            .divide(BigDecimal.valueOf(d), 10, RoundingMode.HALF_UP);
        Map<String, Object> tags = new LinkedHashMap<>();
        tags.put("numeratorCount", n);
        tags.put("denominatorCount", d);
        return new MetricValue(ratio, d, tags);
    }

    private MetricValue lastValue(MetricSpec m, String src, String st, String sid, Date ws) {
        MetricSpec.TargetSpec t = requireTarget(m, "target");
        String prop = safeKey(m.getProp());
        String raw = eventMapper.lastValueProp(src, st, sid, t.getEventType(), ws, prop);
        if (StringUtils.isBlank(raw)) {
            return new MetricValue(null, 0);
        }
        try {
            return new MetricValue(new BigDecimal(raw), 1);
        } catch (NumberFormatException e) {
            // 非数值属性(状态文本): 当事实标签留痕, 指标缺失走 missing
            Map<String, Object> tags = new LinkedHashMap<>();
            tags.put("lastText", raw);
            return new MetricValue(null, 1, tags);
        }
    }

    private long sampleCount(MetricSpec.TargetSpec t, String src, String st, String sid, Date ws) {
        Long c = eventMapper.countEvents(src, st, sid, t.getEventType(), ws, null, null);
        return c == null ? 0 : c;
    }

    // ------------------------------------------------------------ 辅助

    /**
     * 按缺失策略产出维度结果。
     */
    private DimensionResult policyMissing(MissingPolicy policy, ProfileScoreDimension dim, Map<String, Object> evidence) {
        return switch (policy == null ? MissingPolicy.NEUTRAL : policy) {
            case SKIP -> DimensionResult.skipped();
            case ZERO -> new DimensionResult(BigDecimal.ZERO, null, 0, new LinkedHashMap<>(evidence));
            case NEUTRAL -> {
                BigDecimal scoreMax = dim.getScoreMax() == null ? BigDecimal.valueOf(100) : dim.getScoreMax();
                BigDecimal neutral = scoreMax.divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
                yield new DimensionResult(neutral, null, 0, new LinkedHashMap<>(evidence));
            }
        };
    }

    private Map<String, Object> evidence(AggConfig cfg, BigDecimal value, long n, MetricValue mv) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("operator", cfg.getMetric().getOperator());
        ev.put("window", cfg.getWindow().describe());
        if (value != null) {
            ev.put("metric", value);
        }
        ev.put("sampleCount", n);
        return ev;
    }

    private Date windowStart(WindowSpec w) {
        if (w == null || !w.isRolling()) {
            return null;
        }
        return new Date(System.currentTimeMillis() - w.getDays() * 24L * 3600_0000L);
    }

    private MetricSpec.TargetSpec requireTarget(MetricSpec m, String field) {
        MetricSpec.TargetSpec t = "numerator".equals(field) ? m.getNumerator()
            : "denominator".equals(field) ? m.getDenominator() : m.getTarget();
        if (t == null || StringUtils.isBlank(t.getEventType())) {
            throw new IllegalArgumentException("metric." + field + ".eventType required");
        }
        return t;
    }

    /**
     * props 键安全校验: 该键会被 ${} 内嵌进 SQL, 白名单外的直接拒绝。
     */
    static String safeKey(String key) {
        if (StringUtils.isBlank(key) || !PROP_KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("illegal props key: " + key);
        }
        return key;
    }
}
