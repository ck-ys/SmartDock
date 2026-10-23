package com.smartdock.domain.vo;

import io.github.linpeilie.annotations.AutoMapper;
import lombok.Data;
import com.smartdock.domain.DcsAiConfig;

import java.io.Serial;
import java.io.Serializable;
import java.util.Date;

/**
 * DCS AI 模型配置视图对象。
 *
 * @author smartdock
 */
@Data
@AutoMapper(target = DcsAiConfig.class)
public class DcsAiConfigVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 配置ID */
    private Long configId;

    private String tenantId;

    /** 模型厂商(见 DcsAiProvider 枚举: OPENAI/DEEPSEEK/QWEN/ZHIPU/KIMI 等) */
    private String provider;

    /** Q7: provider 显示名(由 service 用 DcsAiProvider.getLabel() 填充, 前端免字典映射) */
    private String providerName;

    /** 配置用途: CHAT=AI 调用, EMBEDDING=向量化; 前端按用途分组展示/筛选 */
    private String useType;

    /** OpenAI 兼容 API 的 base URL */
    private String baseUrl;

    /** Q5-A：接口格式（三选一，空=Chat Completions） */
    private String apiFormat;

    /** 永远返回掩码, 不返回明文 API Key */
    private String apiKey;

    /** 模型名称(如 gpt-4.1-mini) */
    private String modelName;

    /** 模型上下文窗口大小(token, 仅作管理元数据) */
    private Integer contextWindow;

    /** 单次回答最大输出 tokens（批C C1 透出，空=模型/框架默认） */
    private Integer maxTokens;

    /** 采样温度(0~2, 越高越发散) */
    private Double temperature;

    /** 调用超时秒数 */
    private Integer timeoutSeconds;

    /** 可用性("1"=可用, "0"=停用)——批3 起为纯状态位, 与默认解耦 */
    private String enabled;

    /** 默认星标(批3):1=该用途默认★;NULL=普通可用行(每租户每用途至多一星) */
    private String isDefault;

    /** 每次 AI 调用单价(元) */
    private java.math.BigDecimal unitPricePerCall;

    /** 输入单价(元/百万 tokens, 价格目录透出; 批4 Q5/Q6 选择器与模型卡展示用) */
    private java.math.BigDecimal inputPricePerMillion;

    /** 输出单价(元/百万 tokens, 价格目录透出) */
    private java.math.BigDecimal outputPricePerMillion;

    /** 价格币种(价格目录透出, CNY/USD) */
    private String currency;

    /** API Key 掩码串(供前端展示已配置状态) */
    private String apiKeyMask;

    /** 批B: 该厂商接入(钥匙)是否已配置——列表组头「接入已配置✓」与表单「首次配置」形态切换依据 */
    private Boolean accessConfigured;

    /** 是否启用余额查询("1"=启用, "0"=停用) */
    private String balanceQueryEnabled;

    /** 计费模式: OFFICIAL_TOKEN=按官方 token 计费, FIXED_PER_CALL=按次固定计费 */
    private String billingMode;

    /** 服务技术费率百分比(如 10.00 表示加收 10%) */
    private java.math.BigDecimal serviceFeeRate;

    /**
     * 业务反馈批1015b Q13：积分消耗倍率（1 积分=1 分钱基准 × 此倍率；NULL/0 视同 1）。
     * 种子口径：免费×1 / flash 档×2 / 中档×2~3 / 旗舰×4~6——此前前后端零编辑入口只能改库，
     * 本批补模型表单字段。
     */
    private java.math.BigDecimal creditMultiplier;

    /** 思考模式强度(空=关闭/low/medium/high/max) */
    private String reasoningEffort;

    private String remark;

    private Date createTime;

    private Date updateTime;
}
