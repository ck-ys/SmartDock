package com.smartdock.profile.engine;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.redisson.api.RLock;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 事件驱动自动重算: 同一主体的事件在合并窗口内只跑一次。
 *
 * <p>合并窗口(按 debounceMs)仍是 JVM 内标记(少干活的优化); 正确性边界换成
 * 分布式锁(批10 Q1): 双实例同时收到同主体事件时, 只有一个实例真正执行
 * recalcSubject + 模型增量, 另一个直接跳过——消除"结果幂等但算力翻倍"。</p>
 *
 * <p>批10 顺手修: dirty/running 按"租户|源|类型|主体"为键, 主体数无界,
 * 空闲退出时移除条目防长期运行内存涨。</p>
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
    private final org.redisson.api.RedissonClient redisson;

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
        boolean interrupted = false;
        try {
            int debounce = configService.debounceMs(tenantId, source);
            if (debounce > 0) {
                Thread.sleep(Math.min(debounce, 5000));
            }
            while (true) {
                flag.set(false);
                recalcOnce(tenantId, source, subjectType, subjectId);
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
                onEvent(tenantId, source, subjectType, subjectId);
            } else {
                dirty.remove(key);
            }
        }
    }

    private void recalcOnce(String tenantId, String source, String subjectType, String subjectId) {
        RLock lock = redisson.getLock(ProfileLocks.recalcSubject(tenantId, source, subjectType, subjectId));
        boolean acquired;
        try {
            acquired = lock.tryLock(0, ProfileLocks.RECALC_LEASE_MINUTES, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        if (!acquired) {
            // 另一实例正在算同一主体: 结果幂等, 跳过省算力——但持锁者读取点可能早于刚提交的事件,
            // 短等一轮再试一次(批11 Q7), 仍拿不到才放弃(下个事件自愈)
            try {
                Thread.sleep(500);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            try {
                acquired = lock.tryLock(0, ProfileLocks.RECALC_LEASE_MINUTES, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (!acquired) {
                return;
            }
        }
        try {
            TenantHelper.dynamic(tenantId, () -> scoreEngine.recalcSubject(source, subjectType, subjectId));
            TenantHelper.dynamic(tenantId, () ->
                modelService.evaluateSubjectIfActive(tenantId, source, subjectType, subjectId));
        } catch (Exception e) {
            log.warn("[Profile] auto recalc failed tenant={} source={} subject={} err={}",
                tenantId, source, subjectId, e.getMessage());
        } finally {
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                log.warn("[Profile] recalc lock unlock failed subject={} : {}", subjectId, e.getMessage());
            }
        }
    }
}
