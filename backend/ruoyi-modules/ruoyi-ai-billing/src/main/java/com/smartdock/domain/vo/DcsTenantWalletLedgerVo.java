package com.smartdock.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.smartdock.domain.DcsTenantWalletLedger;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 租户钱包流水视图对象。
 *
 * @author smartdock
 */
@Data
@AutoMapper(target = DcsTenantWalletLedger.class)
public class DcsTenantWalletLedgerVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 流水ID */
    private Long ledgerId;
    private String tenantId;
    /** 钱包ID */
    private Long walletId;
    /** 业务类型: RECHARGE=充值, DEDUCT=扣费, REFUND=退款, FREEZE=冻结, UNFREEZE=解冻, ADJUST=手动调整 */
    private String bizType;
    /** 业务ID(关联的业务单据, 如订单/消耗台账ID) */
    private Long bizId;
    /** 服务编码(扣费对应的服务, 如 AI 调用) */
    private String serviceCode;
    /** 服务编码中文标签（业务批 2026-10-15 Q2：后端统一下发，新服务码不再依赖前端映射） */
    private String serviceCodeLabel;
    /** 方向: IN=入账(余额增加), OUT=出账(余额减少) */
    private String direction;
    /** 金额(正数, 实际增减方向由 direction 决定) */
    private BigDecimal amount;
    /** 币种 */
    private String currency;
    /** 操作前余额 */
    private BigDecimal balanceBefore;
    /** 操作后余额 */
    private BigDecimal balanceAfter;
    /** 幂等键(防重复入账) */
    private String idempotencyKey;
    /** 操作人ID */
    private Long operatorId;
    /** 扩展业务载荷 JSON */
    private String extraJson;
    private Date createTime;
}
