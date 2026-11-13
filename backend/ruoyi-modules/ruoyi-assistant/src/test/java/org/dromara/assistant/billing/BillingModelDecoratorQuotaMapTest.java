package org.dromara.assistant.billing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「额度/限流」判定纯函数单测（2026-11-11 STEP03）。
 *
 * <p>为什么值得单独钉：429 会被框架重试到耗尽再上抛，原始状态码常常埋在 cause 或
 * suppressed 里。这里钉死「链上任意一层带特征就认」，同时钉住「网络故障不许被误判成额度问题」
 * ——误判会让用户去充钱，比不说更糟。</p>
 */
@Tag("dev")
@Tag("local")
class BillingModelDecoratorQuotaMapTest {

    @Test
    void detectsBare429() {
        assertThat(BillingModelDecorator.isQuotaLike(new RuntimeException("status 429 Too Many Requests"))).isTrue();
    }

    @Test
    void detects429BuriedInCause() {
        RuntimeException root = new RuntimeException("429 {\"error\":\"quota exceeded\"}");
        assertThat(BillingModelDecorator.isQuotaLike(
            new IllegalStateException("Retries exhausted: 2/2", root))).isTrue();
    }

    @Test
    void detects429InSuppressed() {
        RuntimeException outer = new IllegalStateException("Retries exhausted: 2/2");
        outer.addSuppressed(new RuntimeException("429 Token Plan 用量上限"));
        assertThat(BillingModelDecorator.isQuotaLike(outer)).isTrue();
    }

    @Test
    void detectsRateLimitAndQuotaWords() {
        assertThat(BillingModelDecorator.isQuotaLike(new RuntimeException("Rate Limit reached"))).isTrue();
        assertThat(BillingModelDecorator.isQuotaLike(new RuntimeException("insufficient quota"))).isTrue();
        assertThat(BillingModelDecorator.isQuotaLike(new RuntimeException("已达到 Token Plan 用量上限"))).isTrue();
    }

    @Test
    void doesNotMisreportNetworkFailures() {
        assertThat(BillingModelDecorator.isQuotaLike(new RuntimeException("connect timed out"))).isFalse();
        assertThat(BillingModelDecorator.isQuotaLike(new java.io.IOException("Connection reset"))).isFalse();
        assertThat(BillingModelDecorator.isQuotaLike(new RuntimeException("500 Internal Server Error"))).isFalse();
    }

    @Test
    void survivesNullAndCyclicCauses() {
        assertThat(BillingModelDecorator.isQuotaLike(null)).isFalse();
        // 自引用 cause 不得死循环（真实栈里包装链偶有环）
        RuntimeException a = new RuntimeException("boom");
        RuntimeException b = new RuntimeException("outer", a);
        a.initCause(b);
        assertThat(BillingModelDecorator.isQuotaLike(b)).isFalse();
    }
}
