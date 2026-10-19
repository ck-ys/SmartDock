package com.smartdock.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.smartdock.domain.DcsServiceChargeRule;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * 服务计费规则视图对象。
 *
 * @author smartdock
 */
@Data
@AutoMapper(target = DcsServiceChargeRule.class)
public class DcsServiceChargeRuleVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 规则ID */
    private Long ruleId;
    private String tenantId;
    /** 服务编码(如 AI 调用服务标识) */
    private String serviceCode;
    /** 服务名称 */
    private String serviceName;
    /** 计费方式: AI_USAGE_COST=按 AI 用量计费, FIXED_AMOUNT=固定金额, FREE=免费 */
    private String chargeMode;
    /** 余额不足兜底策略: BLOCK=拦截, ALGORITHM_FALLBACK=降级, ALLOW_FREE=放行免费, ADMIN_OVERRIDE=管理员放行 */
    private String fallbackPolicy;
    /** 适用钱包范围(主钱包/子钱包) */
    private String walletScope;
    /** 固定计费金额(chargeMode=FIXED_AMOUNT 时生效) */
    private BigDecimal fixedAmount;
    /** 低余额阈值(余额低于此值触发兜底策略) */
    private BigDecimal lowBalanceThreshold;
    /** 是否启用("1"=启用, "0"=停用) */
    private String enabled;
    /** 排序号(同服务多规则时的优先级) */
    private Integer sortOrder;
    private String remark;
    private Date createTime;
    private Date updateTime;
}
