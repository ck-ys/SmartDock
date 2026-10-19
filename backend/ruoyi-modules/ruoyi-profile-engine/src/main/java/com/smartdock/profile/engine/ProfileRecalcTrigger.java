package com.smartdock.profile.engine;

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
 * 事件驱动自动重算: 同一主体的事件在合并窗口内只跑一次。
 *
 * <p>用 @Async + 内存合并标记, 不依赖 MQ; 单 JVM 内生效。
 * 多实例部署时每个实例都会算一次(结果幂等, 只是重复算力), 二期可换分布式锁/MQ。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileRecalcTrigger {

    private final ProfileEngineConfigService configService;
    private final ProfileScoreEngine scoreEngine;
    private final ProfileScoreModelService modelService;

    private final Map<String, AtomicBoolean> dirty = new ConcurrentHashMap<>();
    private final Map<String, Boolean> running = new ConcurrentHashMap<>();

    @Async
    public void onEvent(String tenantId, String source, String subjectType, String subjectId) {
        if (StringUtils.isBlank(tenantId) || StringUtils.isBlank(source) || StringUtils.isBlank(subjectId)) {
            return;
        }
        if (!configService.autoRecalc(tenantId, source)) {
            return;
        }
        String key = tenantId + "|" + source + "|" + subjectType + "|" + subjectId;
        AtomicBoolean flag = dirty.computeIfAbsent(key, k -> new AtomicBoolean(false));
        flag.set(true);
        if (running.putIfAbsent(key, Boolean.TRUE) != null) {
            // 已有批次在跑, 标记 dirty 后直接返回; 跑批线程会再拉一轮
            return;
        }
        try {
            int debounce = configService.debounceMs(tenantId, source);
            if (debounce > 0) {
                Thread.sleep(Math.min(debounce, 5000));
            }
            while (true) {
                flag.set(false);
                try {
                    TenantHelper.dynamic(tenantId, () -> scoreEngine.recalcSubject(source, subjectType, subjectId));
                    TenantHelper.dynamic(tenantId, () ->
                        modelService.evaluateSubjectIfActive(tenantId, source, subjectType, subjectId));
                } catch (Exception e) {
                    log.warn("[Profile] auto recalc failed tenant={} source={} subject={} err={}",
                        tenantId, source, subjectId, e.getMessage());
                }
                if (!flag.getAndSet(false)) {
                    break;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            running.remove(key);
            // 极端竞态: 最后一个事件在退出判断后才到, 这里补一次
            if (flag.getAndSet(false)) {
                onEvent(tenantId, source, subjectType, subjectId);
            }
        }
    }
}
