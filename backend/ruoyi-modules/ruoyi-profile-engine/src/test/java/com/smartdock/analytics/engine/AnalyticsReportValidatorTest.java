package com.smartdock.analytics.engine;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * R2-4 视图入参校验: 视图名收敛/指标白名单/天数条数夹取。
 *
 * @author smartdock
 */
@Tag("local")
class AnalyticsReportValidatorTest {

    @Test
    void nameTrimmedAndLengthGuarded() {
        assertEquals("例会视图", AnalyticsReportValidator.normalizeName("  例会视图 "));
        assertThrows(ServiceException.class, () -> AnalyticsReportValidator.normalizeName(" "));
        assertThrows(ServiceException.class, () -> AnalyticsReportValidator.normalizeName("长".repeat(101)));
    }

    @Test
    void dimensionDefaultsToDayAndWhitelisted() {
        assertEquals("day", AnalyticsReportValidator.normalizeDimension(null));
        assertEquals("channel", AnalyticsReportValidator.normalizeDimension(" Channel "));
        // 三审 P2: 白名单口径与 custom() 一致——12 个合法维度全过, 其余一律拒
        for (String dim : AnalyticsReportValidator.DIMENSIONS) {
            assertEquals(dim, AnalyticsReportValidator.normalizeDimension(dim));
        }
        assertThrows(ServiceException.class, () -> AnalyticsReportValidator.normalizeDimension("d".repeat(51)));
        assertThrows(ServiceException.class, () -> AnalyticsReportValidator.normalizeDimension("evil_injection"));
        assertThrows(ServiceException.class, () -> AnalyticsReportValidator.normalizeDimension("DAY;drop"));
    }

    @Test
    void metricWhitelisted() {
        assertEquals("pv", AnalyticsReportValidator.normalizeMetric(null));
        assertEquals("uv", AnalyticsReportValidator.normalizeMetric("UV"));
        assertThrows(ServiceException.class, () -> AnalyticsReportValidator.normalizeMetric(" revenue "));
    }

    @Test
    void daysAndLimitClamped() {
        assertEquals(30, AnalyticsReportValidator.clampDays(null));
        assertEquals(1, AnalyticsReportValidator.clampDays(0));
        assertEquals(365, AnalyticsReportValidator.clampDays(999));
        assertEquals(20, AnalyticsReportValidator.clampLimit(null));
        assertEquals(50, AnalyticsReportValidator.clampLimit(50));
        assertEquals(100, AnalyticsReportValidator.clampLimit(500));
    }
}
