package com.smartdock.service.support;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.domain.AiSearchService;
import com.smartdock.mapper.AiSearchServiceMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 搜索钥匙池（2026-11-26 拍板 Q3=1，照搬 {@link ProviderKeyPool} 的口径）。
 *
 * <p><b>为什么单独一个池、不复用 ProviderKeyPool</b>：那套绑死 {@code AiProviderAccess}
 * （实体、Mapper、balance 字段全不同），搜索表是另一张表、余额口径也不同（元 + 资源包次数）。
 * 但<b>选路算法与冷却时长完全照抄</b>，保证两套钥匙行为一致——管理员学一次懂两边。</p>
 *
 * <p><b>选路算法</b>（每 (tenantId|provider) 一池）：enabled 行按 id 升序 →
 * {@code seq++ % n} 轮询起点 → 跳过冷却中的钥匙 → 跳过「刚确认零余额」的钥匙
 * （balance_status=OK 且 amount=0 且 24h 内查过）→ 全被跳过时回退 id 最小那把（warn 留痕，
 * 服务不中断）。</p>
 *
 * <p><b>与 Q4 的衔接</b>：{@link #pick} 返回整行（含 id），调用方拿 id 去记资源包次数，
 * 从而做到「用的是哪把就扣哪把」；{@link #lastPickedId} 供调用方在只有 tenant+provider
 * 的旧签名路径上回查。</p>
 *
 * <p><b>冷却时长</b>：429（限流）=10 分钟；401/402/403（钥匙无效/欠费/无权）=30 分钟；
 * 其它异常不冷却（网络抖动不怪钥匙）。冷却纯内存（进程级），重启即清零。</p>
 *
 * <p><b>失败归因口径</b>（有意取舍，与 ProviderKeyPool 同）：调用链深处拿不到「这次用的是哪把」
 * 的结构化回执，池子记「最后 pick 的钥匙」。并发窗口内两个搜索同厂并发、其一失败时可能冷却到
 * 另一把——搜索 QPS 低窗口极小，且误冷却最坏后果=健康钥匙歇一个冷却期后自动回池。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchKeyPool {

    /** 429 限流冷却（毫秒） */
    static final long COOLDOWN_RATE_LIMIT_MS = 10 * 60 * 1000L;

    /** 401/402/403 钥匙级硬伤冷却（毫秒） */
    static final long COOLDOWN_KEY_REJECTED_MS = 30 * 60 * 1000L;

    /** 「刚确认零余额」的判定窗口（毫秒）：快照超出窗口不再据此跳过 */
    static final long ZERO_BALANCE_TRUST_MS = 24 * 60 * 60 * 1000L;

    private final AiSearchServiceMapper searchMapper;

    /** 每 (tenantId|provider) 一池：轮询序号 + 各钥匙冷却截止 + 最后 pick 的钥匙 id */
    private final ConcurrentHashMap<String, PoolState> pools = new ConcurrentHashMap<>();

    static final class PoolState {
        final AtomicLong seq = new AtomicLong();
        final ConcurrentHashMap<Long, Long> cooldownUntil = new ConcurrentHashMap<>();
        volatile Long lastPickedId;
    }

    private static String poolKey(String tenantId, String provider) {
        return tenantId + "|" + provider;
    }

    /**
     * 运行时取钥：轮询挑一把可用钥匙。池空（无 enabled 行 / 查询失败）返回 null，
     * 调用方回退旧行为（系统参数兜底）。
     */
    public AiSearchService pick(String tenantId, String provider) {
        List<AiSearchService> keys = enabledKeys(tenantId, provider);
        if (keys.isEmpty()) {
            return null;
        }
        PoolState pool = pools.computeIfAbsent(poolKey(tenantId, provider), k -> new PoolState());
        long now = System.currentTimeMillis();
        AiSearchService picked = null;
        int n = keys.size();
        int start = (int) (pool.seq.getAndIncrement() % n);
        for (int i = 0; i < n; i++) {
            AiSearchService cand = keys.get((start + i) % n);
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
            log.warn("[SearchKeyPool] {} 全部钥匙暂不可用(冷却/零余额), 回退主钥匙 id={} 继续服务",
                poolKey(tenantId, provider), picked.getId());
        }
        pool.lastPickedId = picked.getId();
        return picked;
    }

    /** 最近一次 pick 的钥匙 id（Q4 计数用；没有则 null） */
    public Long lastPickedId(String tenantId, String provider) {
        PoolState pool = pools.get(poolKey(tenantId, provider));
        return pool == null ? null : pool.lastPickedId;
    }

    /** 快照新鲜且为零 → 选路跳过（仅 OK 状态才信；老快照/未知不跳防误伤） */
    private static boolean isFreshZeroBalance(AiSearchService key, long now) {
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
        Long id = pool.lastPickedId;
        if (id != null) {
            pool.cooldownUntil.remove(id);
        }
    }

    /** 调用失败：按错误分类冷却「最后 pick 的钥匙」 */
    public void reportFailure(String tenantId, String provider, Throwable error) {
        PoolState pool = pools.get(poolKey(tenantId, provider));
        if (pool == null) {
            return;
        }
        Long id = pool.lastPickedId;
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
        log.warn("[SearchKeyPool] {} 钥匙 {} 进入冷却 {} 分钟（{}）",
            poolKey(tenantId, provider), id, cooldown / 60000, summarize(error));
    }

    /**
     * 错误分类（纯函数，可测）：429→10min；401/402/403→30min；其它→0（不冷却）。
     * 抓不到状态码的一律不冷却——误冷却比漏冷却伤害大。
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

    /**
     * enabled 行按 id 升序（主钥匙 = id 最小；轮询序稳定）。查询失败返回空表（回退旧行为）。
     * 拷贝后再滤——别依赖 MyBatis 返回可变 List（测试桩 List.of 不可变，removeIf 必炸）。
     */
    private List<AiSearchService> enabledKeys(String tenantId, String provider) {
        try {
            List<AiSearchService> rows = TenantHelper.ignore(() ->
                searchMapper.selectList(new LambdaQueryWrapper<AiSearchService>()
                    .eq(AiSearchService::getTenantId, tenantId)
                    .eq(AiSearchService::getProvider, provider)
                    .orderByAsc(AiSearchService::getId)));
            List<AiSearchService> copy = new ArrayList<>(rows);
            copy.removeIf(k -> !"1".equals(k.getEnabled()));
            return copy;
        } catch (Exception e) {
            log.warn("[SearchKeyPool] 钥匙池查询失败, 回退旧行为 tenant={} provider={}: {}",
                tenantId, provider, e.getMessage());
            return List.of();
        }
    }
}
