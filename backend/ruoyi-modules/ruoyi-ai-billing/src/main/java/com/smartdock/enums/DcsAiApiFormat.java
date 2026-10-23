package com.smartdock.enums;

import cn.hutool.core.util.StrUtil;

/**
 * Q5-A（2026-10-23 拍板）：接口格式三选一，与 ZCode 对齐。
 *
 * <p>老配置/老钥匙默认 {@link #CHAT_COMPLETIONS}，行为不变；格式只影响请求路径、
 * 连通性探测与请求体预览。Anthropic Messages 的 baseUrl 若已带 {@code /v1}，
 * 不再重复拼 {@code /v1}。</p>
 *
 * @author smartdock
 */
public enum DcsAiApiFormat {

    /** OpenAI 兼容（现有对话主链） */
    CHAT_COMPLETIONS("Chat Completions（OpenAI 兼容）", "/chat/completions"),
    /** Anthropic Messages 协议 */
    ANTHROPIC_MESSAGES("Anthropic Messages（Claude 格式）", "/v1/messages"),
    /** OpenAI Responses 协议 */
    RESPONSES("Responses（OpenAI 新版）", "/responses");

    private final String label;
    private final String path;

    DcsAiApiFormat(String label, String path) {
        this.label = label;
        this.path = path;
    }

    public String getLabel() {
        return label;
    }

    public String getPath() {
        return path;
    }

    /** 空/未知一律按 Chat Completions（兼容存量数据，不阻断老配置）。 */
    public static DcsAiApiFormat of(String value) {
        if (StrUtil.isBlank(value)) {
            return CHAT_COMPLETIONS;
        }
        try {
            return valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return CHAT_COMPLETIONS;
        }
    }

    public static boolean supported(String value) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        try {
            valueOf(value.trim().toUpperCase());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** 完整请求地址（baseUrl 去尾斜杠；Anthropic 已在 /v1 结尾时不重复拼）。 */
    public String requestUrl(String baseUrl) {
        String base = StrUtil.removeSuffix(StrUtil.trim(baseUrl), "/");
        if (this == ANTHROPIC_MESSAGES && base.endsWith("/v1")) {
            return base + "/messages";
        }
        return base + path;
    }
}
