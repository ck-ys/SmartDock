package com.smartdock.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * DCS AI provider 探测请求。
 *
 * @author smartdock
 */
@Data
public class DcsAiProbeBo {

    /** 模型厂商(探测目标,见 DcsAiProvider 枚举) */
    @NotBlank(message = "模型厂商不能为空")
    private String provider;

    /** OpenAI 兼容 API 的 base URL(探测目标) */
    @NotBlank(message = "接口地址不能为空")
    private String baseUrl;

    /**
     * API Key(探测目标,不会落库)。批B 起:留空+tenantId 提供时由服务端从厂商接入行补齐
     * （同厂加模型的「获取模型」探测不再要求重填钥匙, ZCode 同款体验）
     */
    private String apiKey;

    /** 批B: 钥匙留空时按 (tenantId,provider) 查接入行补钥(可选, 跨租户探测须显式带 key) */
    private String tenantId;

    /** 探测用模型名(为空时用厂商默认) */
    private String modelName;

    /** 探测用采样温度 */
    private Double temperature;

    /** 探测调用超时秒数 */
    private Integer timeoutSeconds;
}
