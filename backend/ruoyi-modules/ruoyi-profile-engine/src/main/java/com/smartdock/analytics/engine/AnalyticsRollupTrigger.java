package com.smartdock.analytics.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 事件写入后异步刷新最近两天的会话与日汇总。
 *
 * <p>单 JVM 合并窗口：同一业务源 2 秒内的多条事件只重建一次。</p>
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
    public void onEvent(String tenantId, String source) {
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(source)) {
            return;
        }
        String key = tenantId + "|" + source;
        AtomicBoolean flag = dirty.computeIfAbsent(key, k -> new AtomicBoolean(false));
        flag.set(true);
        if (running.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        try {
            Thread.sleep(2000);
            while (true) {
                flag.set(false);
                try {
                    TenantHelper.dynamic(tenantId, () -> analyticsService.rebuildRecent(tenantId, source));
                } catch (Exception e) {
                    log.warn("[Analytics] rollup failed source={} err={}", source, e.getMessage());
                }
                if (!flag.getAndSet(false)) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running.remove(key);
            if (flag.getAndSet(false)) {
                onEvent(tenantId, source);
            }
        }
    }
}
