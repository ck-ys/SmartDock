package com.smartdock.analytics.engine;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;

import java.util.Set;

/**
 * R2-4 自定义分析视图入参校验(纯函数): 视图名/维度/指标白名单与天数/条数收敛。
 *
 * @author smartdock
 */
public final class AnalyticsReportValidator {

    private AnalyticsReportValidator() {
    }

    /** 指标白名单与 custom() 运行口径一致 */
    public static final Set<String> METRICS = Set.of("pv", "uv", "visits", "events");

    public static String normalizeName(String name) {
        if (StringUtils.isBlank(name)) {
            throw new ServiceException("视图名不能为空");
        }
        String trimmed = name.trim();
        if (trimmed.length() > 100) {
            throw new ServiceException("视图名最长 100 字");
        }
        return trimmed;
    }

    public static String normalizeDimension(String dimension) {
        if (StringUtils.isBlank(dimension)) {
            return "day";
        }
        String trimmed = dimension.trim().toLowerCase();
        if (trimmed.length() > 50) {
            throw new ServiceException("维度名非法");
        }
        return trimmed;
    }

    public static String normalizeMetric(String metric) {
        String trimmed = StringUtils.isBlank(metric) ? "pv" : metric.trim().toLowerCase();
        if (!METRICS.contains(trimmed)) {
            throw new ServiceException("指标只支持 pv / uv / visits / events");
        }
        return trimmed;
    }

    public static int clampDays(Integer days) {
        return days == null ? 30 : Math.min(Math.max(days, 1), 365);
    }

    public static int clampLimit(Integer limit) {
        return limit == null ? 20 : Math.min(Math.max(limit, 1), 100);
    }
}
