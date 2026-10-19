package com.smartdock.risk.service;

import cn.hutool.crypto.digest.DigestUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.risk.config.DcsRolloutProperties;
import com.smartdock.risk.domain.DcsRiskEvent;
import com.smartdock.risk.domain.DcsRiskRule;
import com.smartdock.risk.domain.DcsRiskWhitelist;
import com.smartdock.risk.domain.bo.RiskEventCommand;
import com.smartdock.risk.mapper.DcsRiskEventMapper;
import com.smartdock.risk.mapper.DcsRiskRuleMapper;
import com.smartdock.risk.mapper.DcsRiskWhitelistMapper;
import com.smartdock.risk.support.RiskBehaviorSink;
import org.springframework.scheduling.annotation.Async;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Date;

/**
 * 风险事件采集器(统一入口)。
 *
 * <p><b>核心职责</b>: 把分散在系统各处的风险事件(敏感词命中/举报/登录失败/高频接口/异常上传)
 * 统一记录到 dcs_risk_event 表, 供后续 RiskScoreService 算分。</p>
 *
 * <p><b>白名单</b>: 调用方传入的 userId/ip 若在白名单内, 直接 return 不记录。</p>
 *
 * <p><b>异步</b>: 用 @Async 避免阻塞主流程(如发消息), 即使风控 DB 写失败也不影响业务。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RiskEventCollector {

    /** 风险事件 Mapper(统一落库) */
    private final DcsRiskEventMapper eventMapper;
    /** 风险规则 Mapper(匹配事件类型) */
    private final DcsRiskRuleMapper ruleMapper;
    /** 风控白名单 Mapper(命中则跳过) */
    private final DcsRiskWhitelistMapper whitelistMapper;
    /** 行为埋点：风控事件落库后同步写一条 RISK 行为到 RFM 流水 */
    private final RiskBehaviorSink behaviorEventService;

    @Autowired(required = false)
    private DcsRolloutProperties rolloutProperties;

    /**
     * 异步记录一条风险事件(白名单/幂等键去重 + 同步落 RISK 行为埋点); 失败不影响业务主流程。
     * @param command 风险事件命令(tenantId 由源线程捕获, 此处用 TenantHelper.dynamic 恢复)
     */
    @Async
    public void record(RiskEventCommand command) {
        if (rolloutProperties != null && !rolloutProperties.isEventCollectionEnabled()) {
            log.info("[RiskEvent] collection disabled tenant={} type={} source={} ref={}",
                command.tenantId(), command.eventType(), command.source(), command.refId());
            return;
        }
        try {
            TenantHelper.dynamic(command.tenantId(), () -> recordInTenant(command));
        } catch (Exception e) {
            // 风控记录失败不能影响业务主流程
            log.warn("[RiskEvent] collect failed tenant={} type={} source={} ref={} user={} error={}",
                command.tenantId(), command.eventType(), command.source(), command.refId(),
                command.userId(), e.getMessage());
        }
    }

    private void recordInTenant(RiskEventCommand command) {
        if (command.idempotencyKey() != null && !command.idempotencyKey().isBlank()) {
            Long duplicateCount = eventMapper.selectCount(new LambdaQueryWrapper<DcsRiskEvent>()
                .eq(DcsRiskEvent::getIdempotencyKey, command.idempotencyKey()));
            if (duplicateCount != null && duplicateCount > 0) {
                log.info("[RiskEvent] idempotent skip tenant={} type={} trace={}",
                    command.tenantId(), command.eventType(), traceId(command.idempotencyKey()));
                return;
            }
        }
        if (isWhitelisted("IP", command.ip())
            || (command.userId() != null && isWhitelisted("USER", String.valueOf(command.userId())))) {
            log.info("[RiskEvent] whitelist skip tenant={} type={} user={} source={} ref={}",
                command.tenantId(), command.eventType(), command.userId(), command.source(), command.refId());
            return;
        }
        DcsRiskRule rule = ruleMapper.selectOne(new LambdaQueryWrapper<DcsRiskRule>()
            .eq(DcsRiskRule::getEventType, command.eventType())
            .eq(DcsRiskRule::getEnabled, "1")
            .last("LIMIT 1"));
        if (rule == null) {
            log.debug("[RiskEvent] no enabled rule tenant={} type={} source={} ref={}",
                command.tenantId(), command.eventType(), command.source(), command.refId());
            return;
        }
        Date now = new Date();
        DcsRiskEvent event = new DcsRiskEvent();
        event.setTenantId(command.tenantId());
        event.setUserId(command.userId());
        event.setIp(command.ip());
        event.setUserAgent(command.userAgent());
        event.setEventType(command.eventType());
        event.setSource(command.source());
        event.setRefId(command.refId());
        event.setDetail(command.detail());
        event.setHitWord(command.hitWord());
        event.setIdempotencyKey(command.idempotencyKey() == null || command.idempotencyKey().isBlank()
            ? null : command.idempotencyKey());
        event.setRiskScore(rule.getWeight());
        event.setOccurredTime(command.occurredTime());
        event.setCreatedTime(now);
        eventMapper.insert(event);
        log.info("[RiskEvent] collected tenant={} event={} rule={} type={} user={} score={} source={} ref={} trace={}",
            command.tenantId(), event.getEventId(), rule.getRuleId(), command.eventType(), command.userId(),
            rule.getWeight(), command.source(), command.refId(), traceId(command.idempotencyKey()));

        // 行为埋点：风控事件同步落一条 RISK 行为到 RFM 流水（已在 @Async + TenantHelper.dynamic 内）
        // userId 可能为空（纯 IP 风控），BehaviorEventService 内部会跳过 null userId
        if (command.userId() != null) {
            try {
                behaviorEventService.record(
                    command.tenantId(),
                    command.userId(),
                    "RISK",
                    command.occurredTime() == null ? now : command.occurredTime(),
                    command.ip(),
                    null,
                    null,
                    "{\"riskType\":\"" + command.eventType() + "\",\"source\":\"" + command.source() + "\"}"
                );
            } catch (Exception be) {
                // 行为埋点失败不影响风控主流程
                log.debug("[RiskEvent] behavior record failed user={} type={} error={}",
                    command.userId(), command.eventType(), be.getMessage());
            }
        }
    }

    /**
     * 判断某 IP/用户/域名是否在白名单。
     */
    public boolean isWhitelisted(String listType, String value) {
        if (value == null || value.isBlank()) return false;
        Long cnt = whitelistMapper.selectCount(new LambdaQueryWrapper<DcsRiskWhitelist>()
            .eq(DcsRiskWhitelist::getListType, listType)
            .eq(DcsRiskWhitelist::getValue, value)
            .eq(DcsRiskWhitelist::getEnabled, "1"));
        return cnt != null && cnt > 0;
    }

    private String traceId(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return "-";
        return DigestUtil.sha256Hex(idempotencyKey).substring(0, 12);
    }
}
