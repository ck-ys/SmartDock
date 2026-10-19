package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 钱包低余额告警状态(按北京日历天判重, 同日只发一次)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_wallet_alert_state")
public class DcsWalletAlertState extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 告警ID */
    @TableId(value = "alert_id")
    private Long alertId;

    /** 租户ID */
    private String tenantId;

    /** 钱包ID */
    private Long walletId;

    /** LOW_BALANCE / EXHAUSTED */
    private String alertType;

    /** 最近告警时间 */
    private Date lastAlertTime;

    /** 阈值金额 */
    private BigDecimal thresholdAmount;

    /** 删除标志 */
    @TableLogic
    private String delFlag;
}
