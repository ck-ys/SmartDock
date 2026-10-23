package com.smartdock.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI provider 预置项。
 *
 * @author smartdock
 */
@Data
@AllArgsConstructor
public class DcsAiProviderPresetVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String provider;

    private String providerName;

    private String baseUrl;

    private String modelName;

    /** Q5-A：默认接口格式（存量厂商均为 CHAT_COMPLETIONS） */
    private String apiFormat;

    /** 控制台/申请 Key 地址（模板库用；预置枚举可为空） */
    private String consoleUrl;
}
