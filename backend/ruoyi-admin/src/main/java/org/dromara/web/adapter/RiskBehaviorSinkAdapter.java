package org.dromara.web.adapter;

import com.smartdock.profile.ProfileEventPublisher;
import com.smartdock.profile.event.ProfileEventBo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.smartdock.risk.support.RiskBehaviorSink;
import org.dromara.common.core.service.UserService;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 风控行为埋点端口适配器（批3 微核心化，2026-10-18）。
 *
 * <p>旧 dcs 的 BehaviorEventServiceImpl（dcs_behavior_event 表底座）随模块退役。
 * 风控侧 RiskEventCollector 只写不读行为流水，行为账本在新架构里就是画像事件表
 * profile_event——本适配器把 record() 翻译成 source=risk 的画像事件，
 * 登录监听/RFM/风控埋点天然进画像重算口径。</p>
 *
 * <p>落位裁决（批4 定稿）：适配器放 admin 组合根是<b>终位</b>而非过渡位——risk 保持
 * 零业务依赖（不依赖 profile-engine），缝合只发生在装配层；同包还有 MissionReviewProbe /
 * WalletAlertNotice / FileReviewNotice 四件套。语义与旧实现一致：
 * 同步写入、不随调用方事务回滚、userId 空=内部跳过；事件失败只 warn 不抛
 * （埋点不属于业务主事务）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskBehaviorSinkAdapter implements RiskBehaviorSink {

    private final ProfileEventPublisher eventPublisher;
    private final UserService userService;

    @Override
    public void record(String tenantId, Long userId, String eventType, Date eventTime,
                       String sourceIp, Long missionId, Long traceId, String metadata) {
        if (userId == null) {
            return;
        }
        if (tenantId == null || tenantId.isBlank()) {
            // 三审 glm53 P2-10: 空租户动态包裹会让 INSERT 违反 NOT NULL 被吞成静默丢失——显式 warn 留痕
            log.warn("[RiskBehaviorSink] 无租户上下文的行为事件被丢弃 user={} type={}", userId, eventType);
            return;
        }
        ProfileEventBo e = new ProfileEventBo();
        e.setSource(com.smartdock.profile.event.ProfileSources.UNIFIED);
        // 三审 dspro P2-1: 键里用去歧义后的类型(空值落 RISK), 与 eventType 存储值一致;
        // 纳入 missionId 区分同毫秒同用户同类型的不同业务对象
        String typeKey = eventType == null || eventType.isBlank() ? "RISK" : eventType;
        e.setEventType(typeKey);
        // 行为流水高频且逐条独立：时间戳进自然键保证不撞车（同毫秒同用户同类型同任务撞车=幂等跳过一条，可接受）
        long at = eventTime == null ? System.currentTimeMillis() : eventTime.getTime();
        e.setEventUid("risk:" + typeKey + ":" + userId + ":"
            + (missionId == null ? "-" : missionId) + ":" + at);
        e.setSubjectType("user");
        e.setSubjectId(String.valueOf(userId));
        // 主体名称是展示元数据: 查询失败不阻断埋点, 画像侧保留旧值
        String nickName = TenantScopes.callInTenant(tenantId, () -> {
            try {
                return userService.selectNicknameById(userId);
            } catch (Exception ex) {
                return null;
            }
        });
        if (nickName != null && !nickName.isBlank()) {
            e.setSubjectName(nickName);
        }
        e.setOccurredTime(eventTime == null ? new Date() : eventTime);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("sourceIp", sourceIp);
        props.put("missionId", missionId);
        props.put("traceId", traceId);
        if (metadata != null && !metadata.isBlank()) {
            props.put("metadata", metadata);
        }
        e.setProps(props);
        try {
            TenantScopes.runInTenant(tenantId, () -> eventPublisher.publish(e));
        } catch (Exception ex) {
            log.warn("[RiskBehaviorSink] 行为事件写入失败(不拦风控主流程) tenant={} user={} type={}: {} {}",
                tenantId, userId, eventType, ex.getClass().getSimpleName(), ex.getMessage());
        }
    }
}
