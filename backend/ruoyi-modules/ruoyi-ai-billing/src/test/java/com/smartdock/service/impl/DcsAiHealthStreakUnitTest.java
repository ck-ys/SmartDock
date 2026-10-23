package com.smartdock.service.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Q2-A（2026-10-23）：手动体检落巡检表的连败计数纯函数锁。
 */
@Tag("local")
class DcsAiHealthStreakUnitTest {

    @Test
    void successResetsStreak() {
        assertEquals(0, DcsAiConfigServiceImpl.healthStreak(true, 5));
        assertEquals(0, DcsAiConfigServiceImpl.healthStreak(true, null));
    }

    @Test
    void firstFailureStartsAtOne() {
        assertEquals(1, DcsAiConfigServiceImpl.healthStreak(false, null));
    }

    @Test
    void consecutiveFailuresAccumulate() {
        assertEquals(2, DcsAiConfigServiceImpl.healthStreak(false, 1));
        assertEquals(4, DcsAiConfigServiceImpl.healthStreak(false, 3));
    }
}
