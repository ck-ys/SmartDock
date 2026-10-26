package com.smartdock.analytics.engine;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 批5B 兜底目标日解析口径: 空=昨日 / 合法串原样 / 非法格式与未来日拒绝。
 *
 * @author smartdock
 */
@Tag("local")
class AnalyticsBackfillDayTest {

    @Test
    void blankFallsBackToYesterday() {
        LocalDate expect = LocalDate.now().minusDays(1);
        assertEquals(expect, AnalyticsService.parseBackfillDay(null));
        assertEquals(expect, AnalyticsService.parseBackfillDay("  "));
    }

    @Test
    void validDayParsedAndTrimmed() {
        assertEquals(LocalDate.of(2026, 10, 25), AnalyticsService.parseBackfillDay("2026-10-25"));
        assertEquals(LocalDate.of(2026, 10, 25), AnalyticsService.parseBackfillDay(" 2026-10-25 "));
    }

    @Test
    void garbageRejected() {
        assertThrows(ServiceException.class, () -> AnalyticsService.parseBackfillDay("yesterday"));
        assertThrows(ServiceException.class, () -> AnalyticsService.parseBackfillDay("2026/10/25"));
    }

    @Test
    void futureDayRejected() {
        assertThrows(ServiceException.class, () -> AnalyticsService.parseBackfillDay("2999-01-01"));
    }

    @Test
    void todayAllowedForManualRerun() {
        assertEquals(LocalDate.now(), AnalyticsService.parseBackfillDay(LocalDate.now().toString()));
    }
}
