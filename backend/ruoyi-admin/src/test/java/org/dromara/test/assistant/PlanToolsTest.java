package org.dromara.test.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.assistant.core.AssistantPlanSignal;
import org.dromara.assistant.tools.PlanTools;
import org.dromara.assistant.tools.ToolIdentity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 二站③ AU-51 计划卡单测：submit_plan 工具落信号 + 信号仓取走语义 +
 * enricher 侧帧形状（steps → [{label,state:pending}]，前端 PlanCard 契约）。
 * 纯静态构造，不依赖 Spring。
 */
@Tag("dev")
class PlanToolsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static PlanTools tool(String threadId, AssistantPlanSignal signal) {
        return new PlanTools(new ToolIdentity(1L, "000000", ToolIdentity.LEVEL_MEMBER, null), signal, threadId);
    }

    @Test
    void submitStoresSignalAndReturnsGuidance() throws Exception {
        AssistantPlanSignal signal = new AssistantPlanSignal();
        String out = tool("thread-1", signal).submitPlan("催办三步走", "查未交名单;起草催办留言;发送催办");
        JsonNode j = MAPPER.readTree(out);
        assertEquals("OK", j.path("status").asText());
        assertEquals(3, j.path("stepCount").asInt());
        assertTrue(j.path("message").asText().contains("收尾"), "返回话术要指示模型收尾等待用户选择");

        AssistantPlanSignal.PlanPayload p = signal.take("thread-1");
        assertNotNull(p);
        assertEquals("催办三步走", p.title());
        assertEquals(3, p.steps().size());
        assertEquals("查未交名单", p.steps().get(0));
        assertNull(signal.take("thread-1"), "取走即清除（幂等）");
    }

    @Test
    void emptyStepsRejectedWithoutSignal() throws Exception {
        AssistantPlanSignal signal = new AssistantPlanSignal();
        String out = tool("thread-2", signal).submitPlan(null, ";; ;\n");
        JsonNode j = MAPPER.readTree(out);
        assertEquals("ERROR", j.path("status").asText());
        assertTrue(j.path("message").asText().contains("步骤清单是空的"));
        assertNull(signal.take("thread-2"), "空步骤不落信号");
    }

    @Test
    void stepsCappedAndTrimmed() {
        AssistantPlanSignal signal = new AssistantPlanSignal();
        StringBuilder many = new StringBuilder();
        for (int i = 1; i <= 12; i++) {
            many.append("第").append(i).append("步很长的步骤名称超过二十四字的部分应该被裁掉哦不要问我为什么;");
        }
        tool("thread-3", signal).submitPlan(" ", many.toString());
        AssistantPlanSignal.PlanPayload p = signal.take("thread-3");
        assertEquals(8, p.steps().size(), "截 8 步（前端 PlanCard 同口径）");
        assertEquals(24, p.steps().get(0).length(), "单步超 24 字裁断");
        assertNull(p.title(), "空白 title 归一 null（前端显示默认标题）");
    }

    @Test
    void signalStoreRejectsBlankThreadAndDrainsIsolated() {
        AssistantPlanSignal signal = new AssistantPlanSignal();
        signal.submit(null, "t", java.util.List.of("a"));
        signal.submit("  ", "t", java.util.List.of("a"));
        assertNull(signal.take(null));
        assertNull(signal.take("thread-x"), "无记录返回 null");
    }

    @Test
    void enricherPlanFrameShapeMatchesFrontendContract() {
        // 前端 onCustom('plan') 契约：steps=[{label:string, state}]，title 可缺省
        AssistantPlanSignal signal = new AssistantPlanSignal();
        signal.submit("thread-4", "计划", java.util.List.of("a", "b"));
        AssistantPlanSignal.PlanPayload p = signal.take("thread-4");
        java.util.List<java.util.Map<String, String>> steps = new java.util.ArrayList<>();
        for (String s : p.steps()) {
            steps.add(java.util.Map.of("label", s, "state", "pending"));
        }
        assertEquals(2, steps.size());
        assertEquals("pending", steps.get(0).get("state"));
        assertEquals("a", steps.get(0).get("label"));
    }
}
