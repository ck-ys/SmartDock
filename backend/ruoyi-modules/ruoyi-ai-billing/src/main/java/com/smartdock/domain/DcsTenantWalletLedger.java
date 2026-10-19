package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * 租户钱包流水(只增不改审计流水)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_tenant_wallet_ledger")
public class DcsTenantWalletLedger extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 流水ID */
    @TableId(value = "ledger_id")
    private Long ledgerId;

    /** 租户ID */
    private String tenantId;

    /** 钱包ID */
    private Long walletId;

    /** RECHARGE / DEDUCT / REFUND / FREEZE / UNFREEZE / ADJUST */
    private String bizType;

    /** 业务ID */
    private Long bizId;

    /** 服务编码 */
    private String serviceCode;

    /** IN=入账 / OUT=出账 */
    private String direction;

    /** 金额 */
    private BigDecimal amount;

    /** 币种 */
    private String currency;

    /** 操作前余额 */
    private BigDecimal balanceBefore;

    /** 操作后余额 */
    private BigDecimal balanceAfter;

    /** 幂等键 */
    private String idempotencyKey;

    /** 操作人ID */
    private Long operatorId;

    /** 扩展JSON */
    private String extraJson;

    /** 删除标志 */
    @TableLogic
    private String delFlag;
}
