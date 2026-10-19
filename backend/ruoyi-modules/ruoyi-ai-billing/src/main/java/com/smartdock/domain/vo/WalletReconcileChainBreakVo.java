package com.smartdock.domain.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 钱包对账-流水链断裂行(批C 2026-09-24, WalletReconcileJob 专用)。
 *
 * <p>同一钱包按 ledger_id 排序, 本笔 balance_before ≠ 前笔 balance_after 即断裂
 * (意味着两笔之间余额被绕过流水改过)。</p>
 *
 * @author smartdock
 */
@Data
public class WalletReconcileChainBreakVo {

    /** 断裂行流水ID(balance_before 对不上的那笔) */
    private Long ledgerId;

    /** 钱包ID */
    private Long walletId;

    /** 租户ID */
    private String tenantId;

    /** 本笔操作前余额 */
    private BigDecimal balanceBefore;

    /** 前笔操作后余额(LAG 取得) */
    private BigDecimal prevAfter;
}
