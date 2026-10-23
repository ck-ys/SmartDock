package com.smartdock.domain;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.dromara.common.encrypt.annotation.EncryptField;
import org.dromara.common.encrypt.enumd.AlgorithmType;
import org.dromara.common.mybatis.core.domain.BaseEntity;

import java.io.Serial;
import java.math.BigDecimal;

/**
 * DCS AI 模型配置。
 *
 * <p>该表由超管跨租户维护, 因此显式保存 tenantId 但不继承 TenantEntity。</p>
 *
 * @author smartdock
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dcs_ai_config")
public class DcsAiConfig extends BaseEntity {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 配置ID */
    @TableId(value = "config_id")
    private Long configId;

    /** 开通 AI 的租户编号 */
    private String tenantId;

    /** OPENAI/DEEPSEEK/QWEN/ZHIPU/KIMI */
    private String provider;

    /** 配置用途: CHAT=AI 调用(诊断/字段/评分/规则), EMBEDDING=向量化(Phase 4 相似检索) */
    private String useType;

    /** OpenAI-compatible API base URL */
    private String baseUrl;

    /** Q5-A：接口格式（CHAT_COMPLETIONS/ANTHROPIC_MESSAGES/RESPONSES，空=兼容存量按 Chat Completions） */
    private String apiFormat;

    /** API Key, 由 MyBatis 加密插件落库加密(显式 AES + 专用密钥, 不依赖全局默认算法) */
    @EncryptField(algorithm = AlgorithmType.AES, password = "DcsAiCfgEncKey16")
    private String apiKey;

    /** 模型名称 */
    private String modelName;

    /** 模型上下文窗口(token), 仅作为管理配置元数据 */
    private Integer contextWindow;

    /** 采样温度 */
    private Double temperature;

    /** 调用超时秒数 */
    private Integer timeoutSeconds;

    /** 最大输出 token(AI 调用上限) */
    private Integer maxTokens;

    /** 思考模式强度(空=关闭/low/medium/high/max, DeepSeek/OpenAI reasoner 适用) */
    private String reasoningEffort;

    /** 默认星标(批3,2026-10-09):1=该用途默认(每租户每use_type唯一,索引uk_dcs_ai_config_tenant_default);NULL=普通可用行;enabled 语义=可用性(1=可用/0=停用) */
    private String isDefault;

    /** 0=停用, 1=启用 */
    private String enabled;

    /** 每次 AI 调用单价(元), 默认 0.01; 租户可自定义 */
    private BigDecimal unitPricePerCall;

    /** 是否启用余额查询 */
    private String balanceQueryEnabled;

    /** 计费模式: OFFICIAL_TOKEN / FIXED_PER_CALL */
    private String billingMode;

    /**
     * 业务批8 Q2：积分消耗倍率（1 积分=1 分钱基准 × 此倍率；NULL/0 视同 1）。
     * 种子口径：免费×1 / flash 档×2 / 中档×2~3 / 旗舰×4~6——订阅租户对话扣积分时乘算。
     */
    private BigDecimal creditMultiplier;

    /** 服务技术费率百分比, 如 10.00 表示加收 10% */
    private BigDecimal serviceFeeRate;

    /** 备注 */
    private String remark;

    /** 删除标志 */
    @TableLogic
    private String delFlag;
}
