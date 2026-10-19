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
}
