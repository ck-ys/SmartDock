package org.dromara.assistant.core;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Q9 会话级缓存命中聚合器单测（三席审查补课：此前无单测）。
 * 纯静态内存态——各用例用独立 threadId 自包含，CAP 用例清空不影响后续（各自先记后读）。
 */
@Tag("dev")
class AssistantCacheHitHolderUnitTest {

    @Test
    void recordRejectsInvalidInput() {
        AssistantCacheHitHolder.record(null, 1000, 500);
        AssistantCacheHitHolder.record("  ", 1000, 500);
        AssistantCacheHitHolder.record("q9-invalid-zero", 0, 0);
        assertThat(AssistantCacheHitHolder.aggregate("q9-invalid-zero").requests()).isZero();
        assertThat(AssistantCacheHitHolder.aggregate(null).ratePct()).isNull();
    }

    @Test
    void accumulatesAndComputesRate() {
        String th = "q9-acc-" + System.nanoTime();
        // 2 回合：输入 1000/3000，缓存读 800/1200 → 2000/4000 = 50%
        AssistantCacheHitHolder.record(th, 1000, 800);
        AssistantCacheHitHolder.record(th, 3000, 1200);
        AssistantCacheHitHolder.Aggregate agg = AssistantCacheHitHolder.aggregate(th);
        assertThat(agg.requests()).isEqualTo(2);
        assertThat(agg.promptTokens()).isEqualTo(4000);
        assertThat(agg.cachedTokens()).isEqualTo(2000);
        assertThat(agg.ratePct()).isEqualTo(50);
    }

    @Test
    void rateClampedAt100AndNegativeCachedIgnored() {
        String th = "q9-clamp-" + System.nanoTime();
        // 异常回传 cached>prompt（三席审查 P2：钳制对齐 AiBillingCalculator，不得显示 >100%）
        AssistantCacheHitHolder.record(th, 1000, 5000);
        assertThat(AssistantCacheHitHolder.aggregate(th).ratePct()).isEqualTo(100);
        String th2 = "q9-neg-" + System.nanoTime();
        AssistantCacheHitHolder.record(th2, 1000, -50);
        assertThat(AssistantCacheHitHolder.aggregate(th2).cachedTokens()).isZero();
    }

    @Test
    void capClearsWhenExceeded() {
        for (int i = 0; i <= 50_001; i++) {
            AssistantCacheHitHolder.record("q9-cap-" + i, 10, 5);
        }
        // 超限整体清空：早先条目归零，新条目从当前会话重新累计（拍板：丢的只是展示数字）
        assertThat(AssistantCacheHitHolder.aggregate("q9-cap-0").requests()).isZero();
        AssistantCacheHitHolder.record("q9-cap-after", 100, 100);
        assertThat(AssistantCacheHitHolder.aggregate("q9-cap-after").ratePct()).isEqualTo(100);
    }
}
