package org.dromara.assistant.core;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 回合记忆账本（ckys3 解锁板题8/题9 案1，2026-11-12）：把「本轮注入了几条记忆」与
 * 「本轮覆盖了哪条旧记忆」攒在**会话（threadId）**维度，{@link AssistantEventEnricher}
 * 在 RUN_FINISHED 帧前取走并发 {@code memory_turn} CUSTOM 帧，前端在回答下方出一行小字/一张小卡。
 *
 * <p>键为什么是 threadId 而不是 runId（2026-11-12 活体回修）：模型装饰器与工具执行都在
 * Reactor 的 loomBoundedElastic 线程上（日志实证 {@code [loomBoundedElastic-847]}），
 * 而 {@link AssistantRunContext} 的 ThreadLocal 只在事件流线程被 recorder 刷新，跨线程读不到
 * → 记不上 runId，帧永远不发（同款取舍见 BillingModelDecorator「落账线程可能拿不到 runId」）。
 * threadId 则由两处**显式**持有：工具体由 {@code ToolDeps.aguiThreadId}，注入装饰器由装配时
 * 传入；enricher 侧取 {@code context.getThreadId()}。同一 thread 同一时刻只跑一个 run
 * （单飞行闸），故会话维度不串轮。</p>
 *
 * <p>防陈旧：注入统计每次模型调用覆写（count&lt;=0 直接清空），超 {@link #MAX_AGE_MS}
 * 的记录一并不认（run 中途断链没有 RUN_FINISHED 时，下一轮不会读到上一轮的旧值）。</p>
 */
@Component
public class AssistantMemoryTurnLedger {

    /** 单回合注入统计 */
    public record Injected(int count, int chars) {
    }

    /** 单回合覆盖事件（旧内容 → 新内容） */
    public record Superseded(String from, String to) {
    }

    /** 回合快照（前端一次拿到两块信息） */
    public record TurnMemory(Injected injected, List<Superseded> superseded) {
    }

    /** 陈旧阈值：超过即不认（正常回合远小于此值，防御中途断链的残留） */
    private static final long MAX_AGE_MS = 15 * 60 * 1000L;

    private static final int MAX_ENTRIES = 512;

    private final Map<String, AtomicInteger> injectedCount = ring();
    private final Map<String, AtomicInteger> injectedChars = ring();
    private final Map<String, Set<Superseded>> superseded = Collections.synchronizedMap(
        new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Set<Superseded>> eldest) {
                return size() > MAX_ENTRIES;
            }
        });
    /** 最近一次记账时刻（判陈旧用） */
    private final Map<String, Long> touchedAt = ringLong();

    private static Map<String, AtomicInteger> ring() {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, AtomicInteger> eldest) {
                return size() > MAX_ENTRIES;
            }
        });
    }

    private static Map<String, Long> ringLong() {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > MAX_ENTRIES;
            }
        });
    }

    /**
     * 注入装饰器每次模型调用调：覆写为本轮条数/字数（同回合多次模型调用=取最后一次，
     * 不累加——注入量按单次出网算）。count&lt;=0（本轮没命中/已关闭）=显式清空。
     */
    public void recordInjected(String threadId, int count, int chars) {
        if (threadId == null || threadId.isBlank()) {
            return;
        }
        if (count <= 0) {
            injectedCount.remove(threadId);
            injectedChars.remove(threadId);
            return;
        }
        injectedCount.put(threadId, new AtomicInteger(count));
        injectedChars.put(threadId, new AtomicInteger(chars));
        touchedAt.put(threadId, System.currentTimeMillis());
    }

    /** 保存口覆盖旧条时调（题8：小卡要显示 旧→新） */
    public void recordSuperseded(String threadId, String from, String to) {
        if (threadId == null || threadId.isBlank() || from == null || to == null) {
            return;
        }
        superseded.computeIfAbsent(threadId, k -> Collections.synchronizedSet(new LinkedHashSet<>()))
            .add(new Superseded(from, to));
        touchedAt.put(threadId, System.currentTimeMillis());
    }

    /** Enricher 在 RUN_FINISHED 前取走；无记录/已陈旧返回 null（=不发帧） */
    public TurnMemory take(String threadId) {
        if (threadId == null) {
            return null;
        }
        AtomicInteger c = injectedCount.remove(threadId);
        AtomicInteger ch = injectedChars.remove(threadId);
        Set<Superseded> s = superseded.remove(threadId);
        Long at = touchedAt.remove(threadId);
        boolean stale = at != null && System.currentTimeMillis() - at > MAX_AGE_MS;
        if (stale) {
            return null;
        }
        Injected injected = c == null ? null : new Injected(c.get(), ch == null ? 0 : ch.get());
        if (injected == null && (s == null || s.isEmpty())) {
            return null;
        }
        List<Superseded> list = s == null ? List.of() : new ArrayList<>(s);
        return new TurnMemory(injected, list);
    }
}
