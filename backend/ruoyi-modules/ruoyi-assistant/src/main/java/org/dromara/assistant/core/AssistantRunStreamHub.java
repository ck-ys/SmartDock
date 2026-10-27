package org.dromara.assistant.core;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * B 批题5「续看」（2026-10-27 拍板 甲案）：进行中 run 的进程内事件广播汇——
 * 「先补历史、再续直播」。
 *
 * <p><b>机制</b>：每个 run 一条 Reactor replay 汇（{@code Sinks.many().replay().limit(RING)}）——
 * 流侧（{@link AssistantEventEnricher}，出网最终视图=闸后）是唯一喂汇者；刷新后的前端经
 * attach 端点订阅，汇自动先重放保留窗内全部事件（重建进行中回答的已出字部分）再续播实时流。
 * 天然不碰框架限制（AgentRun 同一 run 不许二次订阅——汇只有流侧一处订阅 AGUI 源，重挂全从汇取）。</p>
 *
 * <p><b>桥接（{@link #bridge}，题5 收口补件）</b>：starter 的 SSE 写失败（断连后 IllegalStateException
 * 等未捕获异常）会取消整链杀死 agent——桥接把原链改由本汇自订阅驱动，HTTP 层（含 attach 观众）
 * 一律订汇热流：断连只掉订阅者，生成续活；零观众 60s 弃链走既有失联收割（成本保护不倒退）。</p>
 *
 * <p><b>生命周期</b>：RUN_FINISHED/RUN_ERROR 到达即 complete 汇，保留窗延留 {@code EVICT_AFTER}
 * 供终局后晚到的 attach 重放（终局答案随后也在历史消息里）；超时/超量逐出。溢出（单轮事件数超
 * 保留窗）时 attach 先补一帧 dcs-resume-truncated 提示帧——头部内容请稍后从历史消息看全文。</p>
 *
 * <p><b>施工总则「缝」</b>：多实例批把本件换 Redis pub/sub 广播时，消费方（enricher 喂入 /
 * attach 端点订阅）零改动——只动本类内部实现。</p>
 */
@Slf4j
@Component
public class AssistantRunStreamHub {

    /** 单 run 事件保留窗（闸后视图：思考内容帧已被过滤，长轮主要=文本增量/工具/心跳，4096 富余） */
    public static final int RING = 4096;
    /** 终局后保留窗留驻时长（晚到的刷新重放用；终局全文随后在历史消息里） */
    static final long EVICT_AFTER_SECONDS = 300;
    /** 进程内最大并发 run 数（超量逐出最老终局流；再超=逐出最老未终局流兜底防泄漏） */
    private static final int MAX_RUNS = 256;

    /** attach 时的截断提示帧名（前端见名即知头部有缺，提示用户稍后看全文） */
    public static final String TRUNCATED_FRAME = "dcs-resume-truncated";

    /** 题5 桥接孤儿判定：零观众持续该时长即弃链（弃链后事件停，由既有失联收割终局化+退回合——
     *  成本保护不倒退，只是把「断连即杀」宽限成「断连 60s 无人重挂才杀」，刷新重开窗口足够） */
    static final long ORPHAN_MS = 60_000L;

    /** 挂死兜底：桥接 run 事件停滞超该时长即弃链——模型/工具挂死时 v2.0.4 无 run 级
     *  超时接线，DB 侧 closeStuckRuns 只迁行不停进程内链；10min（远宽于既有 60s 事件
     *  失联收割的触发窗）只在收割器本身也失效的极端场景兜底弃链 */
    static final long HANG_MS = 600_000L;

    private final Map<String, RunStream> runs = new ConcurrentHashMap<>();
    private final ScheduledExecutorService evictor =
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "assistant-run-hub-evictor");
            t.setDaemon(true);
            return t;
        });

    {
        // 孤儿清扫与终局逐出共用单线程调度（均轻量短任务）
        evictor.scheduleWithFixedDelay(this::sweepOrphans, ORPHAN_MS, ORPHAN_MS, TimeUnit.MILLISECONDS);
    }

    static final class RunStream {
        final Sinks.Many<AguiEvent> sink = Sinks.many().replay().limit(RING);
        final AtomicBoolean terminal = new AtomicBoolean(false);
        /** 累计喂入帧数（attach 时判溢出：>RING 即保留窗头部有缺） */
        final AtomicLong fed = new AtomicLong();
        final long createdAt = System.currentTimeMillis();
        volatile long lastEventAt = System.currentTimeMillis();
        /** 桥接驱动（订阅原链使之在 HTTP 断连后继续跑；null=未桥接的直喂 run/测试） */
        volatile reactor.core.Disposable driver;
        /** 当前观众数（原 HTTP 订阅 + attach 订阅） */
        final java.util.concurrent.atomic.AtomicInteger viewers = new java.util.concurrent.atomic.AtomicInteger();
        /** 观众归零时刻（孤儿判定起点；初始=创建时刻——从未有观众的桥接 run 也会被扫） */
        volatile long viewersZeroSince = System.currentTimeMillis();
        /** 孤儿已弃链标记（防重复 dispose 与清扫噪音） */
        final AtomicBoolean orphanDisposed = new AtomicBoolean(false);
    }

    /** 流侧唯一喂入口（每批出网事件；同 run 串行来自流线程，跨 run 并发安全） */
    public void accept(String runId, List<AguiEvent> events) {
        if (runId == null || runId.isBlank() || events == null || events.isEmpty()) {
            return;
        }
        // 深审 P2-1 回修（dspro/glm53）：容量治理移出 computeIfAbsent 映射函数——
        // CHM 契约禁止映射函数改本 map（同 bin 哈希撞 ReservationNode 静默失败），
        // 且 bin 锁内跑 dispose 的取消回调链是持锁副作用面
        evictIfNeeded();
        RunStream rs = runs.computeIfAbsent(runId, k -> new RunStream());
        rs.lastEventAt = System.currentTimeMillis();
        boolean terminalHere = false;
        for (AguiEvent e : events) {
            if (e == null) {
                continue;
            }
            rs.fed.incrementAndGet();
            Sinks.EmitResult r = rs.sink.tryEmitNext(e);
            if (!r.isSuccess()) {
                // 终局后/无订阅者失败仅记录——不因广播失败打断主流
                log.debug("[run-hub] run {} 帧入汇失败 {}（类型 {}）", runId, r, e.getType());
            }
            if (e.getType() == AguiEventType.RUN_FINISHED || e.getType() == AguiEventType.RUN_ERROR) {
                terminalHere = true;
            }
        }
        if (terminalHere && rs.terminal.compareAndSet(false, true)) {
            rs.sink.tryEmitComplete();
            scheduleEvict(runId, rs);
        }
    }

    /**
     * 题5 桥接（断连不拆链）：适配器原链由本汇自订阅驱动（enricher 在链内照常喂汇），
     * 返回汇的热流给 HTTP 层——客户端断连/发送失败只掉订阅者，agent 生成继续；
     * 零观众超 {@link #ORPHAN_MS} 由清扫器弃链（走既有失联收割，退回合）。
     * 同 runId 重入（断线重试/HITL resume 复用 runId）：弃旧链换新汇，新订阅者重放全新。
     */
    public Flux<AguiEvent> bridge(String runId, Flux<AguiEvent> source) {
        if (runId == null || runId.isBlank() || source == null) {
            return source;
        }
        RunStream rs;
        synchronized (this) {
            RunStream old = runs.get(runId);
            if (old != null) {
                if (old.driver != null) {
                    old.driver.dispose();
                }
                // 深审 P2-2 回修：换汇前先收口旧汇——旧 HTTP 观众（若有）不再无限等 complete
                old.sink.tryEmitComplete();
                runs.remove(runId);
            }
            rs = new RunStream();
            runs.put(runId, rs);
        }
        rs.driver = source.subscribe(
            e -> { /* 喂汇由链内 enricher 单点负责；driver 只负责「订阅着」让链活着 */ },
            err -> completeIfAlive(rs, runId, "链异常"),
            () -> completeIfAlive(rs, runId, "链完成"));
        log.debug("[run-hub] run {} 桥接接管（断连不拆链，孤儿 {}s 兜底）", runId, ORPHAN_MS / 1000);
        return watched(rs);
    }

    /** 链终局但 enricher 未见过终态帧（罕见）——收口汇防 attach 永挂 */
    private void completeIfAlive(RunStream rs, String runId, String why) {
        if (rs.terminal.compareAndSet(false, true)) {
            rs.sink.tryEmitComplete();
            scheduleEvict(runId, rs);
            log.debug("[run-hub] run {} {}——汇收口", runId, why);
        }
    }

    /** 汇流包装观众计数（attach 端点与桥接返回共用；订阅/退订驱动孤儿计时） */
    private Flux<AguiEvent> watched(RunStream rs) {
        return rs.sink.asFlux()
            .doOnSubscribe(s -> {
                rs.viewers.incrementAndGet();
                rs.viewersZeroSince = -1;
            })
            .doFinally(sig -> {
                if (rs.viewers.decrementAndGet() <= 0) {
                    rs.viewersZeroSince = System.currentTimeMillis();
                }
            });
    }

    /** 孤儿清扫：未终局 + 已桥接 + 零观众超窗 → 弃链并立即收口摘除（事件停后既有失联收割
     *  终局化+退回合）；另加挂死兜底——桥接 run 停滞超 {@link #HANG_MS} 无论观众多寡都弃链
     *  （模型/工具挂死时 v2.0.4 无 run 超时接线，DB 侧 closeStuck 只迁行不停链）。
     *  深审 P1-3/P2-3 回修：弃链必须 complete+remove——只 dispose 会留僵尸汇可再挂（重放后
     *  永静默挂起）且条目与保留窗内存无人回收；全方法体包 try/catch 防周期任务异常静默停排。 */
    private void sweepOrphans() {
        try {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, RunStream> e : runs.entrySet()) {
                RunStream rs = e.getValue();
                if (rs.terminal.get() || rs.driver == null) {
                    continue;
                }
                boolean orphan = rs.viewers.get() == 0 && rs.viewersZeroSince > 0
                    && now - rs.viewersZeroSince >= ORPHAN_MS;
                boolean hung = now - rs.lastEventAt >= HANG_MS;
                if ((orphan || hung) && rs.orphanDisposed.compareAndSet(false, true)) {
                    reactor.core.Disposable d = rs.driver;
                    if (d != null && !d.isDisposed()) {
                        d.dispose();
                    }
                    completeIfAlive(rs, e.getKey(), orphan ? "孤儿弃链" : "停滞弃链");
                    // 身份移除：防同 runId 换新汇后被本条误摘（深审 P2-2）
                    runs.remove(e.getKey(), rs);
                    log.info("[run-hub] run {} {}——弃链收口摘除（{}）", e.getKey(),
                        orphan ? "零观众 " + (ORPHAN_MS / 1000) + "s" : "事件停滞 " + (HANG_MS / 60000) + "min",
                        rs.viewers.get() + " 观众");
                }
            }
        } catch (Exception ex) {
            // scheduleWithFixedDelay 任务抛异常即永久停排——清扫必须自愈继续
            log.warn("[run-hub] 孤儿清扫轮异常（下一 tick 继续）：{}", ex.getMessage());
        }
    }

    private void scheduleEvict(String runId, RunStream rs) {
        evictor.schedule(() -> {
            // 身份移除（深审 P2-2）：同 runId 后续换新汇时，旧定时任务不得误杀新流
            if (runs.remove(runId, rs) && rs.driver != null) {
                rs.driver.dispose();
            }
        }, EVICT_AFTER_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * attach：返回该 run 的事件流（订阅时自动先重放保留窗再续直播）。
     * run 不在汇（未开始/已逐出/进程重启过）返回 empty——调用方回退轮询等终态。
     */
    public Optional<Flux<AguiEvent>> attach(String runId) {
        if (runId == null) {
            return Optional.empty();
        }
        RunStream rs = runs.get(runId);
        return rs == null ? Optional.empty() : Optional.of(watched(rs));
    }

    /** 该 run 自创建起累计喂入帧数（attach 时判溢出：>RING 即头部有缺） */
    public long fedCount(String runId) {
        // replay().limit 汇不暴露计数，用每 run 计数器——见 fed 计数
        RunStream rs = runs.get(runId);
        return rs == null ? -1 : rs.fed.get();
    }

    /** 进程内 run 数（观测/测试用） */
    public int size() {
        return runs.size();
    }

    private void evictIfNeeded() {
        if (runs.size() < MAX_RUNS) {
            return;
        }
        // 先逐出最老终局流；全部未终局才逐出最老未终局（防内存无限增长兜底，
        // 未终局流被逐=该 run 刷新后走轮询回退，不影响主流）
        RunStream oldestTerminal = null;
        String oldestTerminalKey = null;
        RunStream oldest = null;
        String oldestKey = null;
        for (Map.Entry<String, RunStream> e : runs.entrySet()) {
            if (oldest == null || e.getValue().createdAt < oldest.createdAt) {
                oldest = e.getValue();
                oldestKey = e.getKey();
            }
            if (e.getValue().terminal.get()
                && (oldestTerminal == null || e.getValue().createdAt < oldestTerminal.createdAt)) {
                oldestTerminal = e.getValue();
                oldestTerminalKey = e.getKey();
            }
        }
        String drop = oldestTerminalKey != null ? oldestTerminalKey : oldestKey;
        if (drop != null) {
            RunStream rs = runs.remove(drop);
            if (rs != null && rs.driver != null) {
                rs.driver.dispose();
            }
            log.warn("[run-hub] 超量逐出 run {}（size 回落 {}）", drop, runs.size());
        }
    }

}
