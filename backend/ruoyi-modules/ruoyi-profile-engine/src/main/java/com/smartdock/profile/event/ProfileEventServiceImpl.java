package com.smartdock.profile.event;

import com.smartdock.profile.ProfileEventPublisher;
import com.smartdock.profile.mapper.ProfileEventMapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 事件发布实现: 幂等写入 + 主体登记 upsert。
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProfileEventServiceImpl implements ProfileEventPublisher {

    private final ProfileEventMapper eventMapper;

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class,
        propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public boolean publish(ProfileEventBo bo) {
        ProfileEvent e = toEntity(bo);
        int rows = eventMapper.insertIgnore(e);
        if (rows == 0) {
            return false;
        }
        upsertSubject(e, bo.getSubjectName());
        return true;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class,
        propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public int publishBatch(List<ProfileEventBo> events) {
        if (events == null || events.isEmpty()) {
            return 0;
        }
        int written = 0;
        for (ProfileEventBo bo : events) {
            try {
                // dspro P1-4 回修: 逐条隔离——坏数据(超长/缺字段)只跳过该条, 不拖累整批 500
                if (publish(bo)) {
                    written++;
                }
            } catch (Exception e) {
                log.warn("[Profile] event publish skipped uid={} : {}", bo.getEventUid(), e.getMessage());
            }
        }
        return written;
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
        // dspro P1-4 回修: 与 DDL 长度对齐——超长值在 PG 22001 之前拦下, 报可读错误
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
            // 超长显示名截断而非拒件(dspro P1-4): 可选元数据不值得拖累事件本体
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
        // dspro P2-2 回修: 口径对齐租户拦截器(dynamic > static), LoginHelper 兜底
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
