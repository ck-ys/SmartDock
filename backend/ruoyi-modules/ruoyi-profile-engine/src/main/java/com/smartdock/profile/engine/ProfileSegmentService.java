package com.smartdock.profile.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.profile.domain.ProfileSegment;
import com.smartdock.profile.domain.ProfileSegmentMember;
import com.smartdock.profile.domain.ProfileSegmentRun;
import com.smartdock.profile.mapper.ProfileSegmentMapper;
import com.smartdock.profile.mapper.ProfileSegmentMemberMapper;
import com.smartdock.profile.mapper.ProfileSegmentRunMapper;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import com.smartdock.profile.model.ProfileSegmentRule;
import com.smartdock.profile.query.ProfileSegmentBo;
import com.smartdock.profile.query.ProfileSegmentImportBo;
import com.smartdock.profile.query.ProfileSegmentMemberRow;
import com.smartdock.profile.query.ProfileSubjectRow;
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
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 标签分群: 动态规则求值 + 静态名单导入 + 成员快照(run)。
 *
 * <p>动态规则只在 Java 侧对候选集求值, 不拼接任意 SQL;
 * 每次评估生成一条 run, 成员挂在 run 下, 历史成员可追溯。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileSegmentService {

    private static final String DYNAMIC = "DYNAMIC";
    private static final String STATIC = "STATIC";

    private final ProfileSegmentMapper segmentMapper;
    private final ProfileSegmentRunMapper runMapper;
    private final ProfileSegmentMemberMapper memberMapper;
    private final ProfileSubjectMapper subjectMapper;

    public List<ProfileSegment> list(String source) {
        requireSource(source);
        return segmentMapper.selectList(new LambdaQueryWrapper<ProfileSegment>()
            .eq(ProfileSegment::getSource, source)
            .orderByDesc(ProfileSegment::getBuiltin)
            .orderByAsc(ProfileSegment::getSegmentName));
    }

    public ProfileSegment get(Long segmentId) {
        ProfileSegment segment = segmentMapper.selectById(segmentId);
        if (segment == null) {
            throw new ServiceException("分群不存在");
        }
        return segment;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileSegment create(ProfileSegmentBo bo) {
        requireSource(bo.getSource());
        validateBo(bo, true);
        Long duplicated = segmentMapper.selectCount(new LambdaQueryWrapper<ProfileSegment>()
            .eq(ProfileSegment::getSource, bo.getSource())
            .eq(ProfileSegment::getSegmentCode, bo.getSegmentCode().trim()));
        if (duplicated != null && duplicated > 0) {
            throw new ServiceException("同一业务源下分群编码已存在: " + bo.getSegmentCode());
        }
        ProfileSegment segment = new ProfileSegment();
        segment.setSegmentId(IdWorker.getId());
        segment.setTenantId(currentTenant());
        segment.setSource(bo.getSource());
        segment.setSegmentCode(bo.getSegmentCode().trim());
        segment.setSegmentName(bo.getSegmentName().trim());
        segment.setSegmentType(normalizeType(bo.getSegmentType()));
        segment.setStatus("DISABLED".equalsIgnoreCase(bo.getStatus()) ? "DISABLED" : "ENABLED");
        segment.setRuleConfig(STATIC.equals(segment.getSegmentType()) ? "{}"
            : JsonUtils.toJsonString(bo.getRuleConfig()));
        segment.setDescription(StringUtils.substring(bo.getDescription(), 0, 500));
        segment.setMemberCount(0);
        segment.setRefreshMode(normalizeRefreshMode(bo.getRefreshMode()));
        segment.setBuiltin("0");
        segment.setRevision(0);
        segment.setCreateBy(currentUser());
        segmentMapper.insertJsonb(segment);
        return segment;
    }

    @Transactional(rollbackFor = Exception.class)
    public ProfileSegment update(ProfileSegmentBo bo) {
        if (bo.getSegmentId() == null) {
            throw new ServiceException("segmentId 不能为空");
        }
        ProfileSegment existing = get(bo.getSegmentId());
        bo.setSource(existing.getSource());
        bo.setSegmentCode(existing.getSegmentCode());
        validateBo(bo, false);
        if (bo.getRevision() == null || !bo.getRevision().equals(existing.getRevision())) {
            throw new ServiceException("分群已被其他人修改, 请刷新后重试");
        }
        existing.setSegmentName(bo.getSegmentName().trim());
        existing.setSegmentType(normalizeType(bo.getSegmentType()));
        existing.setStatus("DISABLED".equalsIgnoreCase(bo.getStatus()) ? "DISABLED" : "ENABLED");
        existing.setRuleConfig(STATIC.equals(existing.getSegmentType()) ? "{}"
            : JsonUtils.toJsonString(bo.getRuleConfig()));
        existing.setDescription(StringUtils.substring(bo.getDescription(), 0, 500));
        existing.setRefreshMode(normalizeRefreshMode(bo.getRefreshMode()));
        existing.setRevision((existing.getRevision() == null ? 0 : existing.getRevision()) + 1);
        existing.setUpdateBy(currentUser());
        segmentMapper.updateJsonb(existing);
        return existing;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean remove(Long segmentId) {
        ProfileSegment segment = get(segmentId);
        if ("1".equals(segment.getBuiltin())) {
            return segmentMapper.updateStatus(segmentId, segment.getTenantId(), "DISABLED", currentUser()) > 0;
        }
        return segmentMapper.deleteById(segmentId) > 0;
    }

    /**
     * 干跑: 不落成员, 返回命中数与样例。
     */
    public Map<String, Object> preview(ProfileSegmentBo bo) {
        if (bo == null || StringUtils.isBlank(bo.getSource())) {
            throw new ServiceException("source 不能为空");
        }
        ProfileSegmentRule rule = parseRule(bo.getRuleConfig());
        List<String> errors = ProfileSegmentRule.validate(rule);
        if (!errors.isEmpty()) {
            throw new ServiceException("分群规则不合法: " + String.join("; ", errors));
        }
        List<ProfileSubjectRow> candidates = segmentCandidates(bo.getSource());
        List<Map<String, Object>> sample = new ArrayList<>();
        int matched = 0;
        for (ProfileSubjectRow row : candidates) {
            Map<String, Object> parsed = toCandidate(row);
            if (rule.matches(parsed)) {
                matched++;
                if (sample.size() < 20) {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("subjectType", row.getSubjectType());
                    item.put("subjectId", row.getSubjectId());
                    item.put("displayName", row.getDisplayName());
                    item.put("totalScore", row.getTotalScore());
                    item.put("eventCount", row.getEventCount());
                    item.put("lastEventTime", row.getLastEventTime());
                    sample.add(item);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalCandidates", candidates.size());
        out.put("matched", matched);
        out.put("rate", candidates.isEmpty() ? BigDecimal.ZERO
            : BigDecimal.valueOf(matched * 100.0 / candidates.size()).setScale(2, RoundingMode.HALF_UP));
        out.put("summary", rule.summary());
        out.put("sample", sample);
        return out;
    }

    /**
     * 评估动态分群: 生成新 run + 成员快照, 更新分群统计。
     */
    public ProfileSegmentRun evaluate(Long segmentId) {
        return evaluate(segmentId, "MANUAL");
    }

    /**
     * 评估动态分群(批9: trigger 可为 MANUAL/SCHEDULE)。每次 run 可重跑可追溯;
     * 完成后按 run 保留最近 30 次, 更老的成员明细清理防膨胀。
     */
    public ProfileSegmentRun evaluate(Long segmentId, String trigger) {
        ProfileSegment segment = get(segmentId);
        if (!DYNAMIC.equalsIgnoreCase(segment.getSegmentType())) {
            throw new ServiceException("静态分群请使用名单导入");
        }
        if ("DISABLED".equalsIgnoreCase(segment.getStatus())) {
            throw new ServiceException("分群已停用, 请先启用");
        }
        ProfileSegmentRule rule = parseRule(segment.getRuleConfig());
        List<String> errors = ProfileSegmentRule.validate(rule);
        if (!errors.isEmpty()) {
            throw new ServiceException("分群规则不合法: " + String.join("; ", errors));
        }
        List<ProfileSubjectRow> candidates = segmentCandidates(segment.getSource());
        List<MemberDraft> matched = new ArrayList<>();
        String reason = StringUtils.substring(rule.summary(), 0, 500);
        for (ProfileSubjectRow row : candidates) {
            if (rule.matches(toCandidate(row))) {
                matched.add(new MemberDraft(row.getSubjectType(), row.getSubjectId(),
                    row.getDisplayName(), reason));
            }
        }
        ProfileSegmentRun run = writeRun(segment, StringUtils.isBlank(trigger) ? "MANUAL" : trigger.trim().toUpperCase(), candidates.size(), matched);
        pruneRuns(segment);
        return run;
    }

    /**
     * 静态名单导入: 按主体 ID 精确匹配, 生成新 run。
     */
    public Map<String, Object> importMembers(Long segmentId, ProfileSegmentImportBo bo) {
        ProfileSegment segment = get(segmentId);
        if (!STATIC.equalsIgnoreCase(segment.getSegmentType())) {
            throw new ServiceException("动态分群请用规则评估, 静态分群才支持名单导入");
        }
        if ("DISABLED".equalsIgnoreCase(segment.getStatus())) {
            throw new ServiceException("分群已停用, 请先启用");
        }
        List<String> ids = normalizeIds(bo == null ? null : bo.getSubjectIds());
        if (ids.isEmpty()) {
            throw new ServiceException("导入名单不能为空");
        }
        String typeFilter = bo.getSubjectType() == null ? null : bo.getSubjectType().trim();
        List<ProfileSubjectRow> candidates = segmentCandidates(segment.getSource());
        Map<String, ProfileSubjectRow> byId = new LinkedHashMap<>();
        for (ProfileSubjectRow row : candidates) {
            if (StringUtils.isNotBlank(typeFilter) && !typeFilter.equals(row.getSubjectType())) {
                continue;
            }
            byId.putIfAbsent(row.getSubjectId(), row);
        }
        List<MemberDraft> members = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int notFound = 0;
        for (String id : ids) {
            ProfileSubjectRow row = byId.get(id);
            if (row == null) {
                notFound++;
                continue;
            }
            String key = row.getSubjectType() + "|" + row.getSubjectId();
            if (seen.add(key)) {
                members.add(new MemberDraft(row.getSubjectType(), row.getSubjectId(),
                    row.getDisplayName(), "手动导入"));
            }
        }
        boolean append = bo != null && "APPEND".equalsIgnoreCase(bo.getMode());
        if (append && segment.getLastRunId() != null) {
            List<ProfileSegmentMember> existing = memberMapper.selectList(
                new LambdaQueryWrapper<ProfileSegmentMember>()
                    .eq(ProfileSegmentMember::getSegmentId, segment.getSegmentId())
                    .eq(ProfileSegmentMember::getRunId, segment.getLastRunId()));
            for (ProfileSegmentMember m : existing) {
                String key = m.getSubjectType() + "|" + m.getSubjectId();
                if (seen.add(key)) {
                    members.add(new MemberDraft(m.getSubjectType(), m.getSubjectId(),
                        m.getDisplayName(), "沿用上次成员"));
                }
            }
        }
        ProfileSegmentRun run = writeRun(segment, "IMPORT", candidates.size(), members);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.getRunId());
        out.put("status", run.getStatus());
        out.put("memberCount", run.getMemberCount());
        out.put("notFound", notFound);
        return out;
    }

    public IPage<ProfileSegmentMemberRow> memberPage(Long segmentId, int pageNum, int pageSize, String keyword) {
        ProfileSegment segment = get(segmentId);
        Page<ProfileSegmentMemberRow> page = new Page<>(Math.max(1, pageNum), Math.min(Math.max(1, pageSize), 200));
        page.setOptimizeCountSql(false);
        if (segment.getLastRunId() == null) {
            return page;
        }
        return memberMapper.selectMemberPage(page, segment.getTenantId(), segmentId,
            segment.getLastRunId(), keyword);
    }

    public List<ProfileSegmentRun> runs(Long segmentId) {
        ProfileSegment segment = get(segmentId);
        return runMapper.selectList(new LambdaQueryWrapper<ProfileSegmentRun>()
            .eq(ProfileSegmentRun::getSegmentId, segmentId)
            .eq(ProfileSegmentRun::getTenantId, segment.getTenantId())
            .orderByDesc(ProfileSegmentRun::getCreateTime)
            .last("LIMIT 50"));
    }

    // ---------------------------------------------------------------- 内部

    /** 只保留最近 30 个 run 的成员明细(批9 Q2-A 防膨胀; run 头记录保留, 成员行清理)。 */
    private void pruneRuns(ProfileSegment segment) {
        try {
            List<ProfileSegmentRun> keep = runMapper.selectList(new LambdaQueryWrapper<ProfileSegmentRun>()
                .eq(ProfileSegmentRun::getSegmentId, segment.getSegmentId())
                .eq(ProfileSegmentRun::getTenantId, segment.getTenantId())
                .orderByDesc(ProfileSegmentRun::getCreateTime)
                .last("LIMIT " + KEEP_RUNS));
            Long total = runMapper.selectCount(new LambdaQueryWrapper<ProfileSegmentRun>()
                .eq(ProfileSegmentRun::getSegmentId, segment.getSegmentId()));
            if (total == null || total <= KEEP_RUNS || keep.isEmpty()) {
                return;
            }
            // 不圈 200 窗口: 直接删「不在保留名单里」的全部成员行, 否则第 201 个及更老的 run 永远清不到
            memberMapper.delete(new LambdaQueryWrapper<ProfileSegmentMember>()
                .eq(ProfileSegmentMember::getSegmentId, segment.getSegmentId())
                .eq(ProfileSegmentMember::getTenantId, segment.getTenantId())
                .notIn(ProfileSegmentMember::getRunId,
                    keep.stream().map(ProfileSegmentRun::getRunId).toList()));
        } catch (Exception e) {
            log.warn("[Profile] segment run prune failed segment={} err={}", segment.getSegmentId(), e.getMessage());
        }
    }

    private static final int KEEP_RUNS = 30;

    private ProfileSegmentRun writeRun(ProfileSegment segment, String trigger, int candidates,
                                       List<MemberDraft> members) {
        ProfileSegmentRun run = new ProfileSegmentRun();
        run.setRunId(IdWorker.getId());
        run.setTenantId(segment.getTenantId());
        run.setSource(segment.getSource());
        run.setSegmentId(segment.getSegmentId());
        run.setTriggerType(trigger);
        run.setStatus("RUNNING");
        run.setTotalCandidates(candidates);
        run.setMemberCount(0);
        run.setStartedAt(new Date());
        run.setCreateBy(currentUser());
        runMapper.insert(run);
        try {
            int inserted = 0;
            for (MemberDraft draft : members) {
                ProfileSegmentMember member = new ProfileSegmentMember();
                member.setMemberId(IdWorker.getId());
                member.setTenantId(segment.getTenantId());
                member.setSource(segment.getSource());
                member.setSegmentId(segment.getSegmentId());
                member.setRunId(run.getRunId());
                member.setSubjectType(draft.subjectType());
                member.setSubjectId(draft.subjectId());
                member.setDisplayName(StringUtils.substring(draft.displayName(), 0, 128));
                member.setReason(StringUtils.substring(draft.reason(), 0, 500));
                member.setCreateBy(currentUser());
                memberMapper.insert(member);
                inserted++;
            }
            run.setStatus("DONE");
            run.setMemberCount(inserted);
            run.setFinishedAt(new Date());
            runMapper.updateById(run);
            segmentMapper.updateRunStats(segment.getSegmentId(), segment.getTenantId(), inserted,
                run.getRunId(), run.getFinishedAt(), currentUser());
            segment.setMemberCount(inserted);
            segment.setLastRunId(run.getRunId());
            segment.setLastEvalTime(run.getFinishedAt());
        } catch (Exception e) {
            log.error("[Profile] segment evaluate failed segment={} : {}", segment.getSegmentId(), e.getMessage(), e);
            run.setStatus("FAILED");
            run.setErrorMsg(StringUtils.substring(String.valueOf(e.getMessage()), 0, 2000));
            run.setFinishedAt(new Date());
            runMapper.updateById(run);
        }
        return run;
    }

    private List<ProfileSubjectRow> segmentCandidates(String source) {
        return subjectMapper.selectSegmentCandidates(currentTenant(), source);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toCandidate(ProfileSubjectRow row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("subjectType", row.getSubjectType());
        out.put("subjectId", row.getSubjectId());
        out.put("displayName", row.getDisplayName());
        out.put("eventCount", row.getEventCount());
        out.put("totalScore", row.getTotalScore());
        out.put("properties", parseMap(row.getProperties()));
        out.put("dimensions", parseMap(row.getDimensionDetail()));
        if (row.getLastEventTime() != null) {
            long days = Math.max(0, (System.currentTimeMillis() - row.getLastEventTime().getTime()) / 86_400_000L);
            out.put("lastEventDays", days);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseMap(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JsonUtils.parseMap(json);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private ProfileSegmentRule parseRule(Object config) {
        if (config == null) {
            return new ProfileSegmentRule();
        }
        if (config instanceof ProfileSegmentRule r) {
            return r;
        }
        String json = config instanceof String s ? s : JsonUtils.toJsonString(config);
        if (StringUtils.isBlank(json)) {
            return new ProfileSegmentRule();
        }
        ProfileSegmentRule rule = JsonUtils.parseObject(json, ProfileSegmentRule.class);
        return rule == null ? new ProfileSegmentRule() : rule;
    }

    private List<String> normalizeIds(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null) {
            return List.of();
        }
        for (String item : raw) {
            if (item == null) {
                continue;
            }
            for (String part : item.split("[,;\\s]+")) {
                if (StringUtils.isNotBlank(part)) {
                    out.add(part.trim());
                }
            }
        }
        return new ArrayList<>(out);
    }

    private void validateBo(ProfileSegmentBo bo, boolean requireCode) {
        if (requireCode && (StringUtils.isBlank(bo.getSegmentCode())
            || !bo.getSegmentCode().trim().matches("^[A-Za-z][A-Za-z0-9_]{0,63}$"))) {
            throw new ServiceException("segmentCode 只允许字母开头的字母/数字/下划线, 最长 64");
        }
        if (StringUtils.isBlank(bo.getSegmentName()) || bo.getSegmentName().trim().length() > 100) {
            throw new ServiceException("segmentName 不能为空且不能超过 100 字");
        }
        String type = normalizeType(bo.getSegmentType());
        if (!DYNAMIC.equals(type) && !STATIC.equals(type)) {
            throw new ServiceException("segmentType 只支持 DYNAMIC / STATIC");
        }
        if (DYNAMIC.equals(type)) {
            List<String> errors = ProfileSegmentRule.validate(parseRule(bo.getRuleConfig()));
            if (!errors.isEmpty()) {
                throw new ServiceException("分群规则不合法: " + String.join("; ", errors));
            }
        }
    }

    /** OFF/DAILY/HOURLY 归一, 非法值一律 OFF（保守: 不刷新不影响存量行为）。 */
    private String normalizeRefreshMode(String mode) {
        return "DAILY".equalsIgnoreCase(mode) ? "DAILY" : "HOURLY".equalsIgnoreCase(mode) ? "HOURLY" : "OFF";
    }

    private String normalizeType(String type) {
        return STATIC.equalsIgnoreCase(type) ? STATIC : DYNAMIC;
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
        return "000000";
    }

    private record MemberDraft(String subjectType, String subjectId, String displayName, String reason) {
    }
}
