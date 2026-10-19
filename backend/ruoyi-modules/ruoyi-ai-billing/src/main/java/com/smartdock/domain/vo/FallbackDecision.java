package com.smartdock.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;

/**
 * 钱包余额检查后的兜底决策。
 *
 * @author smartdock
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FallbackDecision implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** PASS=余额够, 正常调; ALGORITHM_FALLBACK=走算法; BLOCK=阻塞; ALLOW_FREE=免费放行 */
    private String decision;

    /** 决策依据的策略名 */
    private String policy;

    /** 给用户看的提示 */
    private String message;

    public boolean isPass() {
        return "PASS".equals(decision);
    }

    public boolean isAlgorithmFallback() {
        return "ALGORITHM_FALLBACK".equals(decision);
    }

    public boolean isBlock() {
        return "BLOCK".equals(decision);
    }

    public static FallbackDecision pass() {
        return new FallbackDecision("PASS", null, null);
    }

    public static FallbackDecision algorithmFallback(String message) {
        return new FallbackDecision("ALGORITHM_FALLBACK", "ALGORITHM_FALLBACK", message);
    }

    public static FallbackDecision block(String message) {
        return new FallbackDecision("BLOCK", "BLOCK", message);
    }

    public static FallbackDecision allowFree() {
        return new FallbackDecision("ALLOW_FREE", "ALLOW_FREE", null);
    }
}
