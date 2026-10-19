package com.smartdock.domain.bo;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 钱包手动操作(充值/扣减/冻结/解冻) BO。
 *
 * @author smartdock
 */
@Data
public class WalletOperationBo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotNull(message = "金额不能为空")
    @DecimalMin(value = "0.01", message = "金额必须大于0")
    private BigDecimal amount;

    @NotBlank(message = "操作原因不能为空")
    private String reason;

    /** 幂等键(可选, 不填则自动生成) */
    private String idempotencyKey;
}
