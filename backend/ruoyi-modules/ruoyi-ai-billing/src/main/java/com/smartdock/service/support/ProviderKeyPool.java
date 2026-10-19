package com.smartdock.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.domain.DcsAiProviderAccess;
import com.smartdock.mapper.DcsAiProviderAccessMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 厂商钥匙池（AI 缺口批 Q8，2026-10-14 拍板全A）：同租户同厂多把钥匙轮询 + 故障冷却。
 *
 * <p><b>选路算法</b>（每 (tenantId|provider) 一池）：
 * enabled 行按 id 升序 → {@code seq++ % n} 轮询起点 → 跳过冷却中的钥匙 → 跳过
 * 「刚确认零余额」的钥匙（balance_status=OK 且 amount=0 且 24h 内探测过——查证没钱
 * 就别再打它；老快照/未知不跳，防误伤）→ 全被跳过时回退 id 最小那把（warn 留痕，
 * 服务不中断——比「直接报错」好：网络抖动类误报冷却也能出流量）。</p>
 *
 * <p><b>失败归因口径</b>（有意取舍）：调用链深处（agentscope/OkHttp）拿不到
 * 「这次用的是哪把钥匙」的结构化回执，池子内存记「最后 pick 的钥匙」。并发窗口内
 * 两个请求同厂并发、其一失败时可能冷却到另一把——对话 QPS 低窗口极小，且误冷却
 * 最坏后果=健康钥匙歇一个冷却期后自动回池，零资损零侵入框架（不动 agentscope jar）。</p>
 *
 * <p><b>冷却时长分类</b>：429（限流）=10 分钟；401/402/403（钥匙无效/欠费/无权）=
 * 30 分钟；其它异常不冷却（网络抖动不怪钥匙）。冷却纯内存（进程级），重启即清零
 * ——宁可重启后短暂再撞一次坏钥匙，也不把瞬态故障持久化。</p>
 *
 * <p>单实例部署口径（与 AssistantPendingInterruptCache 同前提，批J 三审已登记）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProviderKeyPool {

    /** 429 限流冷却（毫秒） */
    static final long COOLDOWN_RATE_LIMIT_MS = 10 * 60 * 1000L;

    /** 401/402/403 钥匙级硬伤冷却（毫秒） */
    static final long COOLDOWN_KEY_REJECTED_MS = 30 * 60 * 1000L;

    /** 「刚确认零余额」的判定窗口（毫秒）：快照超出窗口不再据此跳过 */
    static final long ZERO_BALANCE_TRUST_MS = 24 * 60 * 60 * 1000L;

    private final DcsAiProviderAccessMapper accessMapper;

    /** 每 (tenantId|provider) 一池：轮询序号 + 各钥匙冷却截止 + 最后 pick 的钥匙 */
    private final ConcurrentHashMap<String, PoolState> pools = new ConcurrentHashMap<>();

    static final class PoolState {
        final AtomicLong seq = new AtomicLong();
        final ConcurrentHashMap<Long, Long> cooldownUntil = new ConcurrentHashMap<>();
        final AtomicReference<Long> lastPicked = new AtomicReference<>();
    }

    private static String poolKey(String tenantId, String provider) {
        return tenantId + "|" + provider;
    }

    /**
     * 运行时取钥（hydrate 调用）：轮询挑一把可用钥匙。
     * 池空（无 enabled 行/查询失败）返回 null——调用方回退旧行为（首行/行内列）。
     */
    public DcsAiProviderAccess pick(String tenantId, String provider) {
        List<DcsAiProviderAccess> keys = enabledKeys(tenantId, provider);
        if (keys.isEmpty()) {
            return null;
        }
        PoolState pool = pools.computeIfAbsent(poolKey(tenantId, provider), k -> new PoolState());
        long now = System.currentTimeMillis();
        // 零余额跳过判定先算好（每把独立判龄）
        DcsAiProviderAccess picked = null;
        int n = keys.size();
        int start = (int) (pool.seq.getAndIncrement() % n);
        for (int i = 0; i < n; i++) {
            DcsAiProviderAccess cand = keys.get((start + i) % n);
            Long cd = pool.cooldownUntil.get(cand.getId());
            if (cd != null) {
                if (cd > now) {
                    continue;
                }
                pool.cooldownUntil.remove(cand.getId(), cd);
            }
            if (isFreshZeroBalance(cand, now)) {
                continue;
            }
            picked = cand;
            break;
        }
        if (picked == null) {
            // 全被跳过（冷却/零余额）：回退 id 最小那把，warn 留痕——服务不中断优先
            picked = keys.get(0);
            log.warn("[KeyPool] {} 全部钥匙暂不可用(冷却/零余额), 回退主钥匙 id={} 继续服务",
                poolKey(tenantId, provider), picked.getId());
        }
        pool.lastPicked.set(picked.getId());
        return picked;
    }

    /** 快照新鲜且为零 → 选路跳过（仅 OK 状态才信；FAILED/老快照不跳防误伤） */
    private static boolean isFreshZeroBalance(DcsAiProviderAccess key, long now) {
        if (!"OK".equals(key.getBalanceStatus()) || key.getBalanceCheckedAt() == null) {
            return false;
        }
        BigDecimal amount = key.getBalanceAmount();
        return amount != null && amount.compareTo(BigDecimal.ZERO) <= 0
            && now - key.getBalanceCheckedAt().getTime() <= ZERO_BALANCE_TRUST_MS;
    }

    /** 调用成功：清该池「最后 pick 钥匙」的冷却（冷却中的健康钥匙提前回池） */
    public void reportSuccess(String tenantId, String provider) {
        PoolState pool = pools.get(poolKey(tenantId, provider));
        if (pool == null) {
            return;
        }
        Long id = pool.lastPicked.get();
        if (id != null) {
            pool.cooldownUntil.remove(id);
        }
    }

    /**
     * 调用失败：按错误分类冷却「最后 pick 的钥匙」。
     * 归因口径见类头（并发窗口极小+自愈，有意取舍）。
     */
    public void reportFailure(String tenantId, String provider, Throwable error) {
        PoolState pool = pools.get(poolKey(tenantId, provider));
        if (pool == null) {
            return;
        }
        Long id = pool.lastPicked.get();
        if (id == null) {
            return;
        }
        long cooldown = classify(error);
        if (cooldown <= 0) {
            return;
        }
        long until = System.currentTimeMillis() + cooldown;
        Long prev = pool.cooldownUntil.get(id);
        if (prev != null && prev >= until) {
            return; // 已有更长冷却不缩短
        }
        pool.cooldownUntil.put(id, until);
        log.warn("[KeyPool] {} 钥匙 {} 进入冷却 {} 分钟（{}）",
            poolKey(tenantId, provider), id, cooldown / 60000, summarize(error));
    }

    /**
     * 错误分类（纯函数，可测）：429→10min；401/402/403→30min；其它→0（不冷却）。
     * 异常消息里抓状态码——OpenAI 兼容栈的 HTTP 错误普遍带 "401 Unauthorized" 类文本；
     * 抓不到状态码的一律不冷却（误冷却比漏冷却伤害大：漏了=该钥匙继续被打直到管理员
     * 介入；误了=健康钥匙白白歇 30 分钟）。
     */
    static long classify(Throwable error) {
        String msg = messageOf(error);
        if (msg == null) {
            return 0;
        }
        if (msg.contains("429") || msg.toLowerCase().contains("rate limit")) {
            return COOLDOWN_RATE_LIMIT_MS;
        }
        if (msg.contains("401") || msg.contains("402") || msg.contains("403")
            || msg.toLowerCase().contains("unauthorized") || msg.toLowerCase().contains("insufficient")) {
            return COOLDOWN_KEY_REJECTED_MS;
        }
        return 0;
    }

    private static String messageOf(Throwable error) {
        StringBuilder sb = new StringBuilder();
        Throwable t = error;
        int depth = 0;
        while (t != null && depth++ < 5) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(';');
            }
            t = t.getCause();
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String summarize(Throwable error) {
        String m = error.getMessage();
        return error.getClass().getSimpleName() + (m == null ? "" : ": " + m.substring(0, Math.min(120, m.length())));
    }

    /** enabled 行按 id 升序（主钥匙=id 最小；轮询序稳定）。查询失败返回空表（回退旧行为）。
     *  拷贝后再滤——MyBatis 返回可变 List 但别依赖它（测试桩 List.of 不可变，removeIf 必炸） */
    private List<DcsAiProviderAccess> enabledKeys(String tenantId, String provider) {
        try {
            List<DcsAiProviderAccess> rows = TenantHelper.ignore(() ->
                accessMapper.selectList(new LambdaQueryWrapper<DcsAiProviderAccess>()
                    .eq(DcsAiProviderAccess::getTenantId, tenantId)
                    .eq(DcsAiProviderAccess::getProvider, provider)
                    .orderByAsc(DcsAiProviderAccess::getId)));
            List<DcsAiProviderAccess> copy = new java.util.ArrayList<>(rows);
            copy.removeIf(k -> "0".equals(k.getEnabled()));
            return copy;
        } catch (Exception e) {
            log.warn("[KeyPool] 钥匙池查询失败, 回退旧行为 tenant={} provider={}: {}",
                tenantId, provider, e.getMessage());
            return List.of();
        }
    }

    /** 主钥匙（enabled 按 id 升序第一行；无 enabled 行取 id 最小行）——探测类端点取钥口径 */
    public DcsAiProviderAccess primaryKey(String tenantId, String provider) {
        List<DcsAiProviderAccess> rows;
        try {
            rows = TenantHelper.ignore(() ->
                accessMapper.selectList(new LambdaQueryWrapper<DcsAiProviderAccess>()
                    .eq(DcsAiProviderAccess::getTenantId, tenantId)
                    .eq(DcsAiProviderAccess::getProvider, provider)
                    .orderByAsc(DcsAiProviderAccess::getId)));
        } catch (Exception e) {
            return null;
        }
        return rows.stream()
            .min(Comparator.comparing(DcsAiProviderAccess::getId))
            .orElse(null);
    }

    /** 测试可见：清空池态（进程内冷却/序号） */
    void resetForTest() {
        pools.clear();
    }

    /** 测试可见：某钥匙当前冷却截止（0=不在冷却） */
    long cooldownUntil(String tenantId, String provider, Long accessId) {
        PoolState pool = pools.get(poolKey(tenantId, provider));
        if (pool == null) {
            return 0;
        }
        Long until = pool.cooldownUntil.get(accessId);
        return until == null ? 0 : until;
    }

    /** 钥匙删除/禁用后调用：清其冷却防悬挂（幂等，无该键时零动作） */
    public void evict(String tenantId, String provider, Long accessId) {
        PoolState pool = pools.get(poolKey(tenantId, provider));
        if (pool != null && accessId != null) {
            pool.cooldownUntil.remove(accessId);
        }
    }

    /** 余额快照落库后的新鲜时间戳（供单测对齐口径；生产不直接用） */
    static Date freshCheckedAt() {
        return new Date();
    }
}
