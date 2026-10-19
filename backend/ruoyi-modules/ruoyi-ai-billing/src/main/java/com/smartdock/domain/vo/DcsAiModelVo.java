package com.smartdock.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * AI provider 可用模型。
 *
 * @author smartdock
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DcsAiModelVo implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String modelId;

    private String ownedBy;

    /**
     * 是否免费模型（业务批4 Q16-A，2026-10-13 拍板）：厂商 /models 响应自带定价时解析——
     * OpenRouter 的 pricing.prompt 为 "0" 判免费（:free 系官方口径）；响应无定价字段时为 null
     * （如实不标不猜，前端不排序不显示徽章）。
     */
    private Boolean free;
}
