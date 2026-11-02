package com.smartdock.analytics.config;

import com.smartdock.analytics.engine.AnalyticsService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 小修包件9（2026-11-02）：凌晨兜底任务每晚回填 D-1..D-3，单日失败不拖垮后续天。
 * 行为锁：D-1 抛错时 D-2/D-3 照常执行（共 3 次调用），异常被逐日 try/catch 吞掉。
 */
@Tag("local")
class AnalyticsDailyBackfillJobTest {

    @Test
    void oneDayFailureDoesNotStopFollowingDays() {
        AnalyticsService analyticsService = mock(AnalyticsService.class);
        // 第一天失败, 后两天成功
        when(analyticsService.backfillDaily(anyString()))
            .thenThrow(new IllegalStateException("D-1 宕机"))
            .thenReturn(Map.of("sessions", 1))
            .thenReturn(Map.of("sessions", 1));

        AnalyticsDailyBackfillJob job = new AnalyticsDailyBackfillJob(analyticsService);
        setEnabled(job, true);

        job.backfillRecentDays();

        // 三天都尝试过: 第一天的异常没有中断循环
        verify(analyticsService, times(3)).backfillDaily(anyString());
    }

    @Test
    void disabledSwitchSkipsWork() {
        AnalyticsService analyticsService = mock(AnalyticsService.class);
        AnalyticsDailyBackfillJob job = new AnalyticsDailyBackfillJob(analyticsService);
        setEnabled(job, false);

        job.backfillRecentDays();

        verify(analyticsService, times(0)).backfillDaily(anyString());
    }

    /** 纯单测没有 spring-test, 反射直接改 @Value 注入的开关 */
    private static void setEnabled(AnalyticsDailyBackfillJob job, boolean value) {
        try {
            java.lang.reflect.Field field = AnalyticsDailyBackfillJob.class.getDeclaredField("enabled");
            field.setAccessible(true);
            field.setBoolean(job, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot set enabled", e);
        }
    }
}
