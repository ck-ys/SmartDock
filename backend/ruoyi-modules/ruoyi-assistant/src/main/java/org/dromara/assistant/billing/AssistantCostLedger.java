package org.dromara.assistant.billing;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.LockSupport;

/**
 * 回合成本累加器（批E Task E4）：模型装饰器逐笔累计 tokens/折算金额，
 * enricher 在 RUN_FINISHED 帧前取走并发 cost_meta CUSTOM 帧。
 *
 * <p>口径：显示值=后端按当前生效价折算的元值（拍板2 A+「后端元值，前端禁自算价格」）；
 * 账本流水仍是唯一真源，角标仅提示性展示（ReAct 多笔模型调用累计）。
 * runId 侧来自 {@link org.dromara.assistant.core.AssistantRunContext}（流线程 ThreadLocal，
 * 装饰器 record 落在同流线程时可达；异步线程错过=角标缺省，无害降级）。</p>
 */
@Component
public class AssistantCostLedger {

    /** runId → [0]=累计 tokens, [1]=累计金额(微元,避免浮点) */
    private final Map<String, long[]> runs;

    /** runId → 末次模型调用的 input(+cached)+output tokens（批B B1：上下文环数据源）。
     *  覆写式（last-wins）——与累计账本刻意不同口径：上下文占用=最后一次调用重发全量历史，
     *  多子调用求和会虚高（cline 同款公式）。peek 取值不清除：TEXT_MESSAGE_END 与 RUN_FINISHED
     *  存在异步时序差，清除只发生在 finishRun 终态。同款环形截断防泄漏。 */
    private final Map<String, Long> lastUsages;

    /** 兜底清理上限：超 30 分钟未取走的残段（run 中断未收尾）按插入序粗粒度防泄漏 */
    private static final int MAX_ENTRIES = 512;

    /** 批6-#2：模型调用在飞计数（threadId → 次数）——失联收割判别器（键=装饰器构造
     *  注入的会话 threadId，与 RunRecorder.runStepKeys 同源）。在飞>0 = 活 run（慢首
     *  token/长生成/传输层重试期间事件静默是正常的，不是客户端断连）；断连拆链 → 模型
     *  流被取消 → doFinally 清零 → 收割放行。计数制支持 ReAct 同轮多次子调用嵌套标记。 */
    private final Map<String, java.util.concurrent.atomic.AtomicInteger> inFlights;

    public AssistantCostLedger() {
        // AU-45(1008)：环形截断——超限时按插入序淘汰最旧条目，不再全表 clear
        // （全清会把正在生成的回合账一并抹掉=费用角标中途消失）；容量内零淘汰
        this.runs = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
                return size() > MAX_ENTRIES;
            }
        });
        this.lastUsages = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
                return size() > MAX_ENTRIES;
            }
        });
        this.inFlights = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            // dspro P1-3 回修：只淘汰「计数已归零」的键——原按插入序无条件淘汰会把在飞键
            // 踢出表（该 run 失去收割豁免），且被淘汰键重建计数器后旧调用的 doFinally 会
            // 跨对象误减新计数。副作用：极端场景（>512 个同时非零）表会超限增长，由
            // 重启兜底（512 并发在飞本身已异常）
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, java.util.concurrent.atomic.AtomicInteger> eldest) {
                return size() > MAX_ENTRIES && eldest.getValue().get() <= 0;
            }
        });
    }

    /** 批6-#2：模型调用订阅开始（流线程）标记在飞 */
    public void markInFlight(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        inFlights.computeIfAbsent(runId, k -> new java.util.concurrent.atomic.AtomicInteger())
            .incrementAndGet();
    }

    /** 批6-#2：模型调用终态（完成/取消/错误，doFinally）清在飞；归零即移除键。
     *  dspro P1-3 回修：computeIfPresent 单原子操作——原 get→decrement→remove 三步在
     *  「旧调用清零瞬间被新 run mark 抢入」时会删掉新 run 的活计数（活 run 被判不在飞
     *  →静默 40s 被误收割），原子复合操作消灭交错窗口 */
    public void clearInFlight(String runId) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        inFlights.computeIfPresent(runId, (k, n) -> n.decrementAndGet() <= 0 ? null : n);
    }

    /** 批6-#2：收割判别——在飞>0 视为活 run */
    public boolean isInFlight(String runId) {
        java.util.concurrent.atomic.AtomicInteger n = runId == null ? null : inFlights.get(runId);
        return n != null && n.get() > 0;
    }

    public void accumulate(String runId, long tokens, BigDecimal yuan) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        runs.computeIfAbsent(runId, k -> new long[2]);
        runs.computeIfPresent(runId, (k, acc) -> {
            acc[0] += tokens;
            acc[1] += yuan.movePointRight(6).longValue();
            return acc;
        });
    }

    /** 取走并清除（RUN_FINISHED 时调用）；无记录/空键返回 null */
    public Cost take(String runId) {
        if (runId == null || runId.isBlank()) {
            return null;
        }
        long[] acc = runs.remove(runId);
        if (acc == null || acc[0] <= 0) {
            return null;
        }
        return new Cost(acc[0], BigDecimal.valueOf(acc[1], 6));
    }

    /** 一回合成本快照（tokens + 元） */
    public record Cost(long tokens, BigDecimal yuan) {
    }

    /**
     * 批B B1（glm53 P1-1 回修口径）：记末次模型调用 tokens（覆写=last-wins；仅真实 usage 路径调用，估算不记）。
     * 分子=usage.totalTokens()=input+output——OpenAI 口径 prompt_tokens **已含** cached_tokens，
     * 再加 cached 会虚高近 2 倍（实测 prompt=9695/cached=9472 时错值 19567 vs 真值 10095）。
     */
    public void recordLastUsage(String runId, long totalTokensInOut) {
        if (runId == null || runId.isBlank()) {
            return;
        }
        lastUsages.put(runId, totalTokensInOut);
    }

    /** 批B B1：peek 末次调用 tokens（不清除）；无记录返回 null——前端环空态 */
    public Long lastUsageOf(String runId) {
        if (runId == null || runId.isBlank()) {
            return null;
        }
        return lastUsages.get(runId);
    }

    /**
     * 批B B1 修（glm53 P1-2）：有界等待「值变化」而非「值出现」。E2E 实证计费 record()
     * 在 TEXT_MESSAGE_END 后 ~1ms 才落 map——多子调用轮（ReAct）此刻 map 里躺的是倒数
     * 第二次调用的值，旧版「非 null 即快路径」会把上一轮的 context 当本轮值。before=事件
     * 线程进入时的快照，异步线程等到 get≠before 为止；超时返回当前值（best-effort，与旧
     * 快路径同级降级）。before=null（单调用轮）退化为「等值出现」。
     * ⚠️ 已知取舍（glm53 P2-2 登记）：落库池 CallerRunsPolicy 饱和时本等待会内联到流线程
     * 上执行，最长卡 ~timeoutMs——256 队列打满才触发，与「丢终态」相比取短阻塞。
     */
    public Long awaitLastUsageChanged(String runId, Long before, long timeoutMs) {
        if (runId == null || runId.isBlank()) {
            return null;
        }
        Long v = lastUsages.get(runId);
        long deadline = System.currentTimeMillis() + timeoutMs;
        // null==null（值未出现）也算「未变化」继续等——Objects.equals 统一两态
        while (java.util.Objects.equals(v, before) && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(100_000_000L);
            v = lastUsages.get(runId);
        }
        return v;
    }

    /**
     * 批B B1 修（glm53 P2-1）：条件清除——仅当当前值仍是本任务读到的 expected 才删。
     * 多条文本消息同 run 并行落库时，无条件 remove 会抹掉下一次调用刚写入的新值。
     * Collections.synchronizedMap 未覆盖 remove(key,value) default 方法，须手动同步。
     */
    public void clearLastUsageIf(String runId, Long expected) {
        if (runId == null || runId.isBlank() || expected == null) {
            return;
        }
        synchronized (lastUsages) {
            if (expected.equals(lastUsages.get(runId))) {
                lastUsages.remove(runId);
            }
        }
    }

    /** 批B B1：终态兜底清除（按 key；落库任务正常走 clearLastUsageIf 条件清除） */
    public void clearLastUsage(String runId) {
        if (runId != null && !runId.isBlank()) {
            lastUsages.remove(runId);
        }
    }
}
