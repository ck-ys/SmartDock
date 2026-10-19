package com.smartdock.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.smartdock.domain.DcsTenantWallet;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 租户钱包视图对象。
 *
 * @author smartdock
 */
@Data
@AutoMapper(target = DcsTenantWallet.class)
public class DcsTenantWalletVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 钱包ID */
    private Long walletId;
    private String tenantId;
    /** 钱包类型: MAIN=主钱包, SUB=子钱包 */
    private String walletType;
    /** 父钱包ID(子钱包指向其主钱包) */
    private Long parentWalletId;
    /** 余额(单位随币种, 通用为元) */
    private BigDecimal balanceAmount;
    /** 冻结金额(不可用, 不计入可用余额) */
    private BigDecimal frozenAmount;
    /** 可用余额(余额 - 冻结金额) */
    private BigDecimal availableAmount;
    /** 币种 */
    private String currency;
    /** 钱包状态: ACTIVE=正常, FROZEN=冻结, DISABLED=停用 */
    private String status;
    /** 低余额阈值(余额低于此值触发告警) */
    private BigDecimal lowBalanceThreshold;
    /** 是否允许负值("1"=允许透支, "0"=不允许) */
    private String negativeAllowed;
    private String remark;
    /** 乐观锁版本号 */
    private Integer version;
    private Date createTime;
    private Date updateTime;
}
