package com.smartdock.risk.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;
import java.util.Date;

/**
 * 风控事件流水(只追加, 不修改)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_risk_event")
public class DcsRiskEvent extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "event_id")
    private Long eventId;

    private Long userId;

    private String ip;

    private String userAgent;

    /** SENSITIVE_WORD / REPORTED / LOGIN_FAIL / HIGH_FREQ_API / UPLOAD_ABUSE */
    private String eventType;

    /** CHAT / LOGIN / API / UPLOAD */
    private String source;

    private Long refId;

    private String detail;

    /** 代表命中词(BLOCK级优先,无则首个REVIEW级词),供大盘TOP命中词聚合; 非内容类事件为NULL(2026-09-22) */
    private String hitWord;

    /** Tenant-scoped business key used to ignore retry duplicates. */
    private String idempotencyKey;

    /** 本次事件贡献的风险分 */
    private Integer riskScore;

    /** Time when the source behavior happened. */
    private Date occurredTime;

    private Date createdTime;
}
