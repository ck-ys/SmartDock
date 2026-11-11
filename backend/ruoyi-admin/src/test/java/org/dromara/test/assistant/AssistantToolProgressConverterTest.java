package org.dromara.test.assistant;

import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.ToolProgressEvent;
import io.agentscope.core.message.TextBlock;
import java.util.List;
import java.util.Map;
import org.dromara.assistant.core.AssistantToolProgressConverter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ckys3 解锁板 题1 案1（2026-11-12）：进度转换器纯函数契约。
 *
 * <p>守三件事：①框架 ToolProgressEvent（文本块）→ CUSTOM tool_progress 帧，带
 * toolCallId/toolName/text 三字段（前端按 toolCallId 对行）；②非文本/空文本块静默
 * 跳过（进度行只承载一行小字）；③不占用 TOOL_CALL_CHUNK（那是参数增量通道）。</p>
 *
 * <p>@Tag("local") 纯单测：AguiStreamContext 是 POJO，drainEvents 即出网帧，不起 Spring。</p>
 */
@Tag("local")
class AssistantToolProgressConverterTest {

    private static ToolProgressEvent textEvent(String callId, String name, String text) {
        return new ToolProgressEvent("ev-1", "2026-11-12T00:00:00Z",
            "reply-1", callId, name, TextBlock.builder().text(text).build(), null);
    }

    @Test
    void textProgressBecomesCustomToolProgressFrame() {
        AguiStreamContext ctx = new AguiStreamContext("t-1", "r-1", AguiAdapterConfig.builder().build());
        new AssistantToolProgressConverter().convert(
            textEvent("call-9", "analytics_trend", "执行中·已用时 2 秒"), ctx);

        List<AguiEvent> out = ctx.drainEvents();
        assertEquals(1, out.size(), "一帧");
        AguiEvent.Custom custom = (AguiEvent.Custom) out.get(0);
        assertEquals("tool_progress", custom.name());
        assertEquals("t-1", custom.threadId());
        assertEquals("r-1", custom.runId());
        @SuppressWarnings("unchecked")
        Map<String, Object> value = (Map<String, Object>) custom.value();
        assertEquals("call-9", value.get("toolCallId"));
        assertEquals("analytics_trend", value.get("toolName"));
        assertEquals("执行中·已用时 2 秒", value.get("text"));
    }

    @Test
    void nullContentEmitsNothing() {
        AguiStreamContext ctx = new AguiStreamContext("t-1", "r-1", AguiAdapterConfig.builder().build());
        new AssistantToolProgressConverter().convert(
            new ToolProgressEvent("ev-1", "2026-11-12T00:00:00Z",
                "reply-1", "call-9", "analytics_trend", null, null), ctx);
        assertTrue(ctx.drainEvents().isEmpty(), "无内容=无帧");
    }

    @Test
    void nullEventIsSafeAndEventTypesDeclared() {
        AguiStreamContext ctx = new AguiStreamContext("t-1", "r-1", AguiAdapterConfig.builder().build());
        // 三审席1 P2：用例名纠偏——这里证的是 null 安全 + eventTypes 声明；
        // 真正的「未知类型不进本转换器」由注册侧 eventTypes 分发保证（框架契约）
        assertEquals(java.util.Set.of(ToolProgressEvent.class),
            new AssistantToolProgressConverter().eventTypes());
        new AssistantToolProgressConverter().convert(null, ctx);
        assertTrue(ctx.drainEvents().isEmpty(), "null 事件=无帧");
    }
}
