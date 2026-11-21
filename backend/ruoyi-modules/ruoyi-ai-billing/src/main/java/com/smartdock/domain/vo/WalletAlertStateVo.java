package com.smartdock.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.smartdock.domain.WalletAlertState;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 钱包告警状态视图对象。
 *
 * @author smartdock
 */
@Data
@AutoMapper(target = WalletAlertState.class)
public class WalletAlertStateVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long alertId;
    private String tenantId;
    private Long walletId;
    private String alertType;
    private Date lastAlertTime;
    private BigDecimal thresholdAmount;
}
