package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * AI 调用消耗台账。
 *
 * <p>记录每次 AI 调用的消耗金额(按调用次数 × 固定单价), 供租户管理页和独立明细页展示。</p>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_ai_usage")
public class DcsAiUsage extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 消耗ID */
    @TableId(value = "usage_id")
    private Long usageId;

    /** 租户ID */
    private String tenantId;

    /** 关联配置ID */
    private Long configId;

    /** 模型厂商 */
    private String provider;

    /** 模型名称 */
    private String modelName;

    /** 调用方: PROFILE_DIAGNOSE / FIELD_GENERATE / SCORE_ENHANCE / TEST_CONNECTION / SPEED_PROBE */
    private String caller;

    /** 场景: GENERAL / TEACHER_EVALUATION / ... */
    private String scene;

    /** 操作人ID */
    private Long operatorId;

    /** 调用耗时(毫秒) */
    private Integer latencyMs;

    /** 本次消耗金额(元) */
    private BigDecimal costAmount;

    /** 官方 token 原始成本(不含服务费) */
    private BigDecimal baseCostAmount;

    /** 服务技术费率百分比 */
    private BigDecimal serviceFeeRate;

    /** 服务技术费金额 */
    private BigDecimal serviceFeeAmount;

    /** 币种 */
    private String currency;

    /** 计费模式 */
    private String billingMode;

    /** 定价快照 */
    private String pricingSnapshot;

    /** 调用时的单价快照 */
    private BigDecimal unitPrice;

    /** 提示token数 */
    private Integer promptTokens;

    /** 缓存提示token数 */
    private Integer cachedPromptTokens;

    /** 补全token数 */
    private Integer completionTokens;

    /** 总token数 */
    private Integer totalTokens;

    /** 1=成功 0=失败 */
    private String success;

    /** 错误信息 */
    private String errorMsg;

    /** 扣减的钱包ID(可空) */
    private Long walletId;

    /** 扣减的钱包流水ID(可空) */
    private Long walletLedgerId;
}
