package com.smartdock.profile.identity;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.smartdock.profile.mapper.ProfileIdentityLinkMapper;
import com.smartdock.profile.mapper.ProfileIdentityMergeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 确定性身份解析: 把事件里的标识符链接到规范主体。
 *
 * <p>一期策略:
 * <ul>
 *   <li>强 ID(user_id/email_sha256/phone_sha256/...)冲突时以已有主体为准, 绝不自动合并两个强身份;</li>
 *   <li>anonymous_id 可在登录时并入规范主体, 并写 profile_identity_merge 审计;</li>
 *   <li>device_id 只关联不合并(共享设备保护);</li>
 *   <li>合并只迁移 identity_link, 不重写历史事件, 二期可按 merge 事件重算/unmerge。</li>
 * </ul>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileIdentityService {

    private static final Set<String> STRONG = Set.of(
        "user_id", "email_sha256", "phone_sha256", "crm_id", "member_id", "account_id");
    private static final Set<String> BLOCKLIST = Set.of(
        "", "0", "-1", "null", "undefined", "anonymous", "unknown");

    private final ProfileIdentityLinkMapper linkMapper;
    private final ProfileIdentityMergeMapper mergeMapper;
    /** 批10 Q6: 拆分状态变更用短事务模板 */
    private final TransactionTemplate txTemplate;
    private final com.smartdock.analytics.engine.AnalyticsService analyticsService;
    private final com.smartdock.profile.engine.ProfileScoreEngine scoreEngine;
    private final com.smartdock.profile.engine.ProfileScoreModelService modelService;

    /**
     * 解析规范主体并写入/更新标识链接。
     *
     * @return 规范 subjectId; 无标识时原样返回
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public String resolveAndLink(String tenantId, String source, String subjectType, String subjectId,
                                 Map<String, String> identifiers) {
        if (StringUtils.isBlank(subjectId)) {
            return subjectId;
        }
        Map<String, String> ids = normalize(identifiers);
        if (ids.isEmpty()) {
            return subjectId;
        }
        // 批11 Q4-A: 归并焦点(匿名 ID 优先, 否则首个标识)上事务级 advisory 锁——
        // 同一匿名 ID 两处并发登录归并时不再写出双链/双份 merge 审计(TOCTOU)
        String anchor = ids.get("anonymous_id") != null ? ids.get("anonymous_id")
            : ids.entrySet().iterator().next().getValue();
        return txTemplate.execute(status -> {
            linkMapper.advisoryXactLock("identity:" + tenantId + ":" + source + ":" + anchor);
            return resolveAndLinkLocked(tenantId, source, subjectType, subjectId, ids);
        });
    }

    private String resolveAndLinkLocked(String tenantId, String source, String subjectType, String subjectId,
                                        Map<String, String> ids) {
        String canonical = subjectId;
        boolean strongConflict = false;
        boolean canonicalFixed = false;
        Set<String> conflictingTypes = new java.util.HashSet<>();
        String actor = currentActor();
        boolean anonymousEvent = StringUtils.isNotBlank(ids.get("anonymous_id"))
            && ids.get("anonymous_id").equals(subjectId);

        String userId = ids.get("user_id");
        if (userId != null) {
            ProfileIdentityLink existing = linkMapper.selectLinkStrong(tenantId, source, subjectType, "user_id", userId);
            if (existing != null && StringUtils.isNotBlank(existing.getSubjectId())) {
                canonical = existing.getSubjectId();
                canonicalFixed = true;
                // 已知用户的事件主体与 user_id 指向不一致: 只有“匿名主体登录”允许并入,
                // 两个真实用户 ID 冲突时保持各自独立。
                if (!anonymousEvent && !canonical.equals(subjectId)) {
                    strongConflict = true;
                    log.warn("[Profile] user_id conflict source={} existing={} incoming={}",
                        source, canonical, subjectId);
                }
            }
        }

        // 其他强 ID 冲突: 已有主体优先, 但绝不把冲突标识改挂到另一个主体
        for (String type : STRONG) {
            if ("user_id".equals(type)) {
                continue;
            }
            String value = ids.get(type);
            if (value == null) {
                continue;
            }
            ProfileIdentityLink existing = linkMapper.selectLinkStrong(tenantId, source, subjectType, type, value);
            if (existing != null && StringUtils.isNotBlank(existing.getSubjectId())
                && !existing.getSubjectId().equals(canonical)) {
                log.warn("[Profile] strong identity conflict source={} type={} existing={} incoming={}",
                    source, type, existing.getSubjectId(), canonical);
                if (!canonicalFixed) {
                    canonical = existing.getSubjectId();
                    canonicalFixed = true;
                }
                conflictingTypes.add(type);
                strongConflict = true;
            }
        }

        // anonymous_id: 登录后把匿名主体并入规范主体; 挂载方是真实用户时绝不搬链接
        // (existingAnonType 提升到匿名块外: 收尾登记时匿名桥沿用既有行类型保单行)
        String existingAnonType = null;
        String anonymousId = ids.get("anonymous_id");
        if (anonymousId != null) {
            ProfileIdentityLink existing = linkMapper.selectLinkShared(tenantId, source, "anonymous_id", anonymousId);
            existingAnonType = existing == null ? null : existing.getSubjectType();
            if (existing != null && StringUtils.isNotBlank(existing.getSubjectId())
                && !existing.getSubjectId().equals(canonical)) {
                String holder = existing.getSubjectId();
                if (strongConflict) {
                    conflictingTypes.add("anonymous_id");
                } else if (linkMapper.countStrongLinks(tenantId, source, holder) > 0) {
                    // 持有者在任一主体类型命名空间下有强标识即真实用户(桥行类型可能与强标识命名空间不同)
                    // 匿名 ID 已挂在真实用户名下:
                    // 匿名回访 → 沿用该用户, 事件归因不断链;
                    // 共享浏览器换账号(userId 已定新 canonical) → 只把匿名链接改挂到新主体, 不搬对方身份
                    if (userId == null && !canonicalFixed) {
                        canonical = holder;
                        canonicalFixed = true;
                    } else {
                        log.info("[Profile] anonymous_id re-pointed (shared device, no merge) "
                                + "source={} {} : {} -> {}", source, anonymousId, holder, canonical);
                    }
                } else {
                    // 挂载点是纯匿名主体: 正常"登录升级"并入规范主体
                    mergeSubject(tenantId, source, subjectType, holder, canonical,
                        userId != null ? "login" : "anonymous_merge", actor);
                }
            }
        }

        // 事件主体本身是匿名主体且本次是登录: 并入规范主体;
        // 匿名回访(userId 为空)不走这里, 否则每次回访都会追加一条 merge 审计
        if (!strongConflict && userId != null && !canonical.equals(subjectId)) {
            mergeSubject(tenantId, source, subjectType, subjectId, canonical,
                userId != null ? "login" : "anonymous_merge", actor);
        }

        Date now = new Date();
        for (Map.Entry<String, String> entry : ids.entrySet()) {
            if (conflictingTypes.contains(entry.getKey())) {
                continue;
            }
            // 匿名桥改挂沿用既有行的 subject_type 保持单行语义; 强标识用事件主体类型(各命名空间各自登记)
            String upsertType = "anonymous_id".equals(entry.getKey()) && existingAnonType != null
                ? existingAnonType : subjectType;
            upsertLink(tenantId, source, upsertType, canonical, entry.getKey(), entry.getValue(), now);
        }
        return canonical;
    }

    // ---------------------------------------------------------------- 批10 Q6: unmerge

    /** 合并记录列表(图谱管理页; 可按业务源/任一侧主体过滤)。 */
    public List<ProfileIdentityMerge> merges(String source, String subject) {
        String tenantId = currentTenant();
        return mergeMapper.selectRecent(tenantId,
            StringUtils.isBlank(source) ? null : source.trim(),
            StringUtils.isBlank(subject) ? null : subject.trim());
    }

    /**
     * 拆分误合并(拍板 Q6-A): ①匿名来源事件归属回改(identifiers 无 user_id 的行才是
     * 被误并的匿名行为, 登录主体自己的事件不动) ②匿名链接回挂 ③乐观标记已拆分
     * ——三步一个事务; 事务外按合并时间线回放派生数据(拍板原文"只回放身份链"):
     * 会话/日汇总走 {@code rebuildRange}(持批10 Q1 重建锁), 双方主体各重算一次分数。
     *
     * <p>注意: 拆掉的是"误并"的匿名标识。若该匿名 ID 之后真的以登录身份回来,
     * resolveAndLink 会按共享设备规则重新挂链接——那是新事实, 不是误并复发。</p>
     */
    public Map<String, Object> unmerge(Long mergeId) {
        if (mergeId == null) {
            throw new ServiceException("mergeId 不能为空");
        }
        ProfileIdentityMerge merge = mergeMapper.selectById(mergeId);
        if (merge == null) {
            throw new ServiceException("合并记录不存在: " + mergeId);
        }
        if (merge.getReversedAt() != null) {
            throw new ServiceException("该合并已拆分过(拆分人=" + merge.getReversedBy() + ")");
        }
        // 批11 Q7: 30 天可逆窗口补硬校验——超窗的合并已是长期既成事实, 拆它比留着更伤画像连续性
        if (merge.getReversibleUntil() != null && new Date().after(merge.getReversibleUntil())) {
            throw new ServiceException("该合并已超过可逆窗口(截至 " + merge.getReversibleUntil()
                + "), 不可再拆; 如确需调整请重新触发合并条件");
        }
        String actor = currentActor();
        Integer reassigned = txTemplate.execute(status -> {
            int events = mergeMapper.reassignEvents(merge);
            mergeMapper.repointLink(merge);
            if (mergeMapper.markReversed(mergeId, actor) == 0) {
                throw new ServiceException("拆分竞争失败: 该合并刚被其他人拆过, 请刷新重试");
            }
            return events;
        });
        boolean replayed = replayDerived(merge);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mergeId", mergeId);
        out.put("fromSubjectId", merge.getFromSubjectId());
        out.put("toSubjectId", merge.getToSubjectId());
        out.put("eventsReassigned", reassigned == null ? 0 : reassigned);
        out.put("derivedReplayed", replayed);
        return out;
    }

    /** 派生数据回放: 失败不回滚拆分本身(状态已拆), 只打日志等下一条事件/人工重算补。 */
    private boolean replayDerived(ProfileIdentityMerge merge) {
        try {
            Date from = dayFloor(merge.getOccurredAt());
            Date to = tomorrow();
            analyticsService.rebuildRange(merge.getTenantId(), merge.getSource(), from, to, true, "UNMERGE");
            recalcOne(merge, merge.getFromSubjectId());
            recalcOne(merge, merge.getToSubjectId());
            return true;
        } catch (Exception e) {
            log.warn("[Profile] unmerge replay failed (split done, derived pending) merge={} err={}",
                merge.getMergeId(), e.getMessage());
            return false;
        }
    }

    private void recalcOne(ProfileIdentityMerge merge, String subjectId) {
        if (StringUtils.isBlank(subjectId)) {
            return;
        }
        TenantHelper.dynamic(merge.getTenantId(), () ->
            scoreEngine.recalcSubject(merge.getSource(), merge.getSubjectType(), subjectId));
        TenantHelper.dynamic(merge.getTenantId(), () ->
            modelService.evaluateSubjectIfActive(merge.getTenantId(), merge.getSource(),
                merge.getSubjectType(), subjectId));
    }

    private Date dayFloor(Date time) {
        if (time == null) {
            return new Date(0);
        }
        LocalDate day = time.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        return Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private Date tomorrow() {
        return Date.from(LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private void mergeSubject(String tenantId, String source, String subjectType,
                              String fromSubjectId, String toSubjectId, String reason, String actor) {        if (StringUtils.isBlank(fromSubjectId) || StringUtils.isBlank(toSubjectId)
            || fromSubjectId.equals(toSubjectId)) {
            return;
        }
        Date now = new Date();
        ProfileIdentityMerge merge = new ProfileIdentityMerge();
        merge.setMergeId(IdWorker.getId());
        merge.setTenantId(tenantId);
        merge.setSource(source);
        merge.setSubjectType(subjectType);
        merge.setFromSubjectId(fromSubjectId);
        merge.setToSubjectId(toSubjectId);
        merge.setReason(reason);
        merge.setRuleVersion("identity-v1");
        merge.setActor(actor);
        merge.setOccurredAt(now);
        merge.setReversibleUntil(new Date(now.getTime() + 30L * 24 * 3600_000L));
        mergeMapper.insert(merge);
        linkMapper.moveSubject(tenantId, source, subjectType, fromSubjectId, toSubjectId);
        log.info("[Profile] identity merged source={} {} -> {} reason={}",
            source, fromSubjectId, toSubjectId, reason);
    }

    private void upsertLink(String tenantId, String source, String subjectType, String subjectId,
                            String identifierType, String identifierValue, Date now) {
        ProfileIdentityLink link = new ProfileIdentityLink();
        link.setLinkId(IdWorker.getId());
        link.setTenantId(tenantId);
        link.setSource(source);
        link.setSubjectType(subjectType);
        link.setSubjectId(subjectId);
        link.setIdentifierType(identifierType);
        link.setIdentifierValue(identifierValue);
        link.setConfidence(BigDecimal.ONE);
        link.setIsSecure(STRONG.contains(identifierType) ? "1" : "0");
        link.setStatus("ACTIVE");
        link.setFirstSeen(now);
        link.setLastSeen(now);
        linkMapper.upsertLink(link);
    }

    private Map<String, String> normalize(Map<String, String> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        identifiers.forEach((key, value) -> {
            if (StringUtils.isBlank(key) || StringUtils.isBlank(value)) {
                return;
            }
            String type = key.trim().toLowerCase(Locale.ROOT);
            if (type.length() > 32) {
                log.warn("[Profile] identifier type too long, skipped: {}", type);
                return;
            }
            String normalized = value.trim();
            if (BLOCKLIST.contains(normalized.toLowerCase(Locale.ROOT))) {
                return;
            }
            if (normalized.length() > 128) {
                normalized = sha256(normalized);
            }
            out.put(type, normalized);
        });
        return out;
    }

    private String currentActor() {
        try {
            Long userId = LoginHelper.getUserId();
            return userId == null ? "system" : String.valueOf(userId);
        } catch (Exception e) {
            return "system";
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

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
