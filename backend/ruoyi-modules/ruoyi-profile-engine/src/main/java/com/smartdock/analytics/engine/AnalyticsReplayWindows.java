package com.smartdock.analytics.engine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 回放窗口规则(批10 Q3, 纯函数供单测)。
 *
 * @author smartdock
 */
public final class AnalyticsReplayWindows {

    public static final int MAX_WINDOW_DAYS = 366;

    private AnalyticsReplayWindows() {
    }

    /**
     * @return 违规信息; 空列表 = 合法。规则: 两端齐全、to >= from、to 不晚于今天、跨度 <= 366 天。
     */
    public static List<String> validate(LocalDate from, LocalDate to, LocalDate today) {
        List<String> errors = new ArrayList<>();
        if (from == null || to == null) {
            errors.add("fromDate/toDate 格式必须是 yyyy-MM-dd");
            return errors;
        }
        if (to.isBefore(from)) {
            errors.add("toDate 不能早于 fromDate");
        }
        if (to.isAfter(today)) {
            errors.add("toDate 不能晚于今天");
        }
        if (!to.isBefore(from) && from.datesUntil(to.plusDays(1)).count() > MAX_WINDOW_DAYS) {
            errors.add("回放窗口一次最多 " + MAX_WINDOW_DAYS + " 天, 请分批");
        }
        return errors;
    }
}
