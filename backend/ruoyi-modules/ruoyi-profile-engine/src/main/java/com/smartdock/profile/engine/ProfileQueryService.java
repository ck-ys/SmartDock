package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.profile.domain.ProfileScoreDimension;
import com.smartdock.profile.domain.ProfileScoreRule;
import com.smartdock.profile.event.ProfileSubject;
import com.smartdock.profile.mapper.ProfileScoreSnapshotMapper;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import com.smartdock.profile.query.ProfileOverview;
import com.smartdock.profile.query.ProfileRankingRow;
import com.smartdock.profile.query.ProfileSubjectQuery;
import com.smartdock.profile.query.ProfileSubjectRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 画像查询面: 主体列表、总览、排行、详情聚合。
 *
 * <p>只读; 列表/排行在 SQL 里用 LATERAL 取最新快照, 详情复用打分引擎的链尾查询。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileQueryService {

    private static final Set<String> SORT_FIELDS = Set.of("lastEvent", "score", "events", "name");

    private final ProfileSubjectMapper subjectMapper;
    private final ProfileScoreSnapshotMapper snapshotMapper;
    private final ProfileScoreEngine scoreEngine;
    private final ProfileDimensionService dimensionService;
    private final ProfileRuleService ruleService;
    private final ProfilePiiService piiService;

    public TableDataInfo<ProfileSubjectRow> subjectPage(ProfileSubjectQuery query, PageQuery pageQuery) {
        return subjectPage(query, pageQuery, null);
    }

    /**
     * 主体列表(画像方向第三批): scoreVisibleOverride 供助手工具线程传行级分数可见性——
     * 工具线程无 Sa-Token 上下文, piiService.canViewScore() 恒 false(保守), UI 路径不传(null)行为不变。
     * ⚠️ 仅限调用方已按菜单权限(profile:pii:score)判定的场景使用; 误传 TRUE = 越权输出全部分数与维度明细。
     */
    public TableDataInfo<ProfileSubjectRow> subjectPage(ProfileSubjectQuery query, PageQuery pageQuery,
                                                        Boolean scoreVisibleOverride) {
        if (query == null) {
            query = new ProfileSubjectQuery();
        }
        requireSource(query.getSource());
        normalizeQuery(query);
        if (pageQuery.getPageSize() == null || pageQuery.getPageSize() > 200) {
            // PageQuery 默认 pageSize=MAX_VALUE, 不封顶可一次拉全表
            pageQuery.setPageSize(200);
        }
        Page<ProfileSubjectRow> page = pageQuery.build();
        // 自定义 SQL 带 LATERAL + 计算列过滤, 关闭 count 优化, 用子查询包裹最稳
        page.setOptimizeCountSql(false);
        IPage<ProfileSubjectRow> result = subjectMapper.selectSubjectPage(page, currentTenant(), query);
        // 批11 Q1: 联系方式类属性打码; 无看分权限的角色分数列不返回
        boolean scoreVisible = scoreVisibleOverride != null ? scoreVisibleOverride : piiService.canViewScore();
        result.getRecords().forEach(row -> {
            row.setProperties(piiService.maskJsonOut(row.getProperties()));
            if (!scoreVisible) {
                row.setTotalScore(null);
                row.setDimensionDetail(null);
                row.setRuleVersion(null);
            }
        });
        return TableDataInfo.build(result);
    }

    public ProfileOverview overview(String source, String subjectType) {
        requireSource(source);
        String type = SubjectTypes.normalize(subjectType);
        ProfileOverview overview = subjectMapper.selectOverview(currentTenant(), source, type);
        if (overview == null) {
            overview = new ProfileOverview();
            overview.setSubjectCount(0L);
            overview.setScoredCount(0L);
            overview.setAvgScore(BigDecimal.ZERO);
            overview.setLowRisk(0L);
            overview.setMediumRisk(0L);
            overview.setHighRisk(0L);
        }
        overview.setLastCalcTime(snapshotMapper.selectLastCalcTime(currentTenant(), source, type));
        return overview;
    }

    public List<ProfileRankingRow> ranking(String source, String subjectType, String dimension, int limit) {
        requireSource(source);
        int capped = Math.min(Math.max(limit <= 0 ? 10 : limit, 1), 100);
        String dim = StringUtils.isBlank(dimension) ? null : dimension.trim();
        if (dim != null) {
            boolean known = dimensionService.list(source, SubjectTypes.normalize(subjectType)).stream()
                .anyMatch(d -> dim.equals(d.getDimCode()));
            if (!known) {
                throw new ServiceException("排行维度不存在: " + dim);
            }
        }
        List<ProfileRankingRow> rows = subjectMapper.selectRanking(currentTenant(), source,
            StringUtils.isBlank(subjectType) ? null : subjectType.trim(), dim, capped);
        // 批11 回修(终审 P1): 排行分数列与列表同闸——无列级分数权限置空, 只留主体与名次
        boolean canScore = piiService.canViewScore();
        int rank = 1;
        for (ProfileRankingRow row : rows) {
            row.setRank(rank++);
            if (!canScore) {
                row.setTotalScore(null);
                row.setDimensionScore(null);
            }
        }
        return rows;
    }

    /**
     * 单主体详情: 主体登记 + 最新快照 + 维度明细 + 排名/风险 + 趋势/证据。
     */
    public Map<String, Object> detail(String source, String subjectType, String subjectId, int tail) {
        requireSource(source);
        if (StringUtils.isBlank(subjectId)) {
            throw new ServiceException("subjectId 不能为空");
        }
        String type = StringUtils.isBlank(subjectType) ? "user" : subjectType;
        ProfileSubject subject = subjectMapper.selectList(new LambdaQueryWrapper<ProfileSubject>()
                .eq(ProfileSubject::getSource, source)
                .eq(ProfileSubject::getSubjectType, type)
                .eq(ProfileSubject::getSubjectId, subjectId)
                .last("LIMIT 1"))
            .stream().findFirst().orElse(null);

        ProfileScoreSnapshot latest = scoreEngine.latestSnapshot(source, type, subjectId);
        List<ProfileScoreSnapshot> history = latest == null ? List.of()
            : scoreEngine.snapshotTail(source, type, subjectId, tail);
        Map<String, Object> detailMap = parseObject(latest == null ? null : latest.getDimensionDetail());
        List<Map<String, Object>> dimensions = buildDimensionRows(source, type, detailMap);

        // 批11 回修(终审 P1): 详情口与列表口同权——属性打码 + 无列级分数权限置空
        boolean canScore = piiService.canViewScore();
        if (subject != null) {
            subject.setProperties(piiService.maskJsonOut(subject.getProperties()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subject", subject);
        out.put("subjectProperties", piiService.maskOut(parseObject(subject == null ? null : subject.getProperties())));
        out.put("latest", canScore ? latest : null);
        out.put("history", canScore ? history : List.of());
        out.put("dimensions", canScore ? dimensions : List.of());
        out.put("rule", ruleService.current(source, type));
        out.put("rank", canScore ? rankOf(source, type, latest) : null);
        out.put("risk", canScore ? riskOf(latest == null ? null : latest.getTotalScore()) : null);
        out.put("trend", canScore ? trend(history) : List.of());
        out.put("dataQuality", canScore ? dataQuality(dimensions) : null);
        out.put("scoreHidden", !canScore);
        return out;
    }

    private List<Map<String, Object>> buildDimensionRows(String source, String subjectType, Map<String, Object> detailMap) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<ProfileScoreDimension> configured = dimensionService.list(source, subjectType);
        for (ProfileScoreDimension dim : configured) {
            rows.add(dimensionRow(dim.getDimCode(), dim.getDimName(), dim.getScoreMax(),
                dim.getSortOrder(), dim.getEnabled(), detailMap.get(dim.getDimCode())));
        }
        // 历史快照里可能有已删除维度, 仍然展示, 避免“分数对不上”
        Set<String> configuredCodes = configured.stream().map(ProfileScoreDimension::getDimCode)
            .collect(java.util.stream.Collectors.toSet());
        for (Map.Entry<String, Object> entry : detailMap.entrySet()) {
            if (!configuredCodes.contains(entry.getKey())) {
                rows.add(dimensionRow(entry.getKey(), entry.getKey(), BigDecimal.valueOf(100), 9999, "0", entry.getValue()));
            }
        }
        return rows;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dimensionRow(String code, String name, BigDecimal scoreMax,
                                             Integer sortOrder, String enabled, Object rawEntry) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("dimCode", code);
        row.put("dimName", name);
        row.put("scoreMax", scoreMax == null ? BigDecimal.valueOf(100) : scoreMax);
        row.put("sortOrder", sortOrder == null ? 0 : sortOrder);
        row.put("enabled", enabled);
        Map<String, Object> entry = rawEntry instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        row.put("score", entry.get("score"));
        row.put("normalizedScore", entry.get("normalizedScore"));
        row.put("weight", entry.get("weight"));
        row.put("weightedScore", entry.get("weightedScore"));
        row.put("metric", entry.get("metric"));
        row.put("sampleCount", entry.get("sampleCount"));
        row.put("tags", entry.get("tags"));
        row.put("skipped", Boolean.TRUE.equals(entry.get("skipped")));
        Map<String, Object> tags = entry.get("tags") instanceof Map<?, ?> tm
            ? (Map<String, Object>) tm : Map.of();
        row.put("missing", Boolean.TRUE.equals(tags.get("missing")));
        BigDecimal normalized = entry.get("normalizedScore") instanceof Number n
            ? BigDecimal.valueOf(n.doubleValue()) : null;
        if (normalized == null && entry.get("score") instanceof Number score) {
            BigDecimal max = scoreMax == null || scoreMax.signum() <= 0 ? BigDecimal.valueOf(100) : scoreMax;
            normalized = BigDecimal.valueOf(score.doubleValue()).multiply(BigDecimal.valueOf(100))
                .divide(max, 2, RoundingMode.HALF_UP);
        }
        row.put("level", levelOf(normalized, Boolean.TRUE.equals(tags.get("missing"))));
        return row;
    }

    private List<Map<String, Object>> trend(List<ProfileScoreSnapshot> history) {
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0; i--) {
            ProfileScoreSnapshot snap = history.get(i);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("snapshotId", snap.getSnapshotId());
            point.put("totalScore", snap.getTotalScore());
            point.put("ruleVersion", snap.getRuleVersion());
            point.put("calcTime", snap.getCalcTime());
            trend.add(point);
        }
        return trend;
    }

    private Map<String, Object> dataQuality(List<Map<String, Object>> dimensions) {
        int total = dimensions.size();
        int missing = (int) dimensions.stream().filter(d -> Boolean.TRUE.equals(d.get("missing"))).count();
        int skipped = (int) dimensions.stream().filter(d -> Boolean.TRUE.equals(d.get("skipped"))).count();
        int covered = Math.max(0, total - missing - skipped);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dimensionCount", total);
        out.put("coveredCount", covered);
        out.put("missingCount", missing);
        out.put("skippedCount", skipped);
        out.put("coverageRate", total == 0 ? 0
            : BigDecimal.valueOf(covered * 100.0 / total).setScale(2, RoundingMode.HALF_UP));
        return out;
    }

    private Integer rankOf(String source, String subjectType, ProfileScoreSnapshot latest) {
        if (latest == null || latest.getTotalScore() == null) {
            return null;
        }
        Long higher = subjectMapper.countHigherScore(currentTenant(), source, subjectType, latest.getTotalScore());
        return (higher == null ? 0 : higher.intValue()) + 1;
    }

    private Map<String, Object> riskOf(BigDecimal total) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (total == null) {
            out.put("level", "UNKNOWN");
            out.put("label", "未算分");
            out.put("color", "info");
            return out;
        }
        if (total.compareTo(RISK_HIGH) >= 0) {
            out.put("level", "LOW");
            out.put("label", "低风险");
            out.put("color", "success");
        } else if (total.compareTo(RISK_MEDIUM) >= 0) {
            out.put("level", "MEDIUM");
            out.put("label", "关注");
            out.put("color", "warning");
        } else {
            out.put("level", "HIGH");
            out.put("label", "高风险");
            out.put("color", "danger");
        }
        return out;
    }

    private String levelOf(BigDecimal normalized, boolean missing) {
        if (missing) {
            return "缺证据";
        }
        if (normalized == null) {
            return "—";
        }
        if (normalized.compareTo(BigDecimal.valueOf(80)) >= 0) {
            return "优秀";
        }
        if (normalized.compareTo(BigDecimal.valueOf(60)) >= 0) {
            return "良好";
        }
        if (normalized.compareTo(BigDecimal.valueOf(40)) >= 0) {
            return "待提升";
        }
        return "偏弱";
    }

    /** 风险分档阈值(批9 Q7-A 单一来源): HIGH < 60, MEDIUM 60~75, LOW >= 75 */
    public static final java.math.BigDecimal RISK_MEDIUM = java.math.BigDecimal.valueOf(60);
    public static final java.math.BigDecimal RISK_HIGH = java.math.BigDecimal.valueOf(75);

    public Map<String, Object> subjectSummary(String subjectType, String subjectId) {
        return subjectSummary(subjectType, subjectId, null);
    }

    public Map<String, Object> subjectSummary(String subjectType, String subjectId, String source) {
        return subjectSummary(subjectType, subjectId, source, null);
    }

    /**
     * 单主体摘要(批9 Q5/Q7-A): 基本信息 + 最新维度快照(分数/维度贡献/规则版本/计算时间)。
     * "查看依据"抽屉与助手只读工具共用此口径; source 可选(同 ID 跨源时避免取错)。
     *
     * @param scoreVisibleOverride 分数可见性显式覆盖(画像方向第三批): 助手工具线程无
     *        Sa-Token 上下文, StpUtil 恒 false(保守隐藏), 调用方按 BizToolCall.menuPermissions
     *        自判后传入; null=走 UI 原口径(StpUtil), UI 路径零变化
     */
    public Map<String, Object> subjectSummary(String subjectType, String subjectId, String source,
                                              Boolean scoreVisibleOverride) {
        boolean scoreVisible = scoreVisibleOverride != null ? scoreVisibleOverride : piiService.canViewScore();
        if (StringUtils.isBlank(subjectId)) {
            throw new ServiceException("subjectId 不能为空");
        }
        String type = StringUtils.isBlank(subjectType) ? "user" : subjectType.trim();
        com.smartdock.profile.event.ProfileSubject subject = subjectMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.smartdock.profile.event.ProfileSubject>()
                .eq(com.smartdock.profile.event.ProfileSubject::getTenantId, currentTenant())
                .eq(com.smartdock.profile.event.ProfileSubject::getSubjectType, type)
                .eq(com.smartdock.profile.event.ProfileSubject::getSubjectId, subjectId.trim())
                .eq(StringUtils.isNotBlank(source), com.smartdock.profile.event.ProfileSubject::getSource, source)
                // 三审 dspro P2-4: 跨源同 subjectId 时无排序会随机取一条——固定取最近活跃的
                .orderByDesc(com.smartdock.profile.event.ProfileSubject::getLastEventTime)
                .orderByDesc(com.smartdock.profile.event.ProfileSubject::getId)
                .last("LIMIT 1"))
            .stream().findFirst().orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        if (subject == null) {
            out.put("found", false);
            out.put("subjectType", type);
            out.put("subjectId", subjectId);
            return out;
        }
        out.put("found", true);
        out.put("subjectType", subject.getSubjectType());
        out.put("subjectId", subject.getSubjectId());
        out.put("displayName", subject.getDisplayName());
        out.put("source", subject.getSource());
        out.put("eventCount", subject.getEventCount());
        out.put("lastEventTime", subject.getLastEventTime());
        List<com.smartdock.profile.engine.ProfileScoreSnapshot> latest = snapshotMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.smartdock.profile.engine.ProfileScoreSnapshot>()
                .eq(com.smartdock.profile.engine.ProfileScoreSnapshot::getTenantId, subject.getTenantId())
                .eq(com.smartdock.profile.engine.ProfileScoreSnapshot::getSource, subject.getSource())
                .eq(com.smartdock.profile.engine.ProfileScoreSnapshot::getSubjectType, subject.getSubjectType())
                .eq(com.smartdock.profile.engine.ProfileScoreSnapshot::getSubjectId, subject.getSubjectId())
                .orderByDesc(com.smartdock.profile.engine.ProfileScoreSnapshot::getCalcTime)
                .orderByDesc(com.smartdock.profile.engine.ProfileScoreSnapshot::getSnapshotId)
                .last("LIMIT 1"));
        if (!scoreVisible) {
            // 三审 dsflash P2: 不出 scored:false——与 scoreHidden 同盒会让模型把「权限隐藏」误读成「没打过分的记录」;
            // 前端抽屉 v-if="basisData.scored" 对缺键与 false 渲染一致, UI 无感
            out.put("scoreHidden", true);
        } else if (latest.isEmpty()) {
            out.put("scored", false);
        } else {
            com.smartdock.profile.engine.ProfileScoreSnapshot snap = latest.get(0);
            out.put("scored", true);
            out.put("totalScore", snap.getTotalScore());
            out.put("ruleVersion", snap.getRuleVersion());
            out.put("calcTime", snap.getCalcTime());
            out.put("dimensionDetail", parseSnapshotDetail(snap.getDimensionDetail()));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseSnapshotDetail(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = org.dromara.common.json.utils.JsonUtils.parseMap(json);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void normalizeQuery(ProfileSubjectQuery query) {
        if (query.getRisk() != null) {
            String risk = query.getRisk().trim().toUpperCase();
            if (!java.util.Set.of("HIGH", "MEDIUM", "LOW").contains(risk)) {
                throw new ServiceException("risk 只支持 HIGH / MEDIUM / LOW");
            }
            query.setRisk(risk);
        }
        query.setRiskHigh(RISK_HIGH);
        query.setRiskMedium(RISK_MEDIUM);
        if (StringUtils.isBlank(query.getSortBy()) || !SORT_FIELDS.contains(query.getSortBy())) {
            query.setSortBy("lastEvent");
        }
        if (!"asc".equalsIgnoreCase(query.getSortOrder())) {
            query.setSortOrder("desc");
        } else {
            query.setSortOrder("asc");
        }
        if (query.getKeyword() != null && query.getKeyword().length() > 100) {
            throw new ServiceException("搜索关键词不能超过 100 字");
        }
        if (query.getMinScore() != null && query.getMaxScore() != null
            && query.getMinScore().compareTo(query.getMaxScore()) > 0) {
            throw new ServiceException("最低分不能大于最高分");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseObject(String json) {
        if (StringUtils.isBlank(json)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = JsonUtils.parseMap(json);
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    private void requireSource(String source) {
        if (StringUtils.isBlank(source)) {
            throw new ServiceException("source 不能为空");
        }
        if (source.length() > 32) {
            throw new ServiceException("source 长度不能超过 32");
        }
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
        return com.smartdock.profile.engine.ProfileTenantContext.require();
    }
}
