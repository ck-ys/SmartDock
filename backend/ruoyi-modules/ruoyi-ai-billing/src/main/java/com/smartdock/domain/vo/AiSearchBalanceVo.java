package com.smartdock.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 查余额结果（Q16=1）。
 *
 * <p>三态：{@code OK} 带金额；{@code UNSUPPORTED} = 这家厂商没这接口 / 没填密钥；
 * {@code ERROR} = 查了但失败。<b>后两态 {@code balanceAmount} 必须是 null</b>——
 * 界面显示「查不到」，绝不显示 0（0 会被读成「账户没钱了」）。</p>
 */
@Data
public class AiSearchBalanceVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** OK / UNSUPPORTED / ERROR */
    private String status;
    /** 只有 OK 时有值 */
    private BigDecimal balanceAmount;
    private String balanceCurrency;
    /** 给人看的一句话（失败原因 / 去哪看） */
    private String message;
}
