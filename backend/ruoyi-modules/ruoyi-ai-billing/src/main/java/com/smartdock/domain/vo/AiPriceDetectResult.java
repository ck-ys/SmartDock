package com.smartdock.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 智能识别计费参数结果。
 *
 * @author smartdock
 */
@Data
public class AiPriceDetectResult implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 输入 token 单价(每 1M) */
    private BigDecimal inputPricePerMillion;

    /** 缓存输入 token 单价(每 1M) */
    private BigDecimal cachedInputPricePerMillion;

    /** 输出 token 单价(每 1M) */
    private BigDecimal outputPricePerMillion;

    /** 币种 */
    private String currency;

    /** 来源 URL */
    private String sourceUrl;

    /** 置信度: high/medium/low */
    private String confidence;

    /** 识别方法: REGEX/AI/NONE */
    private String method;

    /** 失败时的提示 */
    private String message;

    /** 是否成功 */
    private boolean success;

    public static AiPriceDetectResult success(BigDecimal input, BigDecimal cachedInput,
                                               BigDecimal output, String currency,
                                               String sourceUrl, String confidence) {
        AiPriceDetectResult r = new AiPriceDetectResult();
        r.setInputPricePerMillion(input);
        r.setCachedInputPricePerMillion(cachedInput);
        r.setOutputPricePerMillion(output);
        r.setCurrency(currency);
        r.setSourceUrl(sourceUrl);
        r.setConfidence(confidence);
        r.setSuccess(true);
        return r;
    }

    public static AiPriceDetectResult failure(String sourceUrl, String message, String method) {
        AiPriceDetectResult r = new AiPriceDetectResult();
        r.setSourceUrl(sourceUrl);
        r.setMessage(message);
        r.setMethod(method);
        r.setSuccess(false);
        return r;
    }
}
