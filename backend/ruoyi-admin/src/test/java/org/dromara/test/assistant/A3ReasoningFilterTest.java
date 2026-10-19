package org.dromara.test.assistant;

import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import org.dromara.assistant.core.AssistantEventEnricher;
import org.dromara.assistant.core.AssistantReasoningGate;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3 思考内容三层过滤 enricher 单测（纯逻辑，不起 Spring）。
 *
 * <p>用户拍板口径（2026-10-03）：思考开关「关」= 前端只见"思考中"不见内容；
 * 「开」= 显示对应内容。落地：开关关时丢弃 REASONING_MESSAGE_CONTENT（内容洪流帧，
 * 实测思考期 13-20s 可产 3000-5000 帧），保留 REASONING_START/END 与
 * REASONING_MESSAGE_START/END 结构标记（前端据此渲染"思考中"占位与折叠块）；
 * 开关开时全放行。TEXT_* 一律不动。</p>
 */
@Tag("p0probe")
class A3ReasoningFilterTest {

    private static final String TH = "th-a3";
    private static final String RUN = "run-a3";

    private final AssistantReasoningGate gate = new AssistantReasoningGate();
    private final AssistantEventEnricher enricher = new AssistantEventEnricher(gate, null, null, null, null);

    private List<AguiEventType> typesOf(List<AguiEvent> events) {
        return events.stream().map(AguiEvent::getType).toList();
    }

    @Test
    void gateOffDropsContentButKeepsStructureMarkers() {
        gate.setEnabled(false);
        List<AguiEvent> out = enricher.enrich(null, List.of(
            new AguiEvent.ReasoningStart(TH, RUN, "rs-1", "raw"),
            new AguiEvent.ReasoningMessageStart(TH, RUN, "msg-1", "deepseek"),
            new AguiEvent.ReasoningMessageContent(TH, RUN, "msg-1", "第一段思考"),
            new AguiEvent.ReasoningMessageContent(TH, RUN, "msg-1", "第二段思考"),
            new AguiEvent.ReasoningMessageEnd(TH, RUN, "msg-1"),
            new AguiEvent.ReasoningEnd(TH, RUN, "done")
        ), null);
        List<AguiEventType> types = typesOf(out);
        assertFalse(types.contains(AguiEventType.REASONING_MESSAGE_CONTENT),
            "开关关：内容帧必须全部丢弃（洪流源头）");
        assertTrue(types.contains(AguiEventType.REASONING_START)
            && types.contains(AguiEventType.REASONING_MESSAGE_START)
            && types.contains(AguiEventType.REASONING_MESSAGE_END)
            && types.contains(AguiEventType.REASONING_END),
            "开关关：结构标记必须保留（前端靠它显示思考中）");
        assertEquals(4, out.size());
    }

    @Test
    void gateOnPassesContentFrames() {
        gate.setEnabled(true);
        List<AguiEvent> out = enricher.enrich(null, List.of(
            new AguiEvent.ReasoningMessageStart(TH, RUN, "msg-1", "deepseek"),
            new AguiEvent.ReasoningMessageContent(TH, RUN, "msg-1", "完整思考内容"),
            new AguiEvent.ReasoningMessageEnd(TH, RUN, "msg-1")
        ), null);
        assertEquals(3, out.size(), "开关开：思考内容全放行");
        assertTrue(typesOf(out).contains(AguiEventType.REASONING_MESSAGE_CONTENT));
    }

    @Test
    void textEventsNeverTouched() {
        gate.setEnabled(false);
        List<AguiEvent> out = enricher.enrich(null, List.of(
            new AguiEvent.TextMessageStart(TH, RUN, "msg-2", "assistant"),
            new AguiEvent.TextMessageContent(TH, RUN, "msg-2", "正文回答"),
            new AguiEvent.TextMessageEnd(TH, RUN, "msg-2")
        ), null);
        assertEquals(3, out.size(), "正文回答不受思考开关影响");
    }

    @Test
    void chunkFramesAlsoDroppedWhenOff() {
        gate.setEnabled(false);
        List<AguiEvent> out = enricher.enrich(null, List.of(
            new AguiEvent.ReasoningMessageChunk(TH, RUN, "msg-1", "chunked-thought")
        ), null);
        assertTrue(out.isEmpty(), "CHUNK 形态的内容帧同样丢弃");
    }
}
