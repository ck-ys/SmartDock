package com.smartdock.enums;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q5（2026-10-23）：接口格式路径拼接与存量兼容口径。
 *
 * @author smartdock
 */
@Tag("local")
class AiApiFormatUnitTest {

    @Test
    void blankOrUnknownFallsBackToChatCompletions() {
        assertEquals(AiApiFormat.CHAT_COMPLETIONS, AiApiFormat.of(null));
        assertEquals(AiApiFormat.CHAT_COMPLETIONS, AiApiFormat.of(" "));
        assertEquals(AiApiFormat.CHAT_COMPLETIONS, AiApiFormat.of("no-such-format"));
    }

    @Test
    void supportedOnlyAcceptsThreeKnownValues() {
        assertTrue(AiApiFormat.supported("chat_completions"));
        assertTrue(AiApiFormat.supported("ANTHROPIC_MESSAGES"));
        assertTrue(AiApiFormat.supported("Responses"));
        assertFalse(AiApiFormat.supported(""));
        assertFalse(AiApiFormat.supported("GEMINI"));
    }

    @Test
    void requestUrlAppendsOfficialPath() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
            AiApiFormat.CHAT_COMPLETIONS.requestUrl("https://api.deepseek.com/v1/"));
        assertEquals("https://api.openai.com/v1/responses",
            AiApiFormat.RESPONSES.requestUrl("https://api.openai.com/v1"));
    }

    @Test
    void anthropicDoesNotDoubleSuffixV1() {
        assertEquals("https://api.anthropic.com/v1/messages",
            AiApiFormat.ANTHROPIC_MESSAGES.requestUrl("https://api.anthropic.com/v1"));
        assertEquals("https://proxy.example.com/v1/messages",
            AiApiFormat.ANTHROPIC_MESSAGES.requestUrl("https://proxy.example.com/v1/"));
        assertEquals("https://proxy.example.com/v1/messages",
            AiApiFormat.ANTHROPIC_MESSAGES.requestUrl("https://proxy.example.com"));
    }
}
