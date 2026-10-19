package com.smartdock.profile.identity;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.smartdock.profile.mapper.ProfileIdentityLinkMapper;
import com.smartdock.profile.mapper.ProfileIdentityMergeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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

        String canonical = subjectId;
        boolean strongConflict = false;
        boolean canonicalFixed = false;
        Set<String> conflictingTypes = new java.util.HashSet<>();
        String actor = currentActor();
        boolean anonymousEvent = StringUtils.isNotBlank(ids.get("anonymous_id"))
            && ids.get("anonymous_id").equals(subjectId);

        String userId = ids.get("user_id");
        if (userId != null) {
            ProfileIdentityLink existing = linkMapper.selectLink(tenantId, source, "user_id", userId);
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
            ProfileIdentityLink existing = linkMapper.selectLink(tenantId, source, type, value);
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

        // anonymous_id: 登录后把匿名主体并入规范主体; 强冲突时保留原链接不动
        String anonymousId = ids.get("anonymous_id");
        if (anonymousId != null) {
            ProfileIdentityLink existing = linkMapper.selectLink(tenantId, source, "anonymous_id", anonymousId);
            if (existing != null && StringUtils.isNotBlank(existing.getSubjectId())
                && !existing.getSubjectId().equals(canonical)) {
                if (strongConflict) {
                    conflictingTypes.add("anonymous_id");
                } else {
                    mergeSubject(tenantId, source, subjectType, existing.getSubjectId(), canonical,
                        userId != null ? "login" : "anonymous_merge", actor);
                }
            }
        }

        // 事件主体本身是匿名主体时, 并入规范主体; 强身份冲突时不动(避免误合并两个用户)
        if (!strongConflict && !canonical.equals(subjectId)) {
            mergeSubject(tenantId, source, subjectType, subjectId, canonical,
                userId != null ? "login" : "anonymous_merge", actor);
        }

        Date now = new Date();
        for (Map.Entry<String, String> entry : ids.entrySet()) {
            if (conflictingTypes.contains(entry.getKey())) {
                continue;
            }
            upsertLink(tenantId, source, subjectType, canonical, entry.getKey(), entry.getValue(), now);
        }
        return canonical;
    }

    private void mergeSubject(String tenantId, String source, String subjectType,
                              String fromSubjectId, String toSubjectId, String reason, String actor) {
        if (StringUtils.isBlank(fromSubjectId) || StringUtils.isBlank(toSubjectId)
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

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
