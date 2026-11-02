package com.smartdock.analytics.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 小修包件6（2026-11-02 拍板「全按推荐」）：首周激活率分母只含已定性者。
 * 观察期未满且未激活的人不进分母(单列 observing); 已激活的观察期未满者自然留在分子分母两侧。
 */
@Tag("local")
class AnalyticsMaturedActivationRateTest {

    @Test
    void noObservers_keepsClassicRate() {
        // 演示基线场景: 1 新同事已激活, 无观察中 → 100% 保持不变
        assertEquals(new BigDecimal("100.00"), AnalyticsService.maturedActivationRate(1, 1, 0));
        assertEquals(new BigDecimal("50.00"), AnalyticsService.maturedActivationRate(1, 2, 0));
    }

    @Test
    void pendingObserversExcludedFromDenominator() {
        // 2 新同事, 1 已激活, 1 还在观察且未激活 → 只判已定性的那 1 人 → 100% 而不是 50%
        assertEquals(new BigDecimal("100.00"), AnalyticsService.maturedActivationRate(1, 2, 1));
    }

    @Test
    void activatedObserverStillCountsInBothSides() {
        // 观察期未满但已激活的人不算 pending(后端口径), 纯函数只看入参: observing=0 → 原样 1/2
        assertEquals(new BigDecimal("50.00"), AnalyticsService.maturedActivationRate(1, 2, 0));
    }

    @Test
    void allObserving_yieldsNullNotZero() {
        // 全员仍在观察: 不造 0%, 前端显「—」
        assertNull(AnalyticsService.maturedActivationRate(0, 3, 3));
        assertNull(AnalyticsService.maturedActivationRate(0, 0, 0));
    }

    @Test
    void pathologicalEdgeNeverExceeds100() {
        // 病理边界: 激活数理论超分母(激活事件落在窗口右界), max 兜底防 >100%
        assertEquals(new BigDecimal("100.00"), AnalyticsService.maturedActivationRate(2, 2, 1));
    }
}
