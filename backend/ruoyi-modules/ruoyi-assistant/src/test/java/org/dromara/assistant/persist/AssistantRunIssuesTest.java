package org.dromara.assistant.persist;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「这一轮为什么没回答」判因/脱敏纯函数单测（2026-11-11 拍板 Q2-2/Q4-2）。
 *
 * <p>钉两件事：①六类判据不串门——429 带 "quota exceeded" 字样必须落 rate（是限流
 * 不是余额尽），鉴权词优先于超时词；②脱敏四连——URL/密钥/Bearer/长串一个不漏，
 * 200 字截断，null 安全。判错会让用户去充钱或换钥匙，比不说更糟。</p>
 */
@Tag("dev")
@Tag("local")
class AssistantRunIssuesTest {

    // ---- classify：七条输入 → 原因码 ----

    @Test
    void classifyEmptyInputsFallsToOther() {
        assertThat(AssistantRunIssues.classify(null, null)).isEqualTo("other");
        assertThat(AssistantRunIssues.classify("", "")).isEqualTo("other");
    }

    @Test
    void classifyRateBeatsQuotaWords() {
        // 429 响应体常带 "quota exceeded"——语义是限流窗口，不是余额尽
        assertThat(AssistantRunIssues.classify("429", "quota exceeded, retry later"))
            .isEqualTo("rate");
        assertThat(AssistantRunIssues.classify(null, "Rate Limit reached")).isEqualTo("rate");
        assertThat(AssistantRunIssues.classify(null, "当前使用人数较多，请稍后再试")).isEqualTo("rate");
    }

    @Test
    void classifyQuotaFromBillingGate() {
        // BillingModelDecorator 前置闸话术（Flux.error → RUN_ERROR 帧）
        assertThat(AssistantRunIssues.classify(null, "AI 助手额度不足（当前可用 0.0 元）…"))
            .isEqualTo("quota");
        assertThat(AssistantRunIssues.classify(null, "insufficient balance, HTTP 402"))
            .isEqualTo("quota");
    }

    @Test
    void classifyAuthBeforeTimeout() {
        assertThat(AssistantRunIssues.classify("401", "unauthorized: invalid api key (read timeout)"))
            .isEqualTo("auth");
        assertThat(AssistantRunIssues.classify(null, "Incorrect API key provided: sk-***"))
            .isEqualTo("auth");
    }

    @Test
    void classifyTimeout() {
        assertThat(AssistantRunIssues.classify(null, "io.netty.handler.timeout.ReadTimeoutException"))
            .isEqualTo("timeout");
        assertThat(AssistantRunIssues.classify(null, "connect timed out")).isEqualTo("timeout");
    }

    @Test
    void classifyUnknownStaysOther() {
        assertThat(AssistantRunIssues.classify("E500", "boom")).isEqualTo("other");
        // 网络故障不含特征词——不许被误判成额度（同 BillingModelDecorator 单测口径）
        assertThat(AssistantRunIssues.classify(null, "Connection reset by peer")).isEqualTo("other");
    }

    // ---- sanitize：原文脱敏 ----

    @Test
    void sanitizeNullAndBlank() {
        assertThat(AssistantRunIssues.sanitizeDetail(null)).isNull();
        assertThat(AssistantRunIssues.sanitizeDetail("   ")).isNull();
    }

    @Test
    void sanitizeStripsUrlKeyBearerAndLongRun() {
        String raw = "GET https://api.vendor.com/v1/chat failed, key=sk-AbCdEf12345678, "
            + "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig, req=abcdef0123456789abcdef0123456789";
        String out = AssistantRunIssues.sanitizeDetail(raw);
        assertThat(out).contains("[链接]").contains("[密钥]").contains("[令牌]").contains("[长串]");
        assertThat(out).doesNotContain("api.vendor.com").doesNotContain("sk-AbCdEf")
            .doesNotContain("eyJhbGci").doesNotContain("abcdef0123456789");
    }

    @Test
    void sanitizeCollapsesWhitespaceAndTruncates() {
        assertThat(AssistantRunIssues.sanitizeDetail("a\r\n b\t  c")).isEqualTo("a b c");
        // 截断用中文填充——纯拉丁长串会先被 [长串] 规则吃掉，测不到截断分支
        String out = AssistantRunIssues.sanitizeDetail("字".repeat(300));
        assertThat(out).hasSize(201).endsWith("…");
        assertThat(AssistantRunIssues.sanitizeDetail("字".repeat(200))).hasSize(200);
    }
}
