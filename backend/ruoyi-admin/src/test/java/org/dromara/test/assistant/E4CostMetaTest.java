package org.dromara.test.assistant;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import org.dromara.assistant.billing.AssistantCostLedger;
import org.dromara.assistant.core.AssistantEventEnricher;
import org.dromara.assistant.core.AssistantReasoningGate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批E E4 单测：cost_meta CUSTOM 帧注入（RUN_FINISHED 前插队首）+ 累加器语义。
 * 纯逻辑直构（不依赖 Spring 上下文），与 A3ReasoningFilterTest 同款注入法。
 */
@Tag("dev")
class E4CostMetaTest {

    @Test
    void ledgerAccumulateAndTake() {
        AssistantCostLedger ledger = new AssistantCostLedger();
        ledger.accumulate("run-1", 100, new BigDecimal("0.001234"));
        ledger.accumulate("run-1", 200, new BigDecimal("0.002"));
        AssistantCostLedger.Cost cost = ledger.take("run-1");
        assertNotNull(cost);
        assertEquals(300, cost.tokens());
        assertEquals(0, cost.yuan().compareTo(new BigDecimal("0.003234")), "微元累加无损: " + cost.yuan());
        assertNull(ledger.take("run-1"), "取走即清除（同 run 二次取为空）");
        assertNull(ledger.take("run-never"), "无记录返回 null");
        ledger.accumulate(null, 10, BigDecimal.ONE);
        ledger.accumulate(" ", 10, BigDecimal.ONE);
        assertTrue(ledger.take(null) == null, "空 runId 跳过");
    }

    @Test
    @SuppressWarnings("unchecked")
    void costMetaInjectedBeforeRunFinished() {
        AssistantCostLedger ledger = new AssistantCostLedger();
        ledger.accumulate("run-9", 456, new BigDecimal("0.0042"));
        // 闸开（gate isEnabled=true 才不进过滤分支）——直构 gate 不行（依赖 Spring 配置），
        // 用 null gate + isEnabled 语义不可控 → 改为验证 enrich 在 recorder/gate 均 null 时仍注入
        AssistantEventEnricher enricher = new AssistantEventEnricher(null, null, ledger, null, null);
        List<AguiEvent> events = new ArrayList<>(List.of(
            new AguiEvent.TextMessageContent("t", "run-9", "m1", "你好"),
            new AguiEvent.RunFinished("t", "run-9")));
        List<AguiEvent> out = enricher.enrich(null, events, ctx("t", "run-9"));
        assertEquals(3, out.size(), "RUN_FINISHED 批次插入 cost_meta 后=3 帧");
        assertEquals(AguiEventType.CUSTOM, out.get(0).getType(), "cost_meta 必须插队首（前端先收金额再收终态）");
        AguiEvent.Custom custom = (AguiEvent.Custom) out.get(0);
        assertEquals("cost_meta", custom.name());
        Map<String, Object> value = (Map<String, Object>) custom.value();
        assertEquals(456L, value.get("tokens"));
        assertEquals(0, new BigDecimal(String.valueOf(value.get("yuan"))).compareTo(new BigDecimal("0.0042")));
        // 无 RUN_FINISHED 的批次不注入
        List<AguiEvent> plain = List.of(new AguiEvent.TextMessageContent("t", "run-9", "m2", "继续"));
        assertEquals(1, enricher.enrich(null, plain, ctx("t", "run-9")).size());
        // 取走后再次 RUN_FINISHED 不再注入（防重复计费观感）
        List<AguiEvent> again = List.of(new AguiEvent.RunFinished("t", "run-9"));
        assertEquals(1, enricher.enrich(null, again, ctx("t", "run-9")).size());
    }

    /** AguiStreamContext 直构（config 传 null——enricher 只读 threadId/runId） */
    private io.agentscope.core.agui.adapter.strategy.AguiStreamContext ctx(String threadId, String runId) {
        return new io.agentscope.core.agui.adapter.strategy.AguiStreamContext(threadId, runId,
            io.agentscope.core.agui.adapter.AguiAdapterConfig.builder().build());
    }
}
