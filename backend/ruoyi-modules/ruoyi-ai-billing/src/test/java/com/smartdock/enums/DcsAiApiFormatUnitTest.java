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
class DcsAiApiFormatUnitTest {

    @Test
    void blankOrUnknownFallsBackToChatCompletions() {
        assertEquals(DcsAiApiFormat.CHAT_COMPLETIONS, DcsAiApiFormat.of(null));
        assertEquals(DcsAiApiFormat.CHAT_COMPLETIONS, DcsAiApiFormat.of(" "));
        assertEquals(DcsAiApiFormat.CHAT_COMPLETIONS, DcsAiApiFormat.of("no-such-format"));
    }

    @Test
    void supportedOnlyAcceptsThreeKnownValues() {
        assertTrue(DcsAiApiFormat.supported("chat_completions"));
        assertTrue(DcsAiApiFormat.supported("ANTHROPIC_MESSAGES"));
        assertTrue(DcsAiApiFormat.supported("Responses"));
        assertFalse(DcsAiApiFormat.supported(""));
        assertFalse(DcsAiApiFormat.supported("GEMINI"));
    }

    @Test
    void requestUrlAppendsOfficialPath() {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
            DcsAiApiFormat.CHAT_COMPLETIONS.requestUrl("https://api.deepseek.com/v1/"));
        assertEquals("https://api.openai.com/v1/responses",
            DcsAiApiFormat.RESPONSES.requestUrl("https://api.openai.com/v1"));
    }

    @Test
    void anthropicDoesNotDoubleSuffixV1() {
        assertEquals("https://api.anthropic.com/v1/messages",
            DcsAiApiFormat.ANTHROPIC_MESSAGES.requestUrl("https://api.anthropic.com/v1"));
        assertEquals("https://proxy.example.com/v1/messages",
            DcsAiApiFormat.ANTHROPIC_MESSAGES.requestUrl("https://proxy.example.com/v1/"));
        assertEquals("https://proxy.example.com/v1/messages",
            DcsAiApiFormat.ANTHROPIC_MESSAGES.requestUrl("https://proxy.example.com"));
    }
}
