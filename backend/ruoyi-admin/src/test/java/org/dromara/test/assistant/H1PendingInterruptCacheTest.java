package org.dromara.test.assistant;

import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import org.dromara.assistant.persist.AssistantPendingInterruptCache;
import org.dromara.assistant.persist.AssistantPendingInterruptCache.PendingInterrupt;
import org.dromara.assistant.persist.AssistantRunRecorder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 二站 T1 悬挂 interrupt 缓存单测（纯逻辑，不起 Spring；recorder 依赖注入 null
 * 仅测缓存簿记路径，异步落库支路自吞异常）。
 *
 * <p>判据（计划 §3.1 + T0 探针口径 + 三审回修）：interrupt 轮 RUN_FINISHED 全量写入
 * （一 run 可挂 2 个）；干净终态清除；RUN_ERROR 一律保留缓存（框架只在无错误的
 * RunFinished 移除悬挂，javap 亲证 sawError 跳过 remove——任何错误清缓存=单边失配
 * 永久毒化）；超龄报读只查不删（P0-2：悬挂条目与框架同寿，staleThreadIds 仅配对
 * stepBuffers 回收）。</p>
 */
@Tag("p0probe")
class H1PendingInterruptCacheTest {

    private static final String TH = "th-h1";
    private static final String RUN = "run-h1";

    private final AssistantPendingInterruptCache cache = new AssistantPendingInterruptCache();
    /** conversationService/dailyQuota/piiFilter/followupService/costLedger 传 null：本测只触发缓存簿记，异步落库支路 try-catch 自吞 */
    private final AssistantRunRecorder recorder = new AssistantRunRecorder(null, null, cache, null, null, new org.dromara.assistant.billing.AssistantCostLedger());

    private AguiStreamContext ctx() {
        return new AguiStreamContext(TH, RUN,
            io.agentscope.core.agui.adapter.AguiAdapterConfig.builder().build());
    }

    @Test
    void interruptFinishStoresAllPendingAsList() {
        // Interrupt(id, reason, message, toolCallId, responseSchema, expiresAt, metadata)
        AguiEvent.Interrupt i0 = new AguiEvent.Interrupt(
            TH + ":call_00_aaa", "tool_call_interrupt", "确认执行？", "call_00_aaa", null, null, null);
        AguiEvent.Interrupt i1 = new AguiEvent.Interrupt(
            TH + ":call_01_bbb", "tool_call_interrupt", "确认执行？", "call_01_bbb", null, null, null);
        AguiEvent.RunFinished fin = new AguiEvent.RunFinished(TH, RUN, null,
            new AguiEvent.RunFinishedInterruptOutcome(List.of(i0, i1)));
        recorder.onEvents(ctx(), List.of(fin));

        List<PendingInterrupt> pending = cache.peek(TH);
        assertNotNull(pending, "interrupt 轮终态后缓存必须可窥视");
        assertEquals(2, pending.size(), "一 run 挂 2 个 interrupt 必须全量存（T0 实测）");
        assertEquals(TH + ":call_00_aaa", pending.get(0).id());
        assertEquals("call_01_bbb", pending.get(1).toolCallId());
    }

    @Test
    void resumeFinishClearsCache() {
        cache.store(TH, List.of(new PendingInterrupt(TH + ":call_00_aaa", "call_00_aaa",
            "reason", null, System.currentTimeMillis())));
        // resume 轮（用户点了确认/取消，或入口注入的自动解锁）正常收尾=非 interrupt outcome
        AguiEvent.RunFinished fin = new AguiEvent.RunFinished(TH, RUN, null,
            new AguiEvent.RunFinishedSuccessOutcome());
        recorder.onEvents(ctx(), List.of(fin));
        assertNull(cache.peek(TH), "resume 轮终态必须清悬挂缓存（清除①）");
    }

    @Test
    void runErrorKeepsCacheRegardlessOfCode() {
        cache.store(TH, List.of(new PendingInterrupt(TH + ":call_00_aaa", "call_00_aaa",
            "reason", null, System.currentTimeMillis())));

        // 契约错误=validate 拒绝（悬挂未被消费）→ 缓存保留供下次注入
        recorder.onEvents(ctx(), List.of(new AguiEvent.RunError(
            TH, RUN, "resume must address all of them", "AGUI_INTERRUPT_CONTRACT_ERROR")));
        assertNotNull(cache.peek(TH), "契约错误不得清缓存（钥匙还在，下次注入用）");

        // 三审 P0-1 回修：provider 超时等非契约错误同样不得清——框架 trackPendingInterrupts
        // 对任何错误流都保留悬挂（RUN_ERROR 事件从不消费它），清缓存=单边失配死锁
        recorder.onEvents(ctx(), List.of(new AguiEvent.RunError(
            TH, RUN, "model timeout", "PROVIDER_ERROR")));
        assertNotNull(cache.peek(TH), "非契约错误也必须保留缓存（框架悬挂仍在，镜像纪律）");
    }

    @Test
    void staleThreadIdsReportsWithoutRemoving() {
        long fresh = System.currentTimeMillis();
        cache.store("th-fresh", List.of(new PendingInterrupt("id1", "tc1", "r", null, fresh)));
        cache.store("th-aged", List.of(new PendingInterrupt("id2", "tc2", "r", null,
            fresh - 61L * 60 * 1000)));

        // 三审 P0-2 回修：报读只查不删（悬挂条目与框架同寿），幂等（同周期只报一次）
        List<String> stale = cache.staleThreadIds(60L * 60 * 1000);
        assertEquals(List.of("th-aged"), stale, "只报超龄键");
        assertNotNull(cache.peek("th-aged"), "报读不得删悬挂条目（P0-2：删了就毒化）");
        assertTrue(cache.staleThreadIds(60L * 60 * 1000).isEmpty(), "幂等：同周期不重复报");
        assertNotNull(cache.peek("th-fresh"), "新鲜键必须存活");
        assertEquals(2, cache.size());
    }

    @Test
    void reStoreReplacesAndClearRemoves() {
        cache.store(TH, List.of(new PendingInterrupt("id-a", "tc-a", "r", null,
            System.currentTimeMillis())));
        cache.store(TH, List.of(new PendingInterrupt("id-b", "tc-b", "r", null,
            System.currentTimeMillis())));
        assertEquals(1, cache.peek(TH).size(), "同 thread 再挂新 interrupt=整表替换");
        assertEquals("id-b", cache.peek(TH).get(0).id());

        cache.clear(TH);
        assertTrue(cache.isEmpty());
    }
}
