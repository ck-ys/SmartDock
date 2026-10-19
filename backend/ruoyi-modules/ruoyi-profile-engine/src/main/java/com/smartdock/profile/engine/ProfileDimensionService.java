package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.smartdock.profile.domain.ProfileScoreDimension;
import com.smartdock.profile.event.ProfileSubject;
import com.smartdock.profile.mapper.ProfileScoreDimensionMapper;
import com.smartdock.profile.mapper.ProfileScoreSnapshotMapper;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import com.smartdock.profile.query.ProfileDimensionBo;
import com.smartdock.profile.query.ProfileDimensionPreviewBo;
import com.smartdock.profile.spi.DimensionResult;
import com.smartdock.profile.spi.DimensionStrategy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 维度治理: 增删改查、能力清单、覆盖率、干跑预览。
 *
 * <p>维度是画像的“指标口径”; 已产生的快照不因维度修改而重写,
 * 修改后需要重新触发重算才能体现在新快照里。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileDimensionService {

    private static final Pattern DIM_CODE = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{0,63}$");
    private static final BigDecimal MAX_SCORE_MAX = BigDecimal.valueOf(1000);

    private final ProfileScoreDimensionMapper dimensionMapper;
    private final ProfileScoreSnapshotMapper snapshotMapper;
    private final ProfileSubjectMapper subjectMapper;
    private final DimensionEvaluator evaluator;
    private final List<DimensionStrategy> strategies;

    public List<ProfileScoreDimension> list(String source) {
        requireSource(source);
        return dimensionMapper.selectList(new LambdaQueryWrapper<ProfileScoreDimension>()
            .eq(ProfileScoreDimension::getSource, source)
            .orderByAsc(ProfileScoreDimension::getSortOrder)
            .orderByAsc(ProfileScoreDimension::getDimCode));
    }

    public ProfileScoreDimension get(Long dimensionId) {
        ProfileScoreDimension dim = dimensionMapper.selectById(dimensionId);
        if (dim == null) {
            throw new ServiceException("评分维度不存在");
        }
        return dim;
    }

    /**
     * 引擎/规则服务用的启用维度码(按排序)。
     */
    public List<String> enabledCodes(String source) {
        return list(source).stream()
            .filter(d -> "1".equals(d.getEnabled()))
            .map(ProfileScoreDimension::getDimCode)
            .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileScoreDimension create(ProfileDimensionBo bo) {
        validateBo(bo, true);
        Long duplicated = dimensionMapper.selectCount(new LambdaQueryWrapper<ProfileScoreDimension>()
            .eq(ProfileScoreDimension::getSource, bo.getSource())
            .eq(ProfileScoreDimension::getDimCode, bo.getDimCode()));
        if (duplicated != null && duplicated > 0) {
            throw new ServiceException("同一业务源下维度码已存在: " + bo.getDimCode());
        }
        ProfileScoreDimension dim = new ProfileScoreDimension();
        dim.setDimensionId(IdWorker.getId());
        dim.setTenantId(currentTenant());
        dim.setSource(bo.getSource());
        dim.setDimCode(bo.getDimCode().trim());
        dim.setDimName(bo.getDimName().trim());
        dim.setCalcMode(bo.getCalcMode().trim().toUpperCase());
        dim.setCalcConfig(JsonUtils.toJsonString(bo.getCalcConfig()));
        dim.setScoreMax(bo.getScoreMax());
        dim.setEnabled(normalizeEnabled(bo.getEnabled()));
        dim.setBuiltin("0");
        dim.setSortOrder(bo.getSortOrder() == null ? 0 : bo.getSortOrder());
        dim.setRemark(StringUtils.substring(bo.getRemark(), 0, 500));
        dimensionMapper.insertJsonb(dim);
        return dim;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileScoreDimension update(ProfileDimensionBo bo) {
        if (bo.getDimensionId() == null) {
            throw new ServiceException("dimensionId 不能为空");
        }
        ProfileScoreDimension existing = get(bo.getDimensionId());
        // source / dimCode / builtin 是身份字段, 修改会让历史快照对不上号
        bo.setSource(existing.getSource());
        bo.setDimCode(existing.getDimCode());
        validateBo(bo, false);
        existing.setDimName(bo.getDimName().trim());
        existing.setCalcMode(bo.getCalcMode().trim().toUpperCase());
        existing.setCalcConfig(JsonUtils.toJsonString(bo.getCalcConfig()));
        existing.setScoreMax(bo.getScoreMax());
        existing.setEnabled(normalizeEnabled(bo.getEnabled()));
        existing.setSortOrder(bo.getSortOrder() == null ? 0 : bo.getSortOrder());
        existing.setRemark(StringUtils.substring(bo.getRemark(), 0, 500));
        dimensionMapper.updateJsonb(existing);
        return existing;
    }

    /**
     * 删除 = 停用(保留历史快照); 内置维度不允许删除。
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean remove(Long dimensionId) {
        ProfileScoreDimension dim = get(dimensionId);
        if ("1".equals(dim.getBuiltin())) {
            throw new ServiceException("内置维度不可删除, 可在编辑中停用");
        }
        return dimensionMapper.updateEnabled(dim.getDimensionId(), dim.getTenantId(), "0") > 0;
    }

    public Map<String, Object> capabilities() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("calcModes", List.of("EVENT_AGG", "STRATEGY"));
        out.put("operators", new ArrayList<>(ProfileDimensionConfig.OPERATORS));
        out.put("scoreTypes", new ArrayList<>(ProfileDimensionConfig.SCORE_TYPES));
        out.put("directions", List.of("higher", "lower"));
        out.put("missingPolicies", new ArrayList<>(ProfileDimensionConfig.MISSING_POLICIES));
        out.put("windowTypes", new ArrayList<>(ProfileDimensionConfig.WINDOW_TYPES));
        out.put("propKeyPattern", ProfileDimensionConfig.PROP_KEY.pattern());
        out.put("scoreMaxRange", Map.of("min", 1, "max", MAX_SCORE_MAX.intValue()));
        List<Map<String, Object>> beans = new ArrayList<>();
        for (DimensionStrategy s : strategies) {
            beans.add(Map.of("source", s.source(), "dimCode", s.dimCode(),
                "bean", s.getClass().getSimpleName()));
        }
        out.put("strategyBeans", beans);
        return out;
    }

    public List<Map<String, Object>> coverage(String source) {
        requireSource(source);
        List<ProfileScoreDimension> dims = list(source);
        Long subjectCount = subjectMapper.selectCount(new LambdaQueryWrapper<ProfileSubject>()
            .eq(ProfileSubject::getSource, source));
        Long scoredCount = snapshotMapper.countLatestSnapshotSubjects(currentTenant(), source);
        Date lastCalcTime = snapshotMapper.selectLastCalcTime(currentTenant(), source);

        Map<String, Long> covered = new LinkedHashMap<>();
        for (Map<String, Object> row : snapshotMapper.selectDimensionCoverage(currentTenant(), source)) {
            Object code = row.get("dim_code");
            Object count = row.get("covered");
            if (code != null) {
                covered.put(String.valueOf(code), count instanceof Number n ? n.longValue() : 0L);
            }
        }
        long total = subjectCount == null ? 0 : subjectCount;
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProfileScoreDimension dim : dims) {
            long hit = covered.getOrDefault(dim.getDimCode(), 0L);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("dimensionId", dim.getDimensionId());
            item.put("dimCode", dim.getDimCode());
            item.put("dimName", dim.getDimName());
            item.put("enabled", dim.getEnabled());
            item.put("builtin", dim.getBuiltin());
            item.put("coveredSubjects", hit);
            item.put("subjectCount", total);
            item.put("scoredSubjects", scoredCount == null ? 0 : scoredCount);
            item.put("coverageRate", total <= 0 ? 0
                : BigDecimal.valueOf(hit * 100.0 / total).setScale(2, RoundingMode.HALF_UP));
            item.put("lastCalcTime", lastCalcTime);
            out.add(item);
        }
        return out;
    }

    /**
     * 干跑预览: 指定主体 + 维度配置, 不落快照。
     */
    public Map<String, Object> preview(ProfileDimensionPreviewBo bo) {
        if (StringUtils.isBlank(bo.getSubjectId())) {
            throw new ServiceException("subjectId 不能为空");
        }
        String subjectType = StringUtils.isBlank(bo.getSubjectType()) ? "user" : bo.getSubjectType();
        ProfileScoreDimension dim;
        if (bo.getDimensionId() != null) {
            dim = get(bo.getDimensionId());
        } else {
            requireSource(bo.getSource());
            if (StringUtils.isBlank(bo.getDimCode())) {
                throw new ServiceException("dimCode 不能为空");
            }
            dim = new ProfileScoreDimension();
            dim.setSource(bo.getSource());
            dim.setDimCode(bo.getDimCode());
            dim.setDimName(StringUtils.isBlank(bo.getDimName()) ? bo.getDimCode() : bo.getDimName());
            dim.setCalcMode(StringUtils.isBlank(bo.getCalcMode()) ? "EVENT_AGG" : bo.getCalcMode());
            dim.setCalcConfig(JsonUtils.toJsonString(bo.getCalcConfig()));
            dim.setScoreMax(bo.getScoreMax() == null ? BigDecimal.valueOf(100) : bo.getScoreMax());
        }
        if (!"EVENT_AGG".equalsIgnoreCase(dim.getCalcMode())) {
            throw new ServiceException("STRATEGY 维度由业务 Bean 计算, 暂不支持管理面干跑预览");
        }
        List<String> errors = ProfileDimensionConfig.validate(dim.getCalcMode(),
            JsonUtils.parseMap(dim.getCalcConfig()), dim.getScoreMax());
        if (!errors.isEmpty()) {
            throw new ServiceException("维度配置不合法: " + String.join("; ", errors));
        }
        DimensionResult r = evaluator.evaluate(dim.getSource(), subjectType, bo.getSubjectId(), dim);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dimCode", dim.getDimCode());
        out.put("dimName", dim.getDimName());
        out.put("scoreMax", dim.getScoreMax());
        out.put("skipped", r == null || r.isSkipped());
        if (r != null && !r.isSkipped()) {
            out.put("score", r.getScore());
            out.put("normalizedScore", r.getScore().multiply(BigDecimal.valueOf(100))
                .divide(dim.getScoreMax(), 2, RoundingMode.HALF_UP));
            out.put("metric", r.getMetric());
            out.put("sampleCount", r.getSampleCount());
            out.put("tags", r.getTags());
        }
        return out;
    }

    private void validateBo(ProfileDimensionBo bo, boolean requireSource) {
        if (requireSource) {
            requireSource(bo.getSource());
        }
        if (StringUtils.isBlank(bo.getDimCode()) || !DIM_CODE.matcher(bo.getDimCode().trim()).matches()) {
            throw new ServiceException("dimCode 只允许字母开头的字母/数字/下划线, 最长 64");
        }
        if (StringUtils.isBlank(bo.getDimName()) || bo.getDimName().trim().length() > 100) {
            throw new ServiceException("dimName 不能为空且不能超过 100 字");
        }
        if (StringUtils.isBlank(bo.getCalcMode())) {
            throw new ServiceException("calcMode 不能为空");
        }
        if (bo.getScoreMax() == null || bo.getScoreMax().signum() <= 0
            || bo.getScoreMax().compareTo(MAX_SCORE_MAX) > 0) {
            throw new ServiceException("scoreMax 必须在 1~1000 之间");
        }
        List<String> errors = ProfileDimensionConfig.validate(bo.getCalcMode(), bo.getCalcConfig(), bo.getScoreMax());
        if (!errors.isEmpty()) {
            throw new ServiceException("维度配置不合法: " + String.join("; ", errors));
        }
        if ("STRATEGY".equalsIgnoreCase(bo.getCalcMode())) {
            String bean = bo.getCalcConfig() == null ? null : String.valueOf(bo.getCalcConfig().get("strategyBean"));
            boolean matched = strategies.stream().anyMatch(s -> s.source().equals(bo.getSource())
                && s.dimCode().equals(bo.getDimCode()))
                || (StringUtils.isNotBlank(bean) && strategies.stream()
                .anyMatch(s -> s.getClass().getSimpleName().equals(bean) || s.getClass().getName().equals(bean)));
            if (!matched) {
                throw new ServiceException("找不到可用的 STRATEGY Bean: " + (StringUtils.isBlank(bean) ? bo.getDimCode() : bean));
            }
        }
    }

    private String normalizeEnabled(String enabled) {
        return "0".equals(enabled) ? "0" : "1";
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
