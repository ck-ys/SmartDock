package com.smartdock.domain.bo;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 钱包阈值修改 BO。
 *
 * @author smartdock
 */
@Data
public class WalletThresholdBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "低余额阈值不能为空")
    private BigDecimal lowBalanceThreshold;
}
