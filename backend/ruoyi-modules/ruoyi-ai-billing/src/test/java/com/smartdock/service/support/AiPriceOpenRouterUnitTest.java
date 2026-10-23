package com.smartdock.service.support;

import com.smartdock.domain.vo.AiPriceDetectResult;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q4-A（2026-10-23）：OpenRouter 官方 models 接口价格解析纯函数锁。
 * 只锁解析规则，不发真实网络请求。
 */
@Tag("local")
class AiPriceOpenRouterUnitTest {

    private static Map<String, Object> root(Object... models) {
        return Map.of("data", List.of(models));
    }

    private static Map<String, Object> model(String id, String prompt, String completion) {
        return Map.of("id", id, "pricing", Map.of("prompt", prompt, "completion", completion));
    }

    @Test
    void exactIdConvertsPerTokenToPerMillion() {
        AiPriceDetectResult r = AiPriceAutoDetectService.extractOpenRouterPrice(
            root(model("deepseek/deepseek-chat", "0.0000015", "0.000002")), "deepseek/deepseek-chat");
        assertNotNull(r);
        assertTrue(r.isSuccess());
        assertEquals(0, new BigDecimal("1.5").compareTo(r.getInputPricePerMillion()));
        assertEquals(0, new BigDecimal("2").compareTo(r.getOutputPricePerMillion()));
        assertEquals("USD", r.getCurrency());
        assertEquals("OPENROUTER", r.getMethod());
    }

    @Test
    void freeModelNormalizesToZeroCny() {
        AiPriceDetectResult r = AiPriceAutoDetectService.extractOpenRouterPrice(
            root(model("minimax/minimax-m3.1:free", "0", "0")), "minimax/minimax-m3.1:free");
        assertNotNull(r);
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getInputPricePerMillion()));
        assertEquals(0, BigDecimal.ZERO.compareTo(r.getOutputPricePerMillion()));
        assertEquals("CNY", r.getCurrency());
        assertTrue(r.getMessage().contains("免费"));
    }

    @Test
    void suffixMatchWhenUserOnlyFillsModelName() {
        AiPriceDetectResult r = AiPriceAutoDetectService.extractOpenRouterPrice(
            root(model("deepseek/deepseek-chat", "0.000001", "0.000002")), "deepseek-chat");
        assertNotNull(r);
        assertEquals("OPENROUTER", r.getMethod());
    }

    @Test
    void unknownModelReturnsNull() {
        assertNull(AiPriceAutoDetectService.extractOpenRouterPrice(
            root(model("deepseek/deepseek-chat", "0.000001", "0.000002")), "not-exist"));
    }

    @Test
    void malformedPricingReturnsNull() {
        Map<String, Object> bad = Map.of("data", List.of(Map.of("id", "x", "pricing", Map.of("prompt", "abc"))));
        assertNull(AiPriceAutoDetectService.extractOpenRouterPrice(bad, "x"));
    }
}
