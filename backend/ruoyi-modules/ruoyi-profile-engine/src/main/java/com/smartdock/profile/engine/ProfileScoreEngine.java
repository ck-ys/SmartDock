package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.smartdock.profile.domain.ProfileScoreDimension;
import com.smartdock.profile.mapper.ProfileScoreDimensionMapper;
import com.smartdock.profile.mapper.ProfileScoreSnapshotMapper;
import com.smartdock.profile.domain.ProfileScoreRule;
import com.smartdock.profile.mapper.ProfileScoreRuleMapper;
import com.smartdock.profile.mapper.ProfileEventMapper;
import com.smartdock.profile.spi.DimensionContext;
import com.smartdock.profile.spi.DimensionResult;
import com.smartdock.profile.spi.DimensionStrategy;
import com.smartdock.profile.spi.EventQueryPort;
import com.smartdock.profile.spi.ProfilePostProcessor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画像打分引擎: 载入维度 → 逐维度执行(EVENT_AGG / STRATEGY) → 权重合成 → 快照落库(前链)。
 *
 * <p>单主体一次调用; 全量走 {@link ProfileRecalcService}。任何单维度失败不阻断其他维度。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileScoreEngine {

    private final ProfileScoreDimensionMapper dimensionMapper;
    private final ProfileScoreRuleMapper ruleMapper;
    private final ProfileScoreSnapshotMapper snapshotMapper;
    private final DimensionEvaluator evaluator;
    private final ProfileEventMapper eventMapper;
    private final List<DimensionStrategy> strategies;
    private final List<ProfilePostProcessor> postProcessors;

    /**
     * 重算单主体并落一条快照。
     *
     * @return 新快照(含 snapshotId); 无启用维度时也落快照(total=中性, detail 说明空维度)
     */
    public ProfileScoreSnapshot recalcSubject(String source, String subjectType, String subjectId) {
        String tenantId = currentTenant();
        List<ProfileScoreDimension> dims = dimensionMapper.selectList(
            new LambdaQueryWrapper<ProfileScoreDimension>()
                .eq(ProfileScoreDimension::getSource, source)
                .eq(ProfileScoreDimension::getEnabled, "1")
                .orderByAsc(ProfileScoreDimension::getSortOrder));
        ProfileScoreRule rule = latestRule(source);
        Map<String, Double> weights = parseWeights(rule);

        // 维度执行
        Map<String, Object> detail = new LinkedHashMap<>();
        BigDecimal weightedSum = BigDecimal.ZERO;
        BigDecimal weightSum = BigDecimal.ZERO;
        double defaultWeight = dims.isEmpty() ? 0 : 1.0 / dims.size();
        for (ProfileScoreDimension dim : dims) {
            String key = dim.getDimCode();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("dimName", dim.getDimName());
            entry.put("calcMode", dim.getCalcMode());
            DimensionResult r = executeDimension(tenantId, source, subjectType, subjectId, dim, entry);
            double w = weights.getOrDefault(key, defaultWeight);
            entry.put("weight", w);
            if (r == null || r.isSkipped()) {
                entry.put("skipped", true);
                detail.put(key, entry);
                continue;
            }
            entry.put("score", r.getScore());
            // 不同维度 scoreMax 可能不同；总分口径统一归一到 100，再按权重合成
            BigDecimal scoreMax = dim.getScoreMax() == null || dim.getScoreMax().signum() <= 0
                ? BigDecimal.valueOf(100) : dim.getScoreMax();
            BigDecimal normalized = r.getScore().multiply(BigDecimal.valueOf(100))
                .divide(scoreMax, 2, RoundingMode.HALF_UP);
            entry.put("scoreMax", scoreMax);
            entry.put("normalizedScore", normalized);
            entry.put("weightedScore", normalized.multiply(BigDecimal.valueOf(w)).setScale(2, RoundingMode.HALF_UP));
            if (r.getMetric() != null) {
                entry.put("metric", r.getMetric());
            }
            entry.put("sampleCount", r.getSampleCount());
            if (r.getTags() != null && !r.getTags().isEmpty()) {
                entry.put("tags", r.getTags());
            }
            detail.put(key, entry);
            weightedSum = weightedSum.add(normalized.multiply(BigDecimal.valueOf(w)));
            weightSum = weightSum.add(BigDecimal.valueOf(w));
        }

        // 总分 = 参与维度加权和 / 参与权重和(跳过维度触发归一化)
        BigDecimal total = weightSum.compareTo(BigDecimal.ZERO) > 0
            ? weightedSum.divide(weightSum, 2, RoundingMode.HALF_UP)
            : BigDecimal.valueOf(50);

        // 前链 + 落库(dspro P1-3 回修: 先落库、再以已存在行跑后处理——processor 拿到的
        // snapshotId 真实可外键; _extra 经一次 UPDATE 合并回本条快照, 不再构造幽灵快照)
        ProfileScoreSnapshot prev = latestSnapshot(source, subjectType, subjectId);
        ProfileScoreSnapshot snapshot = buildSnapshot(tenantId, source, subjectType, subjectId,
            total, detail, rule == null ? "none" : rule.getRuleVersion(), prev == null ? null : prev.getSnapshotId());
        snapshotMapper.insertJsonb(snapshot);
        if (!postProcessors.isEmpty()) {
            Map<String, Object> extra = new LinkedHashMap<>();
            ProfilePostProcessor.SnapshotContext ctx = new ProfilePostProcessor.SnapshotContext(snapshot, extra);
            for (ProfilePostProcessor p : postProcessors) {
                try {
                    p.process(ctx);
                } catch (Exception e) {
                    log.warn("[Profile] postProcessor {} failed (skipped): {}", p.getClass().getSimpleName(), e.getMessage());
                }
            }
            if (!extra.isEmpty()) {
                detail.put("_extra", extra);
                snapshot.setDimensionDetail(JsonUtils.toJsonString(detail));
                snapshotMapper.updateDimensionDetail(snapshot.getSnapshotId(), tenantId, snapshot.getDimensionDetail());
            }
        }
        log.info("[Profile] snapshot written subject={} total={} dims={} prev={}",
            subjectId, total, detail.size(), snapshot.getPrevSnapshotId());
        return snapshot;
    }

    private DimensionResult executeDimension(String tenantId, String source, String subjectType,
                                             String subjectId, ProfileScoreDimension dim, Map<String, Object> entry) {
        try {
            if ("STRATEGY".equalsIgnoreCase(dim.getCalcMode())) {
                Map<String, Object> cfg = JsonUtils.parseMap(dim.getCalcConfig());
                String bean = cfg == null ? null : String.valueOf(cfg.get("strategyBean"));
                DimensionStrategy strategy = findStrategy(source, dim.getDimCode(), bean);
                if (strategy == null) {
                    log.warn("[Profile] dim {} strategy bean not found ({})", dim.getDimCode(), bean);
                    return null;
                }
                DimensionContext ctx = new DimensionContext(tenantId, source, subjectType, subjectId,
                    dim.getDimCode(), new MapperEventQueryPort(source, subjectType, subjectId));
                DimensionResult r = strategy.calc(ctx);
                if (ctx.getEvidenceNotes() != null && !ctx.getEvidenceNotes().isEmpty() && r != null) {
                    r.getTags().put("evidenceNotes", ctx.getEvidenceNotes());
                }
                return r;
            }
            return evaluator.evaluate(source, subjectType, subjectId, dim);
        } catch (Exception e) {
            log.warn("[Profile] dim {} execute error (treated as skip): {}", dim.getDimCode(), e.getMessage());
            return null;
        }
    }

    private DimensionStrategy findStrategy(String source, String dimCode, String bean) {
        for (DimensionStrategy s : strategies) {
            if (s.source().equals(source) && s.dimCode().equals(dimCode)) {
                return s;
            }
        }
        if (StringUtils.isNotBlank(bean)) {
            for (DimensionStrategy s : strategies) {
                if (s.getClass().getName().equals(bean) || s.getClass().getSimpleName().equals(bean)) {
                    return s;
                }
            }
        }
        return null;
    }

    public ProfileScoreSnapshot latestSnapshot(String source, String subjectType, String subjectId) {
        return snapshotMapper.selectList(new LambdaQueryWrapper<ProfileScoreSnapshot>()
                .eq(ProfileScoreSnapshot::getSource, source)
                .eq(ProfileScoreSnapshot::getSubjectType, subjectType)
                .eq(ProfileScoreSnapshot::getSubjectId, subjectId)
            .orderByDesc(ProfileScoreSnapshot::getCalcTime)
            .orderByDesc(ProfileScoreSnapshot::getSnapshotId)
            .last("LIMIT 1"))
            .stream().findFirst().orElse(null);
    }

    public List<ProfileScoreSnapshot> snapshotTail(String source, String subjectType, String subjectId, int n) {
        return snapshotMapper.selectList(new LambdaQueryWrapper<ProfileScoreSnapshot>()
            .eq(ProfileScoreSnapshot::getSource, source)
            .eq(ProfileScoreSnapshot::getSubjectType, subjectType)
            .eq(ProfileScoreSnapshot::getSubjectId, subjectId)
            .orderByDesc(ProfileScoreSnapshot::getCalcTime)
            .orderByDesc(ProfileScoreSnapshot::getSnapshotId)
            .last("LIMIT " + Math.min(Math.max(1, n), 50))); // dspro P2-1: 链尾封顶 50, 防全量拉取
    }

    private ProfileScoreRule latestRule(String source) {
        return ruleMapper.selectList(new LambdaQueryWrapper<ProfileScoreRule>()
                .eq(ProfileScoreRule::getSource, source)
                .eq(ProfileScoreRule::getStatus, "PUBLISHED")
                .orderByDesc(ProfileScoreRule::getPublishedAt)
                .orderByDesc(ProfileScoreRule::getCreateTime)
                .last("LIMIT 1"))
            .stream().findFirst().orElse(null);
    }

    private Map<String, Double> parseWeights(ProfileScoreRule rule) {
        if (rule == null || StringUtils.isBlank(rule.getWeights())) {
            return Map.of();
        }
        Map<String, Object> raw = JsonUtils.parseMap(rule.getWeights());
        Map<String, Double> out = new LinkedHashMap<>();
        if (raw != null) {
            raw.forEach((k, v) -> {
                if (v instanceof Number n) {
                    out.put(k, n.doubleValue());
                } else if (v != null) {
                    try {
                        out.put(k, Double.parseDouble(String.valueOf(v)));
                    } catch (NumberFormatException ignored) {
                        // 坏权重跳过, 引擎按默认均分兜底
                    }
                }
            });
        }
        return out;
    }

    private ProfileScoreSnapshot buildSnapshot(String tenantId, String source, String subjectType, String subjectId,
                                               BigDecimal total, Map<String, Object> detail, String ruleVersion, Long prevId) {
        ProfileScoreSnapshot s = new ProfileScoreSnapshot();
        s.setSnapshotId(IdWorker.getId());
        s.setTenantId(tenantId);
        s.setSource(source);
        s.setSubjectType(subjectType);
        s.setSubjectId(subjectId);
        s.setTotalScore(total);
        s.setDimensionDetail(JsonUtils.toJsonString(detail));
        s.setRuleVersion(ruleVersion);
        s.setPrevSnapshotId(prevId);
        s.setCalcTime(new Date());
        try {
            s.setCreateBy(LoginHelper.getUserId());
        } catch (Exception ignored) {
            // 非登录上下文(定时/系统灌数)无创建人
        }
        return s;
    }

    private String currentTenant() {
        // dspro P2-2 回修: 口径对齐租户拦截器(dynamic > static), LoginHelper 兜底,
        // 双失守时缺省租户(与批1 行为兼容; 二期异步化时改 fail-fast)
        try {
            String t = org.dromara.common.tenant.helper.TenantHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 无租户上下文
        }
        try {
            return LoginHelper.getTenantId();
        } catch (Exception e) {
            return "000000";
        }
    }

    /**
     * EventQueryPort 的 Mapper 适配器(绑定单主体)。
     */
    @RequiredArgsConstructor
    private class MapperEventQueryPort implements EventQueryPort {

        private final String source;
        private final String subjectType;
        private final String subjectId;

        private Date windowStart(Integer windowDays) {
            return windowDays == null || windowDays <= 0 ? null
                : new Date(System.currentTimeMillis() - windowDays * 24L * 3600_0000L);
        }

        @Override
        public long count(String eventType, Integer windowDays, Map<String, String> propFilter) {
            String fk = null, fv = null;
            if (propFilter != null && !propFilter.isEmpty()) {
                Map.Entry<String, String> first = propFilter.entrySet().iterator().next();
                fk = DimensionEvaluator.safeKey(first.getKey());
                fv = first.getValue();
            }
            Long c = eventMapper.countEvents(source, subjectType, subjectId, eventType, windowStart(windowDays), fk, fv);
            return c == null ? 0 : c;
        }

        @Override
        public Double avgProp(String eventType, String prop, Integer windowDays) {
            return eventMapper.avgProp(source, subjectType, subjectId, eventType, windowStart(windowDays),
                DimensionEvaluator.safeKey(prop));
        }

        @Override
        public Double sumProp(String eventType, String prop, Integer windowDays) {
            return eventMapper.sumProp(source, subjectType, subjectId, eventType, windowStart(windowDays),
                DimensionEvaluator.safeKey(prop));
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> lastEventProps(String eventType) {
            String json = eventMapper.lastEventPropsJson(source, subjectType, subjectId, eventType, null);
            return json == null ? null : JsonUtils.parseObject(json, Map.class);
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Map<String, Object>> recentEvents(String eventType, Integer windowDays, int limit) {
            List<String> rows = eventMapper.recentEventPropsJson(source, subjectType, subjectId, eventType,
                windowStart(windowDays), Math.min(Math.max(1, limit), 200));
            List<Map<String, Object>> out = new ArrayList<>();
            if (rows != null) {
                for (String json : rows) {
                    Map<String, Object> m = JsonUtils.parseObject(json, Map.class);
                    if (m != null) {
                        out.add(m);
                    }
                }
            }
            return out;
        }
    }
}
