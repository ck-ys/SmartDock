package com.smartdock.risk.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * 风控白名单(IP/用户/域名, 白名单内不监控不触发验证码)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_risk_whitelist")
public class DcsRiskWhitelist extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "whitelist_id")
    private Long whitelistId;

    /** IP / USER / DOMAIN */
    private String listType;

    private String value;

    private String reason;

    private String enabled;
}
