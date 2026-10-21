package com.smartdock.analytics.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 事件写入后异步刷新最近两天的会话与日汇总。
 *
 * <p>合并窗口(2 秒内多条事件只重建一次)仍是 JVM 内标记——它是"少干活"的优化,
 * 不是正确性边界; 正确性由 {@link AnalyticsService#rebuildRecent} 的分布式锁保证:
 * 双实例各跑各的去抖循环时, 同一源同一时刻只有一个真正执行重建, 另一个跳过
 * 并保留脏标记等下一个事件补触发(批10 Q1)。</p>
 *
 * <p>批10 顺手修: dirty 标记条目在空闲退出时移除, 修复按(租户|源)无限累积。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsRollupTrigger {

    private final AnalyticsService analyticsService;

    private final Map<String, AtomicBoolean> dirty = new ConcurrentHashMap<>();
    private final Map<String, Boolean> running = new ConcurrentHashMap<>();

    @Async
    public void onEvent(String tenantId, String source, Date occurredAt) {
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(source)) {
            return;
        }
        String key = tenantId + "|" + source;
        AtomicBoolean flag = dirty.computeIfAbsent(key, k -> new AtomicBoolean(false));
        flag.set(true);
        if (running.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        boolean interrupted = false;
        try {
            Thread.sleep(2000);
            while (true) {
                flag.set(false);
                boolean ran = false;
                try {
                    Boolean result = TenantHelper.dynamic(tenantId,
                        () -> analyticsService.rebuildRecent(tenantId, source, occurredAt));
                    ran = Boolean.TRUE.equals(result);
                } catch (Exception e) {
                    log.warn("[Analytics] rollup failed source={} err={}", source, e.getMessage());
                }
                if (!ran) {
                    // 被手动重建持锁跳过: 保留脏标记退出, 下一个事件进来会补触发
                    break;
                }
                if (!flag.getAndSet(false)) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            interrupted = true;
        } finally {
            running.remove(key);
            // 空闲时清掉去抖条目, 修复无界增长; 仍有新事件则递归补一轮
            if (!interrupted && flag.getAndSet(false)) {
                onEvent(tenantId, source, occurredAt);
            } else {
                dirty.remove(key);
            }
        }
    }
}
