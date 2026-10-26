package com.smartdock.analytics.config;

import com.smartdock.analytics.engine.AnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 批5B 日汇总兜底: 每天凌晨补昨日会话+日汇总行。
 *
 * <p>事件落库触发的 rebuildRecent 是日汇总唯一写入链, 断档天无人补行
 * (板批5B根因实锤)。本任务与其共用 {@link AnalyticsService#backfillDaily}
 * 同一入口——幂等(deleteRange 后重算), 跑在没有缺口的日上数值不变;
 * 手工触发(分析中心补数接口)即等价验证通道。</p>
 *
 * <p>调度由模块内 {@code ProfileSchedulingConfig} 的 @EnableScheduling 统一开启;
 * cron 按容器时区(GMT+8 铁律), 取 00:20 避开零点整点扎堆。跨实例重复执行幂等无害
 * (rebuildRange 持分布式锁, 后到者等锁后重算同窗口)。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsDailyBackfillJob {

    private final AnalyticsService analyticsService;

    /** 兜底开关; false = 关闭凌晨自动补行(手工触发不受影响) */
    @Value("${analytics.daily-backfill-enabled:true}")
    private boolean enabled;

    private final java.util.concurrent.atomic.AtomicBoolean running =
        new java.util.concurrent.atomic.AtomicBoolean(false);

    @Scheduled(cron = "0 20 0 * * ?")
    public void backfillYesterday() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Map<String, Object> result = analyticsService.backfillDaily(null);
            log.info("[Analytics] daily backfill done: {}", result);
        } catch (Exception e) {
            log.error("[Analytics] daily backfill failed", e);
        } finally {
            running.set(false);
        }
    }
}
