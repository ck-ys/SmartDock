package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.analytics.engine.AnalyticsService;
import com.smartdock.analytics.query.AnalyticsFunnelBo;
import com.smartdock.profile.domain.ProfileScoreRule;
import com.smartdock.profile.domain.ProfileScenarioTemplate;
import com.smartdock.profile.mapper.ProfileScoreRuleMapper;
import com.smartdock.profile.mapper.ProfileScenarioTemplateMapper;
import com.smartdock.profile.model.ProfileSegmentRule;
import com.smartdock.profile.model.ScenarioTemplateDef;
import com.smartdock.profile.event.ProfileSources;
import com.smartdock.profile.query.ProfileDimensionBo;
import com.smartdock.profile.query.ProfileRuleBo;
import com.smartdock.profile.query.ProfileSegmentBo;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 场景模板库(F 片): 内置 7 套 + 租户导入的「我的模板」。
 *
 * <p>套用语义(诚实回执): 维度按编码跳过已存在、分群/漏斗同理——绝不覆盖用户调过的配置;
 * 权重只生成「草稿」不自动发布, 打分行为在用户显式发布前分毫不动;
 * 已有未处理草稿时跳过权重步骤并在回执里说明, 不碰用户手上的活。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileScenarioService {

    /** 与维度/分群/漏斗编码同规则: 字母开头的字母/数字/下划线。 */
    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-zA-Z][a-zA-Z0-9_]{0,63}$");

    private static final int MAX_DIMENSIONS = 20;
    private static final int MAX_SEGMENTS = 20;
    private static final int MAX_FUNNELS = 10;
    /** 漏斗页落库校验要求 ≥2 步、窗口 1~90(AnalyticsService clampWindow), 模板侧对齐防「导入过套用炸」。 */
    private static final int MIN_FUNNEL_STEPS = 2;
    private static final int MAX_STEPS = 10;
    private static final int MAX_FUNNEL_WINDOW_DAYS = 90;
    private static final int MAX_DEFINITION_CHARS = 256 * 1024;

    private final ProfileScenarioTemplateMapper templateMapper;
    private final ProfileScoreRuleMapper ruleMapper;
    private final ProfileDimensionService dimensionService;
    private final ProfileRuleService ruleService;
    private final ProfileSegmentService segmentService;
    private final AnalyticsService analyticsService;

    /**
     * 模板目录: 推荐场景(内置) + 我的模板(本租户导入)。
     */
    public Map<String, Object> catalog() {
        List<Map<String, Object>> builtin = new ArrayList<>();
        for (ScenarioTemplateDef def : ProfileScenarioTemplates.builtin()) {
            builtin.add(summary("builtin", def, null));
        }
        List<Map<String, Object>> saved = new ArrayList<>();
        for (ProfileScenarioTemplate row : templateMapper.selectList(
            new LambdaQueryWrapper<ProfileScenarioTemplate>()
                .eq(ProfileScenarioTemplate::getTenantId, currentTenant())
                .eq(ProfileScenarioTemplate::getDelFlag, "0")
                .orderByDesc(ProfileScenarioTemplate::getTemplateId))) {
            ScenarioTemplateDef def = parseDefinition(row.getDefinition());
            if (def == null) {
                // 三审回修(glm53 P2): 坏定义不静默消失, 至少留痕可查
                log.warn("scenario template {} ({}) definition broken, skipped in catalog",
                    row.getTemplateId(), row.getTemplateCode());
                continue;
            }
            saved.add(summary("saved", def, row.getTemplateId()));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("builtin", builtin);
        out.put("saved", saved);
        return out;
    }

    /**
     * 导出模板定义(内置按 code, 导入按 templateId)——前端拿到后存成 .json 分享。
     */
    public ScenarioTemplateDef export(String code, Long templateId) {
        if (templateId != null) {
            return requireSaved(templateId).def();
        }
        if (StringUtils.isBlank(code)) {
            throw new ServiceException("code 与 templateId 至少传一个");
        }
        ScenarioTemplateDef def = ProfileScenarioTemplates.byCode(code.trim());
        if (def == null) {
            throw new ServiceException("内置模板不存在: " + code);
        }
        return def;
    }

    /**
     * 导入模板 JSON: 校验通过后落「我的模板」, 与内置模板共用编码命名空间(撞内置编码拒绝)。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> importTemplate(ScenarioTemplateDef input) {
        if (input == null) {
            throw new ServiceException("模板内容不能为空");
        }
        normalizeAndValidate(input);
        // 三审回修(dsflash P2): 「推荐默认」是内置模板专属标记, 导入件不得伪造
        input.setRecommended(false);
        String code = input.getCode().trim();
        if (ProfileScenarioTemplates.byCode(code) != null) {
            throw new ServiceException("编码 " + code + " 与内置模板冲突, 请换一个编码");
        }
        Long duplicated = templateMapper.selectCount(new LambdaQueryWrapper<ProfileScenarioTemplate>()
            .eq(ProfileScenarioTemplate::getTenantId, currentTenant())
            .eq(ProfileScenarioTemplate::getTemplateCode, code)
            .eq(ProfileScenarioTemplate::getDelFlag, "0"));
        if (duplicated != null && duplicated > 0) {
            throw new ServiceException("我的模板里已有编码 " + code + ", 不允许重复导入");
        }
        String definition = JsonUtils.toJsonString(input);
        // 三审回修(dspro P2): 无字节上限的 definition 直接进 jsonb 是 DoS 面
        if (definition.length() > MAX_DEFINITION_CHARS) {
            throw new ServiceException("模板定义过大(超过 " + MAX_DEFINITION_CHARS / 1024 + "KB), 请精简后重试");
        }
        ProfileScenarioTemplate row = new ProfileScenarioTemplate();
        row.setTemplateId(com.baomidou.mybatisplus.core.toolkit.IdWorker.getId());
        row.setTenantId(currentTenant());
        row.setTemplateCode(code);
        row.setTemplateName(input.getName().trim());
        row.setScenario(StringUtils.substring(input.getScenario(), 0, 50));
        row.setDescription(StringUtils.substring(input.getDescription(), 0, 500));
        row.setDefinition(definition);
        row.setBuiltin("0");
        row.setCreateBy(currentUser());
        try {
            templateMapper.insertJsonb(row);
        } catch (DuplicateKeyException concurrent) {
            // 并发双写被 uk_profile_scenario_template_code 兜住; catch 后不再发 SQL, 直接退出
            throw new ServiceException("我的模板里已有编码 " + code + ", 不允许重复导入");
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templateId", row.getTemplateId());
        out.put("code", code);
        out.put("name", row.getTemplateName());
        return out;
    }

    /** 删除「我的模板」(内置是代码常量, 删不掉); 重复删除返回 false 不报错(幂等)。 */
    @Transactional(rollbackFor = Exception.class)
    public boolean delete(Long templateId) {
        ProfileScenarioTemplate row = templateMapper.selectById(templateId);
        if (row == null || !"0".equals(String.valueOf(row.getDelFlag()))
            || !currentTenant().equals(row.getTenantId())) {
            return false;
        }
        return templateMapper.softDelete(templateId, currentTenant(), currentUser()) > 0;
    }

    /**
     * 套用模板: 维度/分群/漏斗按编码跳过已存在, 权重生成草稿(已有草稿则跳过并说明)。
     * 全程一个事务, 任何一步失败整体回滚。
     */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> apply(String code, Long templateId, String subjectType) {
        ScenarioTemplateDef def;
        if (templateId != null) {
            def = requireSaved(templateId).def();
        } else if (StringUtils.isNotBlank(code)) {
            def = ProfileScenarioTemplates.byCode(code.trim());
            if (def == null) {
                throw new ServiceException("内置模板不存在: " + code);
            }
        } else {
            throw new ServiceException("code 与 templateId 至少传一个");
        }
        normalizeAndValidate(def);
        String source = ProfileSources.UNIFIED;
        String st = SubjectTypes.normalize(subjectType);

        List<String> dimsCreated = new ArrayList<>();
        List<String> dimsSkipped = new ArrayList<>();
        Set<String> existingDimCodes = new HashSet<>();
        for (var d : dimensionService.list(source, st)) {
            existingDimCodes.add(d.getDimCode());
        }
        for (ScenarioTemplateDef.DimensionDef dim : def.getDimensions()) {
            if (!existingDimCodes.add(dim.getDimCode())) {
                dimsSkipped.add(dim.getDimCode());
                continue;
            }
            ProfileDimensionBo bo = new ProfileDimensionBo();
            bo.setSource(source);
            bo.setSubjectType(st);
            bo.setDimCode(dim.getDimCode());
            bo.setDimName(dim.getDimName());
            bo.setCalcMode("EVENT_AGG");
            bo.setCalcConfig(dim.getCalcConfig());
            bo.setScoreMax(dim.getScoreMax() == null ? BigDecimal.valueOf(100) : dim.getScoreMax());
            bo.setEnabled("1");
            bo.setSortOrder(dim.getSortOrder() == null ? 0 : dim.getSortOrder());
            bo.setRemark("场景模板「" + def.getName() + "」预填");
            try {
                dimensionService.create(bo);
            } catch (DuplicateKeyException concurrent) {
                // 批末回修(双席互证 dspro P2-3/glm53 P2-2): check-then-act 与并发套用之间的窗口
                // 撞唯一键, PG 事务已中止——抛人话并整单回滚, 重试即得「全跳过」回执
                throw new ServiceException("另一管理员正在套用同一模板或已建同编码维度, 请刷新后重试");
            }
            dimsCreated.add(dim.getDimCode());
        }

        String draftNote;
        Long draftRuleId;
        ProfileScoreRule open = ruleMapper.selectOne(new LambdaQueryWrapper<ProfileScoreRule>()
            .eq(ProfileScoreRule::getSource, source)
            .eq(ProfileScoreRule::getSubjectType, st)
            .eq(ProfileScoreRule::getStatus, ProfileRuleService.DRAFT)
            .last("LIMIT 1"));
        if (open != null) {
            // 批末回修(dspro P1-2): 草稿不动, 但模板新建的启用维度没有权重会让草稿
            // 「缺少维度权重」发布不了——回执必须把缺哪些维度说清楚, 否则是把用户锁死
            List<String> missing = dimensionService.enabledCodes(source, st).stream()
                .filter(dimCode -> !ruleService.parseWeights(open.getWeights()).containsKey(dimCode))
                .toList();
            draftNote = missing.isEmpty()
                ? "已有未发布的权重草稿, 本次未改动——去「权重规则」页签处理完再套"
                : "已有未发布的权重草稿, 本次未改动。注意: 启用维度 " + String.join(", ", missing)
                    + " 在该草稿里没有权重, 直接发布会报「缺少维度权重」——请先在「权重规则」页签补上权重再发布";
            draftRuleId = open.getRuleId();
        } else {
            ProfileRuleBo createBo = new ProfileRuleBo();
            createBo.setSource(source);
            createBo.setSubjectType(st);
            createBo.setRuleName(def.getName() + " · 模板权重");
            createBo.setRemark("场景模板「" + def.getName() + "」生成, 请核对后发布");
            ProfileScoreRule draft;
            try {
                // 三审回修(dspro P1): strict 版已有草稿直接抛错——createDraft 的静默复用会让
                // 并发套用 B 拿 A 的 revision 整份覆盖 A 的权重还回执「已生成」
                draft = ruleService.createDraftStrict(createBo);
            } catch (DuplicateKeyException concurrent) {
                // 两个管理员同时套: 后到者撞 uk_profile_rule_draft, PG 事务已中止,
                // 直接抛错整单回滚(维度/分群/漏斗一并回退), 重试即得「全跳过」回执
                throw new ServiceException("已有权重草稿被并发创建, 请重试");
            }
            ProfileRuleBo updateBo = new ProfileRuleBo();
            updateBo.setRuleId(draft.getRuleId());
            updateBo.setRevision(draft.getRevision());
            updateBo.setWeights(mergeWeights(source, st, def));
            ruleService.updateDraft(updateBo);
            draftNote = "已生成权重草稿, 核对后在「权重规则」页签发布才生效";
            draftRuleId = draft.getRuleId();
        }

        List<String> segsCreated = new ArrayList<>();
        List<String> segsSkipped = new ArrayList<>();
        Set<String> existingSegCodes = new HashSet<>();
        for (var s : segmentService.list(source)) {
            existingSegCodes.add(s.getSegmentCode());
        }
        for (ScenarioTemplateDef.SegmentDef seg : def.getSegments()) {
            if (!existingSegCodes.add(seg.getSegmentCode())) {
                segsSkipped.add(seg.getSegmentName());
                continue;
            }
            ProfileSegmentBo bo = new ProfileSegmentBo();
            bo.setSource(source);
            bo.setSegmentCode(seg.getSegmentCode());
            bo.setSegmentName(seg.getSegmentName());
            bo.setSegmentType("DYNAMIC");
            bo.setStatus("ENABLED");
            bo.setRuleConfig(seg.getRuleConfig());
            bo.setDescription(StringUtils.defaultIfBlank(seg.getDescription(),
                "场景模板「" + def.getName() + "」预填"));
            try {
                segmentService.create(bo);
            } catch (DuplicateKeyException concurrent) {
                throw new ServiceException("另一管理员正在套用同一模板或已建同编码分群, 请刷新后重试");
            }
            segsCreated.add(seg.getSegmentName());
        }

        List<String> funnelsCreated = new ArrayList<>();
        List<String> funnelsSkipped = new ArrayList<>();
        Set<String> existingFunnelCodes = new HashSet<>();
        for (var f : analyticsService.funnels(source)) {
            existingFunnelCodes.add(f.getFunnelCode());
        }
        for (ScenarioTemplateDef.FunnelDef funnel : def.getFunnels()) {
            if (!existingFunnelCodes.add(funnel.getFunnelCode())) {
                funnelsSkipped.add(funnel.getFunnelName());
                continue;
            }
            AnalyticsFunnelBo bo = new AnalyticsFunnelBo();
            bo.setSource(source);
            bo.setFunnelCode(funnel.getFunnelCode());
            bo.setFunnelName(funnel.getFunnelName());
            bo.setSteps(funnel.getSteps());
            bo.setWindowDays(funnel.getWindowDays() == null ? 7 : funnel.getWindowDays());
            bo.setEnabled("1");
            bo.setRemark("场景模板「" + def.getName() + "」预填");
            try {
                analyticsService.saveFunnel(bo);
            } catch (DuplicateKeyException concurrent) {
                throw new ServiceException("另一管理员正在套用同一模板或已建同编码漏斗, 请刷新后重试");
            }
            funnelsCreated.add(funnel.getFunnelName());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("templateName", def.getName());
        out.put("dimensionsCreated", dimsCreated);
        out.put("dimensionsSkipped", dimsSkipped);
        out.put("draftRuleId", draftRuleId);
        out.put("draftNote", draftNote);
        out.put("segmentsCreated", segsCreated);
        out.put("segmentsSkipped", segsSkipped);
        out.put("funnelsCreated", funnelsCreated);
        out.put("funnelsSkipped", funnelsSkipped);
        return out;
    }

    /**
     * 权重合并: 现行已发布权重为底(用户调过的优先), 模板给其新增维度补权重,
     * 两侧都没提到的启用维度诚实填 0(用户在草稿里看得见、可手调), 再归一到合计 1。
     * 三审回修(glm53/dspro P2): 先逐个 4 位舍入、再对第一个维度做「1-其余和」末位补偿,
     * 舍入之后合计才是精确 1(原顺序舍入会重新打破合计)。
     */
    private Map<String, Double> mergeWeights(String source, String subjectType, ScenarioTemplateDef def) {
        List<String> enabled = dimensionService.enabledCodes(source, subjectType);
        Map<String, Double> merged = new LinkedHashMap<>();
        ProfileScoreRule current = ruleService.current(source, subjectType);
        if (current != null) {
            merged.putAll(ruleService.parseWeights(current.getWeights()));
        }
        for (ScenarioTemplateDef.DimensionDef dim : def.getDimensions()) {
            merged.putIfAbsent(dim.getDimCode(), def.getWeights().getOrDefault(dim.getDimCode(), 0.0));
        }
        for (String code : enabled) {
            merged.putIfAbsent(code, 0.0);
        }
        merged.keySet().retainAll(enabled);
        double sum = ProfileRuleWeights.sum(merged);
        Map<String, Double> out = new LinkedHashMap<>();
        for (String code : enabled) {
            double v = merged.getOrDefault(code, 0.0);
            double normalized = sum <= 0 ? 1.0 / enabled.size() : v / sum;
            out.put(code, BigDecimal.valueOf(normalized).setScale(4, RoundingMode.HALF_UP).doubleValue());
        }
        // 末位吸收(对齐 ProfileRuleWeights.balanced 写法): 其余维度先求和, 第一个维度吃差额
        if (!enabled.isEmpty()) {
            String first = enabled.get(0);
            double rest = 0;
            for (int i = 1; i < enabled.size(); i++) {
                rest += out.get(enabled.get(i));
            }
            out.put(first, BigDecimal.valueOf(1.0 - rest).setScale(4, RoundingMode.HALF_UP).doubleValue());
        }
        return out;
    }

    // ---- 校验(内置与导入共用: 内置坏了启动期单测就炸, 导入坏了这里拦) ----

    private void normalizeAndValidate(ScenarioTemplateDef def) {
        List<String> errors = new ArrayList<>();
        if (def.getCode() == null || !CODE_PATTERN.matcher(def.getCode().trim()).matches()) {
            errors.add("模板编码须为字母开头的字母/数字/下划线, 最长 64");
        }
        if (StringUtils.isBlank(def.getName()) || def.getName().trim().length() > 100) {
            errors.add("模板名不能为空且不能超过 100 字");
        }
        if (StringUtils.isBlank(def.getDescription())) {
            errors.add("模板说明不能为空——要告诉用户事件名要换成自己的");
        } else if (def.getDescription().trim().length() > 500) {
            errors.add("模板说明不能超过 500 字");
        }
        if (def.getDimensions() == null) {
            // 三审回修(dspro/dsflash): 显式 "dimensions": null 会让 Jackson 置空, 防 NPE
            def.setDimensions(new ArrayList<>());
        }
        Set<String> dimCodes = new HashSet<>();
        if (def.getDimensions().isEmpty()) {
            errors.add("至少要有一个维度");
        } else if (def.getDimensions().size() > MAX_DIMENSIONS) {
            errors.add("维度最多 " + MAX_DIMENSIONS + " 个");
        }
        for (ScenarioTemplateDef.DimensionDef dim : def.getDimensions()) {
            if (dim == null) {
                errors.add("维度项不能为空");
                continue;
            }
            String prefix = "维度[" + dim.getDimCode() + "] ";
            if (dim.getDimCode() == null || !CODE_PATTERN.matcher(dim.getDimCode()).matches()) {
                errors.add(prefix + "编码不合法");
                continue;
            }
            if (!dimCodes.add(dim.getDimCode())) {
                errors.add(prefix + "编码重复");
            }
            if (StringUtils.isBlank(dim.getDimName())) {
                errors.add(prefix + "名称不能为空");
            } else if (dim.getDimName().trim().length() > 100) {
                // 对齐 ProfileDimensionService 落库校验, 防「导入过、套用炸」
                errors.add(prefix + "名称不能超过 100 字");
            }
            if (dim.getCalcMode() != null && !"EVENT_AGG".equalsIgnoreCase(dim.getCalcMode())) {
                errors.add(prefix + "只支持 EVENT_AGG 维度(模板要跨环境分享)");
            }
            BigDecimal scoreMax = dim.getScoreMax() == null ? BigDecimal.valueOf(100) : dim.getScoreMax();
            if (scoreMax.signum() <= 0 || scoreMax.compareTo(BigDecimal.valueOf(1000)) > 0) {
                errors.add(prefix + "scoreMax 须在 1~1000 之间(与维度页一致)");
            }
            errors.addAll(withPrefix(prefix, ProfileDimensionConfig.validate(
                "EVENT_AGG", dim.getCalcConfig(), scoreMax)));
        }
        if (def.getWeights() == null) {
            def.setWeights(new LinkedHashMap<>());
        }
        if (def.getSegments() == null) {
            def.setSegments(new ArrayList<>());
        }
        if (def.getFunnels() == null) {
            def.setFunnels(new ArrayList<>());
        }
        if (Math.abs(ProfileRuleWeights.sum(def.getWeights()) - 1.0) > ProfileRuleWeights.EPSILON) {
            errors.add("权重合计须≈1, 当前 " + ProfileRuleWeights.sum(def.getWeights()));
        }
        for (Map.Entry<String, Double> e : def.getWeights().entrySet()) {
            if (!dimCodes.contains(e.getKey())) {
                errors.add("权重引用了模板里没有的维度: " + e.getKey());
            }
            if (e.getValue() == null || !Double.isFinite(e.getValue()) || e.getValue() < 0 || e.getValue() > 1) {
                // isFinite 拦 NaN/Infinity: JSON 字符串 "NaN" 能被 Jackson 解成 Double.NaN,
                // 而 NaN 的任何比较都是 false, 只比大小会放行到套用时才炸 NumberFormatException
                errors.add("维度 " + e.getKey() + " 的权重须在 0~1 之间");
            }
        }
        // 三审回修(dsflash P2): 权重必须覆盖全部维度——缺口在套用时只会被静默填 0,
        // 用户发布后这些维度白算; 导入侧直接拦, 让模板自带完整权重表
        for (String code : dimCodes) {
            if (!def.getWeights().containsKey(code)) {
                errors.add("缺少维度权重: " + code);
            }
        }
        if (def.getSegments().size() > MAX_SEGMENTS) {
            errors.add("分群最多 " + MAX_SEGMENTS + " 个");
        }
        for (ScenarioTemplateDef.SegmentDef seg : def.getSegments()) {
            if (seg == null) {
                errors.add("分群项不能为空");
                continue;
            }
            String prefix = "分群[" + seg.getSegmentCode() + "] ";
            if (seg.getSegmentCode() == null || !CODE_PATTERN.matcher(seg.getSegmentCode()).matches()) {
                errors.add(prefix + "编码不合法");
                continue;
            }
            if (StringUtils.isBlank(seg.getSegmentName())) {
                errors.add(prefix + "名称不能为空");
            } else if (seg.getSegmentName().trim().length() > 100) {
                errors.add(prefix + "名称不能超过 100 字");
            }
            errors.addAll(withPrefix(prefix, ProfileSegmentRule.validate(
                JsonUtils.parseObject(JsonUtils.toJsonString(seg.getRuleConfig()), ProfileSegmentRule.class))));
        }
        if (def.getFunnels().size() > MAX_FUNNELS) {
            errors.add("漏斗最多 " + MAX_FUNNELS + " 个");
        }
        for (ScenarioTemplateDef.FunnelDef funnel : def.getFunnels()) {
            if (funnel == null) {
                errors.add("漏斗项不能为空");
                continue;
            }
            String prefix = "漏斗[" + funnel.getFunnelCode() + "] ";
            if (funnel.getFunnelCode() == null || !CODE_PATTERN.matcher(funnel.getFunnelCode()).matches()) {
                errors.add(prefix + "编码不合法");
                continue;
            }
            if (StringUtils.isBlank(funnel.getFunnelName())) {
                errors.add(prefix + "名称不能为空");
            } else if (funnel.getFunnelName().trim().length() > 128) {
                errors.add(prefix + "名称不能超过 128 字");
            }
            if (funnel.getSteps() == null || funnel.getSteps().size() < MIN_FUNNEL_STEPS
                || funnel.getSteps().size() > MAX_STEPS) {
                errors.add(prefix + "步骤须为 " + MIN_FUNNEL_STEPS + "~" + MAX_STEPS + " 步(与漏斗维护页一致)");
                continue;
            }
            for (Map<String, Object> step : funnel.getSteps()) {
                Object eventName = step == null ? null : step.get("eventName");
                if (eventName == null || String.valueOf(eventName).isBlank()) {
                    errors.add(prefix + "每一步都要有 eventName");
                }
            }
            Integer days = funnel.getWindowDays();
            if (days == null || days < 1 || days > MAX_FUNNEL_WINDOW_DAYS) {
                errors.add(prefix + "窗口天数须在 1~" + MAX_FUNNEL_WINDOW_DAYS + "(漏斗页上限)");
            }
        }
        if (!errors.isEmpty()) {
            throw new ServiceException("模板不合法: " + String.join("; ", errors));
        }
    }

    private static List<String> withPrefix(String prefix, List<String> errors) {
        List<String> out = new ArrayList<>();
        for (String e : errors) {
            out.add(prefix + e);
        }
        return out;
    }

    private Map<String, Object> summary(String kind, ScenarioTemplateDef def, Long templateId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("templateId", templateId);
        out.put("code", def.getCode());
        out.put("name", def.getName());
        out.put("scenario", def.getScenario());
        out.put("description", def.getDescription());
        out.put("recommended", Boolean.TRUE.equals(def.getRecommended()));
        out.put("dimensionCount", def.getDimensions().size());
        out.put("segmentCount", def.getSegments().size());
        out.put("funnelCount", def.getFunnels().size());
        List<Map<String, Object>> weightPreview = new ArrayList<>();
        for (ScenarioTemplateDef.DimensionDef dim : def.getDimensions()) {
            Map<String, Object> w = new HashMap<>();
            w.put("dimCode", dim.getDimCode());
            w.put("dimName", dim.getDimName());
            w.put("weight", def.getWeights().get(dim.getDimCode()));
            weightPreview.add(w);
        }
        out.put("weightPreview", weightPreview);
        return out;
    }

    private record SavedTemplate(Long templateId, ScenarioTemplateDef def) {
    }

    private SavedTemplate requireSaved(Long templateId) {
        ProfileScenarioTemplate row = templateMapper.selectById(templateId);
        if (row == null || !"0".equals(String.valueOf(row.getDelFlag())) ) {
            throw new ServiceException("模板不存在或已删除");
        }
        if (!currentTenant().equals(row.getTenantId())) {
            throw new ServiceException("模板不存在或已删除");
        }
        ScenarioTemplateDef def = parseDefinition(row.getDefinition());
        if (def == null) {
            throw new ServiceException("模板定义解析失败, 请重新导入");
        }
        return new SavedTemplate(templateId, def);
    }

    private ScenarioTemplateDef parseDefinition(String json) {
        if (StringUtils.isBlank(json)) {
            return null;
        }
        try {
            return JsonUtils.parseObject(json, ScenarioTemplateDef.class);
        } catch (Exception e) {
            return null;
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
        // 三审回修(glm53 P2): 对齐同包三个服务——无上下文时 fail-fast,
        // 不许静默写进默认租户
        return ProfileTenantContext.require();
    }
}
