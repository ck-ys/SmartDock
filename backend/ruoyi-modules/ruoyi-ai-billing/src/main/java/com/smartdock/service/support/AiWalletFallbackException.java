package com.smartdock.service.support;

/**
 * AI 钱包余额不足, 上层应回退算法路径。
 *
 * <p>与 {@code ServiceException} 区分: 上层(AiScoreEnhancer/ScoreEngine)catch 此异常后
 * 不向用户报错, 而是静默回退到纯算法评分。</p>
 *
 * @author smartdock
 */
public class AiWalletFallbackException extends RuntimeException {

    /**
     * 构造异常并携带余额不足提示。
     */
    public AiWalletFallbackException(String message) {
        super(message);
    }
}
