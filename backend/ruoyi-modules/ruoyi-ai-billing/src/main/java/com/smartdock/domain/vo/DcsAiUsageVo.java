package com.smartdock.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.smartdock.domain.DcsAiUsage;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;

/**
 * AI 调用消耗 VO (前端展示用)。
 *
 * @author smartdock
 */
@Data
@AutoMapper(target = DcsAiUsage.class)
public class DcsAiUsageVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 消耗记录ID */
    private Long usageId;
    private String tenantId;
    /** 关联的 AI 配置ID */
    private Long configId;
    /** 模型厂商(见 DcsAiProvider) */
    private String provider;
    /** 模型名称 */
    private String modelName;
    /** 调用方: PROFILE_DIAGNOSE / FIELD_GENERATE / SCORE_ENHANCE / TEST_CONNECTION / SPEED_PROBE */
    private String caller;
    /** 业务场景: GENERAL / TEACHER_EVALUATION / ... */
    private String scene;
    /** 操作人ID */
    private Long operatorId;
    /** 本次调用耗时(毫秒) */
    private Integer latencyMs;
    /** 本次消耗总金额(元, 含服务费) */
    private BigDecimal costAmount;
    /** 官方 token 原始成本(不含服务费) */
    private BigDecimal baseCostAmount;
    /** 服务技术费率百分比(如 10.00 表示 10%) */
    private BigDecimal serviceFeeRate;
    /** 服务技术费金额(元) */
    private BigDecimal serviceFeeAmount;
    /** 币种 */
    private String currency;
    /** 计费模式: OFFICIAL_TOKEN / FIXED_PER_CALL */
    private String billingMode;
    /** 计费时的定价快照 JSON(用于追溯) */
    private String pricingSnapshot;
    /** 调用时的单价快照 */
    private BigDecimal unitPrice;
    /** 提示 token 数(输入) */
    private Integer promptTokens;
    /** 缓存命中的提示 token 数 */
    private Integer cachedPromptTokens;
    /** 补全 token 数(输出) */
    private Integer completionTokens;
    /** 总 token 数(提示 + 补全) */
    private Integer totalTokens;
    /** 是否成功("1"=成功, "0"=失败) */
    private String success;
    /** 失败时的错误信息 */
    private String errorMsg;
    /** 扣减的钱包ID(可空) */
    private Long walletId;
    /** 扣减的钱包流水ID(可空) */
    private Long walletLedgerId;
    private Date createTime;
}
