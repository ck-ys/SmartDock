package com.smartdock.service.support;

/**
 * Spring AI 返回的 token 用量快照。
 *
 * @author smartdock
 */
public record AiTokenUsageSnapshot(Integer promptTokens, Integer cachedPromptTokens, Integer completionTokens, Integer totalTokens) {

    /**
     * 是否存在有效的 token 用量(prompt/completion/total 任一为正)。
     */
    public boolean hasTokenUsage() {
        return positive(promptTokens) || positive(completionTokens) || positive(totalTokens);
    }

    /**
     * 判断整数是否为正值。
     */
    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }
}
