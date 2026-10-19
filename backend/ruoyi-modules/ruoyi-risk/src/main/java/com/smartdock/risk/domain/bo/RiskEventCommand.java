package com.smartdock.risk.domain.bo;

import java.util.Date;

/**
 * Explicit, tenant-bound risk event input.
 *
 * @param hitWord 代表命中词(内容类事件专用: BLOCK级优先,无则首个REVIEW级词), 供大盘TOP命中词聚合; 其他事件传 null
 */
public record RiskEventCommand(
    String tenantId,
    String eventType,
    String source,
    Long userId,
    String ip,
    String userAgent,
    Long refId,
    String detail,
    String idempotencyKey,
    Date occurredTime,
    String hitWord
) {
    public RiskEventCommand {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
    }
}
