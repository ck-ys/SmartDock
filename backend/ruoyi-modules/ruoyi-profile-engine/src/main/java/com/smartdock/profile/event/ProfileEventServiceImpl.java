package com.smartdock.profile.event;

import com.smartdock.analytics.engine.AnalyticsRollupTrigger;
import com.smartdock.profile.ProfileEventPublisher;
import com.smartdock.profile.engine.ProfileRecalcTrigger;
import com.smartdock.profile.identity.ProfileIdentityService;
import com.smartdock.profile.mapper.ProfileEventMapper;
import com.smartdock.profile.protocol.ProfileSchemaService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 事件发布实现: schema 校验 + 身份解析 + 幂等写入 + 主体登记 upsert。
 *
 * <p>每条事件独立事务(REQUIRES_NEW)。不用方法级 {@code @Transactional} 的原因:
 * {@link #publishBatch} 若直接调用同类 {@link #publish}, Spring 代理不生效,
 * 外层事务会把整批事件绑在一起——单条坏数据会拖垮整批。这里用 TransactionTemplate
 * 显式开新事务, 单条失败只跳该条。</p>
 *
 * <p>协议/Schema/身份表尚未迁移的旧库: schema 查询与身份解析均按“放行/降级”
 * 处理, 不阻断主事件链路。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
public class ProfileEventServiceImpl implements ProfileEventPublisher {

    private final ProfileEventMapper eventMapper;
    private final ProfileSchemaService schemaService;
    private final ProfileIdentityService identityService;
    private final ProfileRecalcTrigger recalcTrigger;
    private final AnalyticsRollupTrigger analyticsRollupTrigger;
    private final TransactionTemplate requiresNew;

    public ProfileEventServiceImpl(ProfileEventMapper eventMapper,
                                   ProfileSchemaService schemaService,
                                   ProfileIdentityService identityService,
                                   ProfileRecalcTrigger recalcTrigger,
                                   AnalyticsRollupTrigger analyticsRollupTrigger,
                                   PlatformTransactionManager transactionManager) {
        this.eventMapper = eventMapper;
        this.schemaService = schemaService;
        this.identityService = identityService;
        this.recalcTrigger = recalcTrigger;
        this.analyticsRollupTrigger = analyticsRollupTrigger;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean publish(ProfileEventBo bo) {
        ProfileEvent written = requiresNew.execute(status -> doPublish(bo));
        if (written == null) {
            return false;
        }
        // 事务提交后再异步重算, 避免异步线程读到未提交事件
        try {
            recalcTrigger.onEvent(written.getTenantId(), written.getSource(),
                written.getSubjectType(), written.getSubjectId());
            analyticsRollupTrigger.onEvent(written.getTenantId(), written.getSource());
        } catch (Exception e) {
            // 自动重算/汇总调度失败不影响事件账本
            log.warn("[Profile] auto recalc schedule failed subject={} err={}",
                written.getSubjectId(), e.getMessage());
        }
        return true;
    }

    @Override
    public int publishBatch(List<ProfileEventBo> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (ProfileEventBo bo : events) {
            try {
                // 逐条隔离——坏数据(超长/缺字段)只跳过该条, 不拖累整批
                if (publish(bo)) {
                    written++;
                }
            } catch (Exception e) {
                log.warn("[Profile] event publish skipped uid={} : {}", bo.getEventUid(), e.getMessage());
            }
        }
        return written;
    }

    /**
     * 管理端灌数: 区分「重复」与「校验失败」, 避免把坏数据误报成幂等命中。
     */
    public Map<String, Object> publishBatchDetailed(List<ProfileEventBo> events) {
        int written = 0;
        int duplicated = 0;
        List<Map<String, String>> failures = new ArrayList<>();
        if (events != null) {
            for (ProfileEventBo bo : events) {
                try {
                    ProfileEvent event = requiresNew.execute(status -> doPublish(bo));
                    if (event == null) {
                        duplicated++;
                        continue;
                    }
                    written++;
                    try {
                        recalcTrigger.onEvent(event.getTenantId(), event.getSource(),
                            event.getSubjectType(), event.getSubjectId());
                        analyticsRollupTrigger.onEvent(event.getTenantId(), event.getSource());
                    } catch (Exception e) {
                        log.warn("[Profile] auto recalc schedule failed subject={} err={}",
                            event.getSubjectId(), e.getMessage());
                    }
                } catch (Exception e) {
                    Map<String, String> failure = new LinkedHashMap<>();
                    failure.put("eventUid", String.valueOf(bo.getEventUid()));
                    failure.put("reason", String.valueOf(e.getMessage()));
                    failures.add(failure);
                    log.warn("[Profile] event publish skipped uid={} : {}", bo.getEventUid(), e.getMessage());
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("received", events == null ? 0 : events.size());
        out.put("written", written);
        out.put("duplicated", duplicated);
        out.put("skipped", failures.size());
        out.put("failures", failures);
        return out;
    }

    private ProfileEvent doPublish(ProfileEventBo bo) {
        ProfileEvent e = toEntity(bo);

        // 已注册 schema 才校验; 未注册/旧库/解析失败一律放行
        List<String> schemaErrors = schemaService.validate(e.getTenantId(), e.getSource(), e.getEventName(),
            e.getSchemaVersion(), bo.getProps(), bo.getContext(), bo.getTraits());
        if (!schemaErrors.isEmpty()) {
            throw new IllegalArgumentException("profile event schema invalid: " + String.join("; ", schemaErrors));
        }

        // 身份解析失败不阻断事件(链接表是索引, 事件是账本); 下一条事件会再补
        e.setSubjectId(resolveCanonicalSubject(e, bo));

        int rows = eventMapper.insertIgnore(e);
        if (rows == 0) {
            return null;
        }
        upsertSubject(e, bo);
        return e;
    }

    private String resolveCanonicalSubject(ProfileEvent e, ProfileEventBo bo) {
        Map<String, String> identifiers = new LinkedHashMap<>();
        if (bo.getIdentifiers() != null) {
            bo.getIdentifiers().forEach((key, value) -> {
                if (StringUtils.isNotBlank(key) && StringUtils.isNotBlank(value)) {
                    identifiers.put(key, value);
                }
            });
        }
        if ("user".equals(e.getSubjectType()) && StringUtils.isNotBlank(e.getSubjectId())
            && !e.getSubjectId().equals(e.getAnonymousId())) {
            identifiers.putIfAbsent("user_id", e.getSubjectId());
        }
        if (StringUtils.isNotBlank(e.getAnonymousId())) {
            identifiers.putIfAbsent("anonymous_id", e.getAnonymousId());
        }
        try {
            return identityService.resolveAndLink(e.getTenantId(), e.getSource(), e.getSubjectType(),
                e.getSubjectId(), identifiers);
        } catch (Exception ex) {
            log.warn("[Profile] identity resolve failed (non-fatal) subject={} err={}",
                e.getSubjectId(), ex.getMessage());
            return e.getSubjectId();
        }
    }

    private ProfileEvent toEntity(ProfileEventBo bo) {
        if (StringUtils.isBlank(bo.getSource())) {
            throw new IllegalArgumentException("profile event source is required");
        }
        String eventName = firstNonBlank(bo.getEventName(), bo.getEventType());
        String eventType = firstNonBlank(bo.getEventType(), bo.getEventName());
        if (StringUtils.isBlank(eventName)) {
            throw new IllegalArgumentException("profile event eventName/eventType is required");
        }
        if (StringUtils.isBlank(bo.getEventUid())) {
            throw new IllegalArgumentException("profile event eventUid is required (idempotency key)");
        }
        if (StringUtils.isBlank(bo.getSubjectId())) {
            throw new IllegalArgumentException("profile event subjectId is required");
        }
        String subjectType = StringUtils.isBlank(bo.getSubjectType()) ? "user" : bo.getSubjectType();
        String schemaVersion = StringUtils.isBlank(bo.getSchemaVersion()) ? "1-0-0" : bo.getSchemaVersion().trim();
        // 与 DDL 长度对齐——超长值在 PG 22001 之前拦下, 报可读错误
        // (subjectName 除外: 可选字段走 upsert 侧截断)
        checkLen("source", bo.getSource(), 32);
        checkLen("eventName", eventName, 64);
        checkLen("eventType", eventType, 64);
        checkLen("eventUid", bo.getEventUid(), 128);
        checkLen("subjectType", subjectType, 32);
        checkLen("subjectId", bo.getSubjectId(), 64);
        checkLen("anonymousId", bo.getAnonymousId(), 128);
        checkLen("sessionId", bo.getSessionId(), 128);
        checkLen("schemaVersion", schemaVersion, 32);

        ProfileEvent e = new ProfileEvent();
        e.setEventId(IdWorker.getId());
        e.setTenantId(currentTenant());
        e.setSource(bo.getSource());
        e.setEventType(eventType);
        e.setEventName(eventName);
        e.setEventUid(bo.getEventUid());
        e.setSubjectType(subjectType);
        e.setSubjectId(bo.getSubjectId());
        e.setAnonymousId(bo.getAnonymousId());
        e.setSessionId(bo.getSessionId());
        e.setSchemaVersion(schemaVersion);
        e.setOccurredTime(bo.getOccurredTime() != null ? bo.getOccurredTime() : new Date());
        e.setProps(toJson(bo.getProps()));
        e.setContext(toJson(bo.getContext()));
        e.setTraits(toJson(bo.getTraits()));
        e.setIdentifiers(toJson(storedIdentifiers(bo, subjectType)));
        return e;
    }

    /**
     * 事件行里保留的标识符快照: 调用方显式传入的 + 由 subject 推导的。
     * 登录事件的 user_id 保持“登录前的原始值”, 规范化后的主体 ID 单独落在 subject_id。
     */
    private Map<String, String> storedIdentifiers(ProfileEventBo bo, String subjectType) {
        Map<String, String> ids = new LinkedHashMap<>();
        if (bo.getIdentifiers() != null) {
            bo.getIdentifiers().forEach((key, value) -> {
                if (StringUtils.isNotBlank(key) && StringUtils.isNotBlank(value)) {
                    ids.put(key, value);
                }
            });
        }
        if ("user".equals(subjectType) && StringUtils.isNotBlank(bo.getSubjectId())
            && !bo.getSubjectId().equals(bo.getAnonymousId())) {
            ids.putIfAbsent("user_id", bo.getSubjectId());
        }
        if (StringUtils.isNotBlank(bo.getAnonymousId())) {
            ids.putIfAbsent("anonymous_id", bo.getAnonymousId());
        }
        return ids;
    }

    private void upsertSubject(ProfileEvent e, ProfileEventBo bo) {
        try {
            ProfileSubject s = new ProfileSubject();
            s.setId(IdWorker.getId());
            s.setTenantId(e.getTenantId());
            s.setSource(e.getSource());
            s.setSubjectType(e.getSubjectType());
            s.setSubjectId(e.getSubjectId());
            s.setDisplayName(resolveDisplayName(bo));
            s.setFirstEventTime(e.getOccurredTime());
            s.setLastEventTime(e.getOccurredTime());
            s.setProperties(toJson(bo.getTraits()));
            eventMapper.upsertSubject(s);
        } catch (Exception ex) {
            // 主体登记失败不阻断事件(事件是账本, 登记是索引; 下一条事件会再补)
            log.warn("[Profile] subject upsert failed (non-fatal) subject={} err={}",
                e.getSubjectId(), ex.getMessage());
        }
    }

    private String resolveDisplayName(ProfileEventBo bo) {
        String displayName = bo.getSubjectName();
        if (StringUtils.isBlank(displayName) && bo.getTraits() != null) {
            Object name = bo.getTraits().get("name");
            if (name == null) {
                name = bo.getTraits().get("nickName");
            }
            if (name == null) {
                name = bo.getTraits().get("displayName");
            }
            if (name != null && StringUtils.isNotBlank(String.valueOf(name))) {
                displayName = String.valueOf(name);
            }
        }
        // 超长显示名截断而非拒件: 可选元数据不值得拖累事件本体
        return displayName != null && displayName.length() > 128
            ? displayName.substring(0, 128) : displayName;
    }

    private String toJson(Map<String, ?> map) {
        return JsonUtils.toJsonString(map == null || map.isEmpty() ? Map.of() : map);
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.isNotBlank(first) ? first : second;
    }

    private void checkLen(String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw new IllegalArgumentException("profile event " + field + " exceeds max length " + max);
        }
    }

    private String currentTenant() {
        // 口径对齐租户拦截器(dynamic > static), LoginHelper 兜底
        try {
            String t = TenantHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 无租户上下文
        }
        try {
            return LoginHelper.getTenantId();
        } catch (Exception e) {
            // 非登录上下文(定时任务/系统灌数)缺省默认租户
            return "000000";
        }
    }
}
