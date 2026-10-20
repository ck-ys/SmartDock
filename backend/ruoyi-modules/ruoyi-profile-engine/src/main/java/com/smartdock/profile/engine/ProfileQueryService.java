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

    public TableDataInfo<ProfileSubjectRow> subjectPage(ProfileSubjectQuery query, PageQuery pageQuery) {
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
        return TableDataInfo.build(result);
    }

    public ProfileOverview overview(String source) {
        requireSource(source);
        ProfileOverview overview = subjectMapper.selectOverview(currentTenant(), source);
        if (overview == null) {
            overview = new ProfileOverview();
            overview.setSubjectCount(0L);
            overview.setScoredCount(0L);
            overview.setAvgScore(BigDecimal.ZERO);
            overview.setLowRisk(0L);
            overview.setMediumRisk(0L);
            overview.setHighRisk(0L);
        }
        overview.setLastCalcTime(snapshotMapper.selectLastCalcTime(currentTenant(), source));
        return overview;
    }

    public List<ProfileRankingRow> ranking(String source, String subjectType, String dimension, int limit) {
        requireSource(source);
        int capped = Math.min(Math.max(limit <= 0 ? 10 : limit, 1), 100);
        String dim = StringUtils.isBlank(dimension) ? null : dimension.trim();
        if (dim != null) {
            boolean known = dimensionService.list(source).stream()
                .anyMatch(d -> dim.equals(d.getDimCode()));
            if (!known) {
                throw new ServiceException("排行维度不存在: " + dim);
            }
        }
        List<ProfileRankingRow> rows = subjectMapper.selectRanking(currentTenant(), source,
            StringUtils.isBlank(subjectType) ? null : subjectType.trim(), dim, capped);
        int rank = 1;
        for (ProfileRankingRow row : rows) {
            row.setRank(rank++);
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
        List<Map<String, Object>> dimensions = buildDimensionRows(source, detailMap);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subject", subject);
        out.put("subjectProperties", parseObject(subject == null ? null : subject.getProperties()));
        out.put("latest", latest);
        out.put("history", history);
        out.put("dimensions", dimensions);
        out.put("rule", ruleService.current(source));
        out.put("rank", rankOf(source, type, latest));
        out.put("risk", riskOf(latest == null ? null : latest.getTotalScore()));
        out.put("trend", trend(history));
        out.put("dataQuality", dataQuality(dimensions));
        return out;
    }

    private List<Map<String, Object>> buildDimensionRows(String source, Map<String, Object> detailMap) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<ProfileScoreDimension> configured = dimensionService.list(source);
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
        if (total.compareTo(BigDecimal.valueOf(75)) >= 0) {
            out.put("level", "LOW");
            out.put("label", "低风险");
            out.put("color", "success");
        } else if (total.compareTo(BigDecimal.valueOf(60)) >= 0) {
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

    private void normalizeQuery(ProfileSubjectQuery query) {
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
        return "000000";
    }
}
