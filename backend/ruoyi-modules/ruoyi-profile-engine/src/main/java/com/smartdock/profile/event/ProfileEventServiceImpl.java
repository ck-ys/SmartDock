package com.smartdock.profile.event;

import com.smartdock.profile.ProfileEventPublisher;
import com.smartdock.profile.mapper.ProfileEventMapper;
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

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 事件发布实现: 幂等写入 + 主体登记 upsert。
 *
 * <p>每条事件独立事务(REQUIRES_NEW)。不用方法级 {@code @Transactional} 的原因:
 * {@link #publishBatch} 若直接调用同类 {@link #publish}, Spring 代理不生效,
 * 外层事务会把整批事件绑在一起——单条坏数据会拖垮整批。这里用 TransactionTemplate
 * 显式开新事务, 单条失败只跳该条。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
public class ProfileEventServiceImpl implements ProfileEventPublisher {

    private final ProfileEventMapper eventMapper;
    private final TransactionTemplate requiresNew;

    public ProfileEventServiceImpl(ProfileEventMapper eventMapper, PlatformTransactionManager transactionManager) {
        this.eventMapper = eventMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public boolean publish(ProfileEventBo bo) {
        Boolean written = requiresNew.execute(status -> doPublish(bo));
        return Boolean.TRUE.equals(written);
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

    private boolean doPublish(ProfileEventBo bo) {
        ProfileEvent e = toEntity(bo);
        int rows = eventMapper.insertIgnore(e);
        if (rows == 0) {
            return false;
        }
        upsertSubject(e, bo.getSubjectName());
        return true;
    }

    private ProfileEvent toEntity(ProfileEventBo bo) {
        if (StringUtils.isBlank(bo.getSource())) {
            throw new IllegalArgumentException("profile event source is required");
        }
        if (StringUtils.isBlank(bo.getEventType())) {
            throw new IllegalArgumentException("profile event eventType is required");
        }
        if (StringUtils.isBlank(bo.getEventUid())) {
            throw new IllegalArgumentException("profile event eventUid is required (idempotency key)");
        }
        if (StringUtils.isBlank(bo.getSubjectId())) {
            throw new IllegalArgumentException("profile event subjectId is required");
        }
        // 与 DDL 长度对齐——超长值在 PG 22001 之前拦下, 报可读错误
        // (subjectName 除外: 可选字段走 upsert 侧截断)
        checkLen("source", bo.getSource(), 32);
        checkLen("eventType", bo.getEventType(), 64);
        checkLen("eventUid", bo.getEventUid(), 128);
        checkLen("subjectType", bo.getSubjectType(), 32);
        checkLen("subjectId", bo.getSubjectId(), 64);
        ProfileEvent e = new ProfileEvent();
        e.setEventId(IdWorker.getId());
        e.setTenantId(currentTenant());
        e.setSource(bo.getSource());
        e.setEventType(bo.getEventType());
        e.setEventUid(bo.getEventUid());
        e.setSubjectType(StringUtils.isBlank(bo.getSubjectType()) ? "user" : bo.getSubjectType());
        e.setSubjectId(bo.getSubjectId());
        e.setOccurredTime(bo.getOccurredTime() != null ? bo.getOccurredTime() : new Date());
        Map<String, Object> props = bo.getProps() == null ? Map.of() : bo.getProps();
        e.setProps(JsonUtils.toJsonString(props));
        return e;
    }

    private void upsertSubject(ProfileEvent e, String displayName) {
        try {
            ProfileSubject s = new ProfileSubject();
            s.setId(IdWorker.getId());
            s.setTenantId(e.getTenantId());
            s.setSource(e.getSource());
            s.setSubjectType(e.getSubjectType());
            s.setSubjectId(e.getSubjectId());
            // 超长显示名截断而非拒件: 可选元数据不值得拖累事件本体
            s.setDisplayName(displayName != null && displayName.length() > 128
                ? displayName.substring(0, 128) : displayName);
            s.setFirstEventTime(e.getOccurredTime());
            s.setLastEventTime(e.getOccurredTime());
            eventMapper.upsertSubject(s);
        } catch (Exception ex) {
            // 主体登记失败不阻断事件(事件是账本, 登记是索引; 下一条事件会再补)
            log.warn("[Profile] subject upsert failed (non-fatal) subject={} err={}",
                e.getSubjectId(), ex.getMessage());
        }
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
