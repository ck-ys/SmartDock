package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * 租户钱包(主钱包/子钱包)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_tenant_wallet")
public class DcsTenantWallet extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 钱包ID */
    @TableId(value = "wallet_id")
    private Long walletId;

    /** 租户ID */
    private String tenantId;

    /** MAIN=主钱包, SUB=子钱包 */
    private String walletType;

    /** 父钱包ID */
    private Long parentWalletId;

    /** 余额 */
    private BigDecimal balanceAmount;

    /** 冻结金额 */
    private BigDecimal frozenAmount;

    /** 币种 */
    private String currency;

    /** ACTIVE / FROZEN / DISABLED */
    private String status;

    /** 低余额阈值 */
    private BigDecimal lowBalanceThreshold;

    /** 是否允许负值 */
    private String negativeAllowed;

    /** 备注 */
    private String remark;

    /** 乐观锁版本号 */
    @Version
    private Integer version;

    /** 删除标志 */
    @TableLogic
    private String delFlag;
}
