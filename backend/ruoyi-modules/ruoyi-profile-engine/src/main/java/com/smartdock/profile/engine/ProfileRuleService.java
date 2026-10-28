package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.smartdock.profile.domain.ProfileScoreRule;
import com.smartdock.profile.mapper.ProfileScoreRuleMapper;
import com.smartdock.profile.mapper.ProfileScoreSnapshotMapper;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import com.smartdock.profile.query.ProfileRuleBo;
import com.smartdock.profile.query.ProfileRulePublishBo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 权重规则治理: 草稿 → 校验 → 发布 → 归档, 版本不可原地修改。
 *
 * <p>发布时先归档旧 PUBLISHED 再落新版本; DB 部分唯一索引兜底并发。
 * 草稿允许暂时不满足“权重合计=1”, 发布前必须通过校验。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileRuleService {

    public static final String DRAFT = "DRAFT";
    public static final String PUBLISHED = "PUBLISHED";
    public static final String ARCHIVED = "ARCHIVED";

    private final ProfileScoreRuleMapper ruleMapper;
    private final ProfileScoreSnapshotMapper snapshotMapper;
    private final ProfileSubjectMapper subjectMapper;
    private final ProfileDimensionService dimensionService;

    public ProfileScoreRule current(String source, String subjectType) {
        requireSource(source);
        return ruleMapper.selectList(new LambdaQueryWrapper<ProfileScoreRule>()
                .eq(ProfileScoreRule::getSource, source)
                .eq(ProfileScoreRule::getSubjectType, SubjectTypes.normalize(subjectType))
                .eq(ProfileScoreRule::getStatus, PUBLISHED)
                .orderByDesc(ProfileScoreRule::getPublishedAt)
                .orderByDesc(ProfileScoreRule::getCreateTime)
                .last("LIMIT 1"))
            .stream().findFirst().orElse(null);
    }

    public List<ProfileScoreRule> history(String source, String subjectType) {
        requireSource(source);
        return ruleMapper.selectList(new LambdaQueryWrapper<ProfileScoreRule>()
            .eq(ProfileScoreRule::getSource, source)
            .eq(ProfileScoreRule::getSubjectType, SubjectTypes.normalize(subjectType))
            .orderByDesc(ProfileScoreRule::getCreateTime)
            .orderByDesc(ProfileScoreRule::getRuleId));
    }

    public ProfileScoreRule get(Long ruleId) {
        ProfileScoreRule rule = ruleMapper.selectById(ruleId);
        if (rule == null) {
            throw new ServiceException("评分规则不存在");
        }
        return rule;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileScoreRule createDraft(ProfileRuleBo bo) {
        requireSource(bo.getSource());
        String subjectType = SubjectTypes.normalize(bo.getSubjectType());
        ProfileScoreRule open = queryOpenDraft(bo.getSource(), subjectType);
        if (open != null) {
            return open;
        }
        List<String> codes = dimensionService.enabledCodes(bo.getSource(), subjectType);
        if (codes.isEmpty()) {
            throw new ServiceException("该业务源+主体类型没有启用的评分维度, 请先配置维度");
        }
        ProfileScoreRule sourceRule = bo.getSourceRuleId() == null ? current(bo.getSource(), subjectType)
            : get(bo.getSourceRuleId());
        if (sourceRule != null && !bo.getSource().equals(sourceRule.getSource())) {
            throw new ServiceException("复制来源规则不属于当前业务源");
        }
        if (sourceRule != null && DRAFT.equals(sourceRule.getStatus())) {
            throw new ServiceException("只能从已发布/已归档版本复制草稿");
        }
        Map<String, Double> weights;
        if (sourceRule == null) {
            weights = ProfileRuleWeights.balanced(codes);
        } else {
            weights = parseWeights(sourceRule.getWeights());
            if (!ProfileRuleWeights.validate(weights, codes).isEmpty()) {
                weights = ProfileRuleWeights.balanced(codes);
            }
        }

        ProfileScoreRule draft = new ProfileScoreRule();
        draft.setRuleId(IdWorker.getId());
        draft.setTenantId(currentTenant());
        draft.setSource(bo.getSource());
        draft.setSubjectType(subjectType);
        draft.setRuleVersion("draft-" + draft.getRuleId());
        draft.setRuleName(StringUtils.isBlank(bo.getRuleName())
            ? (sourceRule == null || StringUtils.isBlank(sourceRule.getRuleName())
                ? "画像权重草稿" : sourceRule.getRuleName() + " 草稿")
            : bo.getRuleName().trim());
        draft.setStatus(DRAFT);
        draft.setWeights(toJson(weights));
        draft.setRemark(bo.getRemark());
        draft.setBaseRuleVersion(sourceRule == null ? null : sourceRule.getRuleVersion());
        draft.setRevision(0);
        draft.setEnabled("0");
        draft.setCreateBy(currentUser());
        try {
            ruleMapper.insertJsonb(draft);
        } catch (DuplicateKeyException concurrent) {
            ProfileScoreRule existing = queryOpenDraft(bo.getSource(), subjectType);
            if (existing != null) {
                return existing;
            }
            throw new ServiceException("已有评分规则草稿, 请刷新后继续编辑");
        }
        return draft;
    }

    /**
     * F 片三审回修(dspro P1): 场景模板套用专用——已有开放草稿时直接抛错而不是静默复用。
     * createDraft 的复用语义对规则页无害(页面随后只读加载), 但并发套模板会把 A 的草稿
     * 返回给 B, B 拿 A 的 revision 整份覆盖权重还回执「已生成」。因此这里不复用 createDraft,
     * 创建体内联; 插入撞 uk_profile_rule_draft 时 DuplicateKeyException 原样冒泡——PG 事务
     * 此刻已 aborted, 调用方须直接抛错让整单回滚, 不许再发后续 SQL。
     */
    @Transactional(rollbackFor = Exception.class)
    public ProfileScoreRule createDraftStrict(ProfileRuleBo bo) {
        requireSource(bo.getSource());
        String subjectType = SubjectTypes.normalize(bo.getSubjectType());
        if (queryOpenDraft(bo.getSource(), subjectType) != null) {
            throw new ServiceException("已有未发布的权重草稿, 请先处理后再套用模板");
        }
        List<String> codes = dimensionService.enabledCodes(bo.getSource(), subjectType);
        if (codes.isEmpty()) {
            throw new ServiceException("该业务源+主体类型没有启用的评分维度, 请先配置维度");
        }
        ProfileScoreRule sourceRule = current(bo.getSource(), subjectType);
        Map<String, Double> weights = sourceRule == null
            ? ProfileRuleWeights.balanced(codes)
            : parseWeights(sourceRule.getWeights());
        if (sourceRule != null && !ProfileRuleWeights.validate(weights, codes).isEmpty()) {
            weights = ProfileRuleWeights.balanced(codes);
        }
        ProfileScoreRule draft = new ProfileScoreRule();
        draft.setRuleId(IdWorker.getId());
        draft.setTenantId(currentTenant());
        draft.setSource(bo.getSource());
        draft.setSubjectType(subjectType);
        draft.setRuleVersion("draft-" + draft.getRuleId());
        draft.setRuleName(StringUtils.isBlank(bo.getRuleName())
            ? "画像权重草稿" : bo.getRuleName().trim());
        draft.setStatus(DRAFT);
        draft.setWeights(toJson(weights));
        draft.setRemark(bo.getRemark());
        draft.setBaseRuleVersion(sourceRule == null ? null : sourceRule.getRuleVersion());
        draft.setRevision(0);
        draft.setEnabled("0");
        draft.setCreateBy(currentUser());
        ruleMapper.insertJsonb(draft);
        return draft;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileScoreRule updateDraft(ProfileRuleBo bo) {
        if (bo.getRuleId() == null) {
            throw new ServiceException("ruleId 不能为空");
        }
        ProfileScoreRule draft = get(bo.getRuleId());
        assertDraft(draft);
        if (bo.getRevision() == null || !bo.getRevision().equals(draft.getRevision())) {
            throw new ServiceException("草稿已被其他人修改, 请刷新后重试");
        }
        if (bo.getWeights() != null && !bo.getWeights().isEmpty()) {
            validateDraftWeights(bo.getWeights(), draft.getSource(), draft.getSubjectType());
            draft.setWeights(toJson(bo.getWeights()));
        }
        if (bo.getRuleName() != null) {
            if (bo.getRuleName().isBlank() || bo.getRuleName().length() > 100) {
                throw new ServiceException("规则名称不能为空且不能超过 100 字");
            }
            draft.setRuleName(bo.getRuleName().trim());
        }
        if (bo.getRemark() != null) {
            draft.setRemark(StringUtils.substring(bo.getRemark(), 0, 500));
        }
        draft.setRevision(draft.getRevision() == null ? 1 : draft.getRevision() + 1);
        draft.setUpdateBy(currentUser());
        ruleMapper.updateJsonb(draft);
        return draft;
    }

    public Map<String, Object> validateDraft(Long ruleId) {
        ProfileScoreRule rule = get(ruleId);
        List<String> codes = dimensionService.enabledCodes(rule.getSource(), rule.getSubjectType());
        Map<String, Double> weights = parseWeights(rule.getWeights());
        List<String> errors = ProfileRuleWeights.validate(weights, codes);
        List<String> warnings = new ArrayList<>();
        if (codes.size() < 2) {
            warnings.add("当前启用维度少于 2 个, 画像区分度可能不足");
        }
        weights.forEach((code, value) -> {
            if (value != null && value == 0) {
                warnings.add("维度 " + code + " 权重为 0, 将不参与总分");
            }
        });
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("valid", errors.isEmpty());
        out.put("errors", errors);
        out.put("warnings", warnings);
        out.put("ruleId", rule.getRuleId());
        out.put("ruleVersion", rule.getRuleVersion());
        out.put("status", rule.getStatus());
        out.put("dimensionCodes", codes);
        out.put("weights", weights);
        out.put("weightSum", round(ProfileRuleWeights.sum(weights)));
        out.put("revision", rule.getRevision());
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileScoreRule publish(Long ruleId, ProfileRulePublishBo bo) {
        ProfileScoreRule draft = get(ruleId);
        assertDraft(draft);
        if (bo == null || StringUtils.isBlank(bo.getVersion())) {
            throw new ServiceException("发布版本不能为空");
        }
        String version = bo.getVersion().trim();
        if (version.length() > 32) {
            throw new ServiceException("发布版本不能超过 32 个字符");
        }
        if (bo.getNote() != null && bo.getNote().length() > 500) {
            throw new ServiceException("版本说明不能超过 500 个字符");
        }
        if (bo.getRevision() != null && !bo.getRevision().equals(draft.getRevision())) {
            throw new ServiceException("草稿已被其他人修改, 请刷新后重试");
        }
        List<String> codes = dimensionService.enabledCodes(draft.getSource(), draft.getSubjectType());
        Map<String, Double> weights = parseWeights(draft.getWeights());
        List<String> errors = ProfileRuleWeights.validate(weights, codes);
        if (!errors.isEmpty()) {
            throw new ServiceException("权重校验失败: " + String.join("; ", errors));
        }
        Long duplicated = ruleMapper.selectCount(new LambdaQueryWrapper<ProfileScoreRule>()
            .eq(ProfileScoreRule::getSource, draft.getSource())
            .eq(ProfileScoreRule::getSubjectType, draft.getSubjectType())
            .eq(ProfileScoreRule::getRuleVersion, version)
            .ne(ProfileScoreRule::getRuleId, draft.getRuleId()));
        if (duplicated != null && duplicated > 0) {
            throw new ServiceException("该业务源+主体类型已存在同名版本: " + version);
        }

        ruleMapper.archivePublished(draft.getTenantId(), draft.getSource(), draft.getSubjectType());
        draft.setRuleVersion(version);
        draft.setStatus(PUBLISHED);
        draft.setEnabled("1");
        draft.setPublishedAt(new Date());
        draft.setPublishedBy(currentUser());
        draft.setRevision(draft.getRevision() == null ? 1 : draft.getRevision() + 1);
        if (StringUtils.isNotBlank(bo.getNote())) {
            draft.setRemark(StringUtils.substring(bo.getNote(), 0, 500));
        }
        draft.setUpdateBy(currentUser());
        try {
            ruleMapper.updateJsonb(draft);
        } catch (DuplicateKeyException concurrent) {
            throw new ServiceException("已有其他规则发布, 请刷新后重试");
        }
        log.info("[Profile] rule published source={} version={} by={}", draft.getSource(), version, currentUser());
        return draft;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileScoreRule archive(Long ruleId) {
        ProfileScoreRule rule = get(ruleId);
        if (!PUBLISHED.equals(rule.getStatus())) {
            throw new ServiceException("只有已发布版本可以归档");
        }
        rule.setStatus(ARCHIVED);
        rule.setEnabled("0");
        rule.setUpdateBy(currentUser());
        ruleMapper.updateJsonb(rule);
        return rule;
    }

    public Map<String, Object> diff(Long ruleId) {
        ProfileScoreRule draft = get(ruleId);
        ProfileScoreRule base = StringUtils.isBlank(draft.getBaseRuleVersion())
            ? current(draft.getSource(), draft.getSubjectType())
            : findByVersion(draft.getSource(), draft.getSubjectType(), draft.getBaseRuleVersion());
        Map<String, Double> before = base == null ? Map.of() : parseWeights(base.getWeights());
        Map<String, Double> after = parseWeights(draft.getWeights());
        Set<String> keys = new TreeSet<>();
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        Map<String, Object> changes = new LinkedHashMap<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        for (String key : keys) {
            Double b = before.get(key);
            Double a = after.get(key);
            if (b == null) {
                added.add(key);
            } else if (a == null) {
                removed.add(key);
            }
            if (b == null || a == null || Double.compare(b, a) != 0) {
                Map<String, Object> change = new LinkedHashMap<>();
                change.put("before", b);
                change.put("after", a);
                change.put("delta", a == null || b == null ? null : round(a - b));
                changes.put(key, change);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("baseRuleId", base == null ? null : base.getRuleId());
        out.put("baseVersion", base == null ? null : base.getRuleVersion());
        out.put("draftRuleId", draft.getRuleId());
        out.put("draftVersion", draft.getRuleVersion());
        out.put("changes", changes);
        out.put("added", added);
        out.put("removed", removed);
        return out;
    }

    public Map<String, Object> impact(Long ruleId) {
        ProfileScoreRule draft = get(ruleId);
        ProfileScoreRule published = current(draft.getSource(), draft.getSubjectType());
        Map<String, Double> draftWeights = parseWeights(draft.getWeights());
        Map<String, Double> currentWeights = published == null ? Map.of() : parseWeights(published.getWeights());
        List<String> changed = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>(draftWeights.keySet());
        keys.addAll(currentWeights.keySet());
        for (String key : keys) {
            Double a = draftWeights.get(key);
            Double b = currentWeights.get(key);
            if (a == null || b == null || Double.compare(a, b) != 0) {
                changed.add(key);
            }
        }
        Long subjects = subjectMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.smartdock.profile.event.ProfileSubject>()
            .eq(com.smartdock.profile.event.ProfileSubject::getSource, draft.getSource())
            .eq(com.smartdock.profile.event.ProfileSubject::getSubjectType, draft.getSubjectType()));
        Long snapshots = snapshotMapper.countLatestSnapshotSubjects(draft.getTenantId(), draft.getSource(), draft.getSubjectType());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("draftRuleId", draft.getRuleId());
        out.put("publishedVersion", published == null ? null : published.getRuleVersion());
        out.put("draftVersion", draft.getRuleVersion());
        out.put("subjectCount", subjects == null ? 0 : subjects);
        out.put("scoredSubjectCount", snapshots == null ? 0 : snapshots);
        out.put("changedDimensions", changed);
        out.put("estimatedRecalcSubjects", subjects == null ? 0 : subjects);
        out.put("requiresRecalc", !changed.isEmpty());
        return out;
    }

    public List<Map<String, Object>> templates(String source, String subjectType) {
        requireSource(source);
        List<String> codes = dimensionService.enabledCodes(source, subjectType);
        if (codes.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(template("balanced", "均衡模板", "所有启用维度等权", ProfileRuleWeights.balanced(codes)));

        ProfileScoreRule current = current(source, subjectType);
        if (current != null) {
            Map<String, Double> weights = parseWeights(current.getWeights());
            if (ProfileRuleWeights.validate(weights, codes).isEmpty()) {
                out.add(template("current", "沿用当前版本",
                    "复制当前生效版本 " + current.getRuleVersion(), weights));
            }
        }
        List<String> qualityBoost = codes.stream()
            .filter(c -> "quality".equals(c) || "negative".equals(c)).toList();
        if (!qualityBoost.isEmpty() && qualityBoost.size() < codes.size()) {
            Map<String, Double> boosted = boost(ProfileRuleWeights.balanced(codes), qualityBoost, 0.10);
            out.add(template("quality_first", "质量优先",
                "提高质量/负面维度权重, 适合强调交付质量",
                ProfileRuleWeights.validate(boosted, codes).isEmpty() ? boosted : ProfileRuleWeights.balanced(codes)));
        }
        List<String> responseBoost = codes.stream()
            .filter(c -> "response".equals(c) || "activity".equals(c)).toList();
        if (!responseBoost.isEmpty() && responseBoost.size() < codes.size()) {
            Map<String, Double> boosted = boost(ProfileRuleWeights.balanced(codes), responseBoost, 0.10);
            out.add(template("response_first", "响应优先",
                "提高响应/活跃维度权重, 适合强调及时性",
                ProfileRuleWeights.validate(boosted, codes).isEmpty() ? boosted : ProfileRuleWeights.balanced(codes)));
        }
        return out;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteDraft(Long ruleId) {
        ProfileScoreRule rule = get(ruleId);
        assertDraft(rule);
        return ruleMapper.deleteById(ruleId) > 0;
    }

    /**
     * 供引擎/其它服务复用: 权重 JSON → map。
     */
    public Map<String, Double> parseWeights(String json) {
        Map<String, Double> out = new LinkedHashMap<>();
        if (StringUtils.isBlank(json)) {
            return out;
        }
        Map<String, Object> raw;
        try {
            raw = JsonUtils.parseMap(json);
        } catch (Exception e) {
            return out;
        }
        if (raw == null) {
            return out;
        }
        raw.forEach((key, value) -> {
            if (key == null) {
                return;
            }
            if (value instanceof Number n) {
                out.put(key, n.doubleValue());
            } else if (value != null) {
                try {
                    out.put(key, Double.parseDouble(String.valueOf(value)));
                } catch (NumberFormatException ignored) {
                    // 坏值跳过; 发布校验会报缺失
                }
            }
        });
        return out;
    }

    private String toJson(Map<String, Double> weights) {
        return JsonUtils.toJsonString(weights == null ? Map.of() : weights);
    }

    private void validateDraftWeights(Map<String, Double> weights, String source, String subjectType) {
        Set<String> codes = new LinkedHashSet<>(dimensionService.enabledCodes(source, subjectType));
        for (Map.Entry<String, Double> entry : weights.entrySet()) {
            if (!codes.contains(entry.getKey())) {
                throw new ServiceException("权重引用了未启用维度: " + entry.getKey());
            }
            Double value = entry.getValue();
            if (value == null || value.isNaN() || value.isInfinite() || value < 0 || value > 1) {
                throw new ServiceException("维度 " + entry.getKey() + " 的权重必须在 0~1 之间");
            }
        }
    }

    private ProfileScoreRule queryOpenDraft(String source, String subjectType) {
        return ruleMapper.selectList(new LambdaQueryWrapper<ProfileScoreRule>()
                .eq(ProfileScoreRule::getSource, source)
                .eq(ProfileScoreRule::getSubjectType, SubjectTypes.normalize(subjectType))
                .eq(ProfileScoreRule::getStatus, DRAFT)
                .last("LIMIT 1"))
            .stream().findFirst().orElse(null);
    }

    private ProfileScoreRule findByVersion(String source, String subjectType, String version) {
        return ruleMapper.selectList(new LambdaQueryWrapper<ProfileScoreRule>()
                .eq(ProfileScoreRule::getSource, source)
                .eq(ProfileScoreRule::getSubjectType, SubjectTypes.normalize(subjectType))
                .eq(ProfileScoreRule::getRuleVersion, version)
                .last("LIMIT 1"))
            .stream().findFirst().orElse(null);
    }

    private void assertDraft(ProfileScoreRule rule) {
        if (!DRAFT.equals(rule.getStatus())) {
            throw new ServiceException("已发布/已归档版本不可修改, 请先创建草稿");
        }
    }

    private Map<String, Object> template(String code, String name, String description,
                                         Map<String, Double> weights) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("name", name);
        out.put("description", description);
        out.put("weights", weights);
        out.put("weightSum", round(ProfileRuleWeights.sum(weights)));
        return out;
    }

    /**
     * 提升若干维度权重后重新归一化到 1。
     */
    private Map<String, Double> boost(Map<String, Double> base, List<String> boostCodes, double amount) {
        Map<String, Double> out = new LinkedHashMap<>(base);
        for (String code : boostCodes) {
            if (out.containsKey(code)) {
                out.put(code, Math.min(1.0, out.get(code) + amount));
            }
        }
        double sum = ProfileRuleWeights.sum(out);
        if (sum <= 0) {
            return base;
        }
        Map<String, Double> normalized = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(out.keySet());
        double used = 0;
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            double value = i == keys.size() - 1
                ? round(1.0 - used)
                : round(out.get(key) / sum);
            normalized.put(key, value);
            used += value;
        }
        return normalized;
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private void requireSource(String source) {
        if (StringUtils.isBlank(source)) {
            throw new ServiceException("source 不能为空");
        }
        if (source.length() > 32) {
            throw new ServiceException("source 长度不能超过 32");
        }
    }

    private Long currentUser() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
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
