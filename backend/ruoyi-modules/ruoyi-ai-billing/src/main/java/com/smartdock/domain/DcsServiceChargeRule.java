package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * 服务计费规则(余额不足兜底策略)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_service_charge_rule")
public class DcsServiceChargeRule extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 规则ID */
    @TableId(value = "rule_id")
    private Long ruleId;

    /** 租户ID */
    private String tenantId;

    /** 服务编码 */
    private String serviceCode;

    /** 服务名称 */
    private String serviceName;

    /** AI_USAGE_COST / FIXED_AMOUNT / FREE */
    private String chargeMode;

    /** BLOCK / ALGORITHM_FALLBACK / ALLOW_FREE / ADMIN_OVERRIDE */
    private String fallbackPolicy;

    /** 钱包范围 */
    private String walletScope;

    /** 固定金额 */
    private BigDecimal fixedAmount;

    /** 低余额阈值 */
    private BigDecimal lowBalanceThreshold;

    /** 是否启用 */
    private String enabled;

    /** 排序号 */
    private Integer sortOrder;

    /** 备注 */
    private String remark;

    /** 删除标志 */
    @TableLogic
    private String delFlag;
}
