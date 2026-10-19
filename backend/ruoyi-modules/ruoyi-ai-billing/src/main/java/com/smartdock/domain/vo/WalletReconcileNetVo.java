package com.smartdock.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 钱包对账-余额 vs 流水净额行(批C 2026-09-24, WalletReconcileJob 专用)。
 *
 * @author smartdock
 */
@Data
public class WalletReconcileNetVo {

    /** 钱包ID */
    private Long walletId;

    /** 租户ID */
    private String tenantId;

    /** 钱包当前余额(dcs_tenant_wallet.balance_amount) */
    private BigDecimal balanceAmount;

    /** 流水净额(Σ IN金额 - Σ OUT金额, 无流水为 0) */
    private BigDecimal ledgerNet;
}
