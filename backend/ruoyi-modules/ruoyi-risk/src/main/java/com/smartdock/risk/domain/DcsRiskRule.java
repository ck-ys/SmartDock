package com.smartdock.risk.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.tenant.core.TenantEntity;

import java.io.Serial;

/**
 * 风控规则配置(每种事件类型的权重和阈值)。
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_risk_rule")
public class DcsRiskRule extends TenantEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableId(value = "rule_id")
    private Long ruleId;

    /** SENSITIVE_WORD / REPORTED / LOGIN_FAIL / ... */
    private String eventType;

    /** EVENT for an independent event bucket; GLOBAL for the aggregate track. */
    private String ruleScope;

    /** 单次事件贡献的分值 */
    private Integer weight;

    private String enabled;

    /** 累计分达到此值触发 L1(简单图形码) */
    private Integer thresholdL1;

    private Integer thresholdL2;

    private Integer thresholdL3;

    private Integer thresholdL4;

    /** 滑动窗口(分钟) */
    private Integer windowMinutes;

    private String description;
}
