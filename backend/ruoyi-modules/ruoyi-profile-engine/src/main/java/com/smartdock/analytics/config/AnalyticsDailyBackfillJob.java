package com.smartdock.analytics.config;

import com.smartdock.analytics.engine.AnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * 批5B 日汇总兜底: 每晚补 D-1..D-3 的会话+日汇总行(小修包件9 起三天窗口)。
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
 * <p>小修包件9(2026-11-02): 每晚幂等回填 D-1..D-3 三天——此前只跑昨天,
 * 00:20 前后宕机一天, 次日只跑新的昨天, 宕机那天的行就永久缺(深度优化批 v2.6 登记)。
 * 多跑两天只多两次小扫描(rebuildRange 本就幂等 upsert)。</p>
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
    public void backfillRecentDays() {
        if (!enabled) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            for (String day : backfillDays(LocalDate.now(ZoneId.systemDefault()))) {
                try {
                    Map<String, Object> result = analyticsService.backfillDaily(day);
                    log.info("[Analytics] daily backfill done: {}", result);
                } catch (Exception dayEx) {
                    // 单日失败不拖垮后续天——D-1 挂了 D-2/D-3 照补
                    log.error("[Analytics] daily backfill failed day={}", day, dayEx);
                }
            }
        } finally {
            running.set(false);
        }
    }

    /** 件9 纯函数: 回填日清单 = 昨天、前天、大前天(近到远) */
    static List<String> backfillDays(LocalDate today) {
        LocalDate yesterday = today.minusDays(1);
        return List.of(yesterday.toString(), yesterday.minusDays(1).toString(),
            yesterday.minusDays(2).toString());
    }
}
