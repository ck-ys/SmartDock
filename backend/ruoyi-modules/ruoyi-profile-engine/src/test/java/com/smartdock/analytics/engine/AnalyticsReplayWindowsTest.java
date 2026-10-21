package com.smartdock.analytics.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回放窗口规则(批10 Q3): 纯函数直测。
 */
@Tag("local")
class AnalyticsReplayWindowsTest {

    private final LocalDate today = LocalDate.of(2026, 10, 20);

    @Test
    void 合法窗口通过() {
        assertThat(AnalyticsReplayWindows.validate(
            LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 17), today)).isEmpty();
    }

    @Test
    void 单日窗口通过_同一天() {
        assertThat(AnalyticsReplayWindows.validate(today, today, today)).isEmpty();
    }

    @Test
    void 两端缺失只报一条() {
        assertThat(AnalyticsReplayWindows.validate(null, today, today))
            .hasSize(1)
            .allMatch(msg -> msg.contains("yyyy-MM-dd"));
    }

    @Test
    void 反序窗口被拒() {
        assertThat(AnalyticsReplayWindows.validate(
            LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 15), today))
            .anyMatch(msg -> msg.contains("不能早于"));
    }

    @Test
    void 未来窗口被拒() {
        assertThat(AnalyticsReplayWindows.validate(
            LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 22), today))
            .anyMatch(msg -> msg.contains("不能晚于今天"));
    }

    @Test
    void 跨度超366天被拒_366天整通过() {
        LocalDate from = LocalDate.of(2025, 10, 20);
        LocalDate toExactly366 = LocalDate.of(2026, 10, 20);
        assertThat(AnalyticsReplayWindows.validate(from, toExactly366, today)).isEmpty();

        LocalDate to367 = LocalDate.of(2026, 10, 21);
        // to367 同时越界(未来)与超跨度, 两条都要报——防"只报第一条"的短路
        assertThat(AnalyticsReplayWindows.validate(from, to367, today)).hasSize(2);
    }
}
