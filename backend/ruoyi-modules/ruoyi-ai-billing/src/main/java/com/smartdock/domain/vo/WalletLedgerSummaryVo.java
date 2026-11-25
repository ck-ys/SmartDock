package com.smartdock.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 钱包流水按筛选条件的合计（2026-11-25 钱包流水批 Q5）。
 *
 * <p>前端旧口径是「当前页收入/支出」——换个页数字就变，用户以为是同一个数在跳。
 * 这里按<b>当前筛选条件</b>全量聚合：收入合计 / 支出合计 / 净额 / 笔数，与列表同口径。</p>
 *
 * @author smartdock
 */
@Data
public class WalletLedgerSummaryVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 收入合计（direction = IN 的金额和） */
    private BigDecimal totalIn;

    /** 支出合计（direction = OUT 的金额和） */
    private BigDecimal totalOut;

    /** 净额 = totalIn - totalOut */
    private BigDecimal net;

    /** 符合筛选条件的流水笔数 */
    private long count;
}
