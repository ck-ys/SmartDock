package org.dromara.test.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.ToolSchema;
import org.dromara.assistant.core.AssistantTodoSignal;
import org.dromara.assistant.mapper.AssistantConversationMapper;
import org.dromara.assistant.service.AssistantTitleService;
import org.dromara.assistant.tools.ToolIdentity;
import org.dromara.assistant.tools.TodoTools;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ZCode 对齐批A 单测（2026-10-10）：todo_write 校验矩阵 + 标题侧车清洗/CAS。
 * 纯 Mockito 直构（无 Spring/无 PG/零模型成本）。
 */
@Tag("dev")
class TodoAndTitleUnitTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static ToolIdentity identity() {
        return new ToolIdentity(1L, "000000", ToolIdentity.LEVEL_MEMBER, null);
    }

    // ========== todo_write 校验矩阵 ==========

    private JsonNode runTodo(TodoTools tools, String todos) throws Exception {
        return M.readTree(tools.todoWrite(todos));
    }

    @Test
    void todoWriteNormalListOkAndSignals() throws Exception {
        AssistantTodoSignal signal = new AssistantTodoSignal();
        org.dromara.assistant.service.AssistantConversationService conv = mock();
        TodoTools tools = new TodoTools(identity(), signal, conv, "th-1");
        JsonNode out = runTodo(tools, "[{\"content\":\"查未交名单\",\"status\":\"in_progress\",\"priority\":\"high\"},"
            + "{\"content\":\"发送催办\",\"status\":\"pending\",\"priority\":\"medium\"}]");
        assertEquals("OK", out.path("status").asText());
        assertEquals(2, out.path("count").asInt());
        // 信号仓取到最新一版（整表替换语义）
        List<AssistantTodoSignal.TodoItem> items = signal.take("th-1");
        assertNotNull(items);
        assertEquals(2, items.size());
        assertEquals("查未交名单", items.get(0).content());
        assertEquals("in_progress", items.get(0).status());
        // 持久化通道同批调用
        verify(conv).saveTodo(eq("000000"), eq("th-1"), any());
    }

    @Test
    void todoWriteDefaultsAndTruncation() throws Exception {
        AssistantTodoSignal signal = new AssistantTodoSignal();
        org.dromara.assistant.service.AssistantConversationService conv = mock();
        TodoTools tools = new TodoTools(identity(), signal, conv, "th-1");
        // status/priority 缺省 → pending/medium；content 超 40 字截断
        StringBuilder long40 = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            long40.append('字');
        }
        JsonNode out = runTodo(tools, "[{\"content\":\"" + long40 + "\"}]");
        assertEquals("OK", out.path("status").asText());
        List<AssistantTodoSignal.TodoItem> items = signal.take("th-1");
        assertEquals(40, items.get(0).content().length(), "content 截到 40 字");
        assertEquals("pending", items.get(0).status());
        assertEquals("medium", items.get(0).priority());
        // >20 条截断并带 truncated 标记
        StringBuilder arr = new StringBuilder("[");
        for (int i = 0; i < 25; i++) {
            if (i > 0) {
                arr.append(',');
            }
            arr.append("{\"content\":\"步骤").append(i).append("\"}");
        }
        arr.append(']');
        JsonNode out2 = runTodo(tools, arr.toString());
        assertEquals("OK", out2.path("status").asText());
        assertTrue(out2.path("truncated").asBoolean());
        assertEquals(20, signal.take("th-1").size());
    }

    @Test
    void todoWriteRejectsBadPayloads() throws Exception {
        AssistantTodoSignal signal = new AssistantTodoSignal();
        org.dromara.assistant.service.AssistantConversationService conv = mock();
        TodoTools tools = new TodoTools(identity(), signal, conv, "th-1");
        // 非法 JSON / 非数组 / 空数组
        for (String bad : new String[] {"不是json", "{\"content\":\"x\"}", "[]", null}) {
            JsonNode out = runTodo(tools, bad);
            assertEquals("ERROR", out.path("status").asText(), "非法载荷须拒: " + bad);
        }
        // status 非法（指明第几条）
        JsonNode s1 = runTodo(tools, "[{\"content\":\"a\",\"status\":\"doing\"}]");
        assertEquals("ERROR", s1.path("status").asText());
        assertTrue(s1.path("message").asText().contains("1"), "提示指明第 1 条");
        // priority 非法
        JsonNode s2 = runTodo(tools, "[{\"content\":\"a\",\"status\":\"pending\",\"priority\":\"urgent\"}]");
        assertEquals("ERROR", s2.path("status").asText());
        // content 缺失
        JsonNode s3 = runTodo(tools, "[{\"status\":\"pending\"}]");
        assertEquals("ERROR", s3.path("status").asText());
        // 全拒路径不落信号不落库
        assertNull(signal.take("th-1"));
        verify(conv, never()).saveTodo(anyString(), anyString(), any());
    }

    // ========== 标题侧车清洗/CAS ==========

    @Test
    void titleShouldGenerateGuards() {
        assertFalse(AssistantTitleService.shouldGenerate(null));
        assertFalse(AssistantTitleService.shouldGenerate("  "));
        assertFalse(AssistantTitleService.shouldGenerate("你好"));
        assertFalse(AssistantTitleService.shouldGenerate("一二三四五六七八九"), "9 字=跳过");
        assertTrue(AssistantTitleService.shouldGenerate("一二三四五六七八九十"), "10 字=生成");
    }

    @Test
    void titleCleanVariants() {
        assertEquals("查未交名单", AssistantTitleService.cleanTitle("{\"title\":\"查未交名单\"}"));
        assertEquals("按部门汇总催办", AssistantTitleService.cleanTitle("```json\n{\"title\":\"按部门汇总催办\"}\n```"));
        assertEquals("催办名单", AssistantTitleService.cleanTitle("{\"title\":\"催办名单。\"}"));
        assertEquals("裸文本标题", AssistantTitleService.cleanTitle("裸文本标题"));
        assertEquals("多行取首行", AssistantTitleService.cleanTitle("多行取首行\n第二行不要"));
        assertEquals("剥思考块", AssistantTitleService.cleanTitle("<think>让我想想</think>{\"title\":\"剥思考块\"}"));
        assertEquals("去引号", AssistantTitleService.cleanTitle("{\"title\":\"\\\"去引号\\\"\"}"));
        assertEquals("去序号前缀", AssistantTitleService.cleanTitle("1. 去序号前缀"));
        assertEquals("标题冒号前缀", AssistantTitleService.cleanTitle("标题：标题冒号前缀"));
        assertNull(AssistantTitleService.cleanTitle(null));
        assertNull(AssistantTitleService.cleanTitle("   "));
        assertNull(AssistantTitleService.cleanTitle("<think>全是思考</think>"));
        assertNull(AssistantTitleService.cleanTitle("！！！？？？"));
        // >60 字截断
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 80; i++) {
            b.append('长');
        }
        assertEquals(60, AssistantTitleService.cleanTitle("{\"title\":\"" + b + "\"}").length());
    }

    /** 可控假模型：回放固定文本块 */
    private static Model modelOf(String... texts) {
        Model m = mock(Model.class);
        List<ChatResponse> rs = java.util.Arrays.stream(texts)
            .map(t -> new ChatResponse("r1",
                List.of(io.agentscope.core.message.TextBlock.builder().text(t).build()),
                null, java.util.Map.of(), null))
            .toList();
        when(m.stream(any(), any(), any(GenerateOptions.class))).thenReturn(Flux.fromIterable(rs));
        return m;
    }

    @Test
    void titleRunGenerationWritesViaCas() {
        AssistantConversationMapper mapper = mock(AssistantConversationMapper.class);
        when(mapper.updateTitleIfMatch(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        AssistantTitleService svc = new AssistantTitleService(null, null, null, null, null, null, mapper, null, null, null);
        svc.runGenerationForTest("000000", "th-9", "首问前三十个字的截断标题", "帮我把三个部门的未交名单汇总一下",
            modelOf("{\"title\":\"汇总未交名单\"}"), java.time.Duration.ofSeconds(5));
        verify(mapper).updateTitleIfMatch(eq("000000"), eq("th-9"),
            eq("首问前三十个字的截断标题"), eq("汇总未交名单"));
    }

    @Test
    void titleRunGenerationCasMissKeepsSilent() {
        AssistantConversationMapper mapper = mock(AssistantConversationMapper.class);
        when(mapper.updateTitleIfMatch(anyString(), anyString(), anyString(), anyString())).thenReturn(0);
        AssistantTitleService svc = new AssistantTitleService(null, null, null, null, null, null, mapper, null, null, null);
        svc.runGenerationForTest("000000", "th-9", "旧标题", "用户已经改过名的会话首问",
            modelOf("{\"title\":\"新标题\"}"), java.time.Duration.ofSeconds(5));
        // CAS 未命中也不抛（保留现标题）
        verify(mapper).updateTitleIfMatch(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void titleRunGenerationTimeoutGivesUpQuietly() {
        AssistantConversationMapper mapper = mock(AssistantConversationMapper.class);
        Model neverModel = mock(Model.class);
        when(neverModel.stream(any(), any(), any(GenerateOptions.class)))
            .thenReturn(Flux.never());
        AssistantTitleService svc = new AssistantTitleService(null, null, null, null, null, null, mapper, null, null, null);
        svc.runGenerationForTest("000000", "th-9", "旧", "超时也不该抛异常的首问",
            neverModel, java.time.Duration.ofMillis(120));
        verify(mapper, never()).updateTitleIfMatch(anyString(), anyString(), anyString(), anyString());
    }

    /** dspro P2-7/glm53 P2-5 回修补测：enricher 的 todo CUSTOM 帧注入——帧名/payload 形状/
     * 批首插入/取走即清（此前零覆盖，前端契约只靠一次性 E2E） */
    @Test
    @SuppressWarnings("unchecked")
    void enricherInjectsTodoCustomFrameAtHead() {
        org.dromara.assistant.core.AssistantTodoSignal signal = new org.dromara.assistant.core.AssistantTodoSignal();
        signal.submit("th-1", List.of(
            new org.dromara.assistant.core.AssistantTodoSignal.TodoItem("查未交名单", "completed", "high"),
            new org.dromara.assistant.core.AssistantTodoSignal.TodoItem("发催办", "in_progress", "medium")));
        org.dromara.assistant.core.AssistantEventEnricher enricher =
            new org.dromara.assistant.core.AssistantEventEnricher(null, null, null, null, signal, null, null, null);
        List<AguiEvent> events = List.of(new AguiEvent.TextMessageContent("th-1", "run-1", "m1", "推进中"));
        List<AguiEvent> out = enricher.enrich(null, events, ctx("th-1", "run-1"));
        assertEquals(2, out.size(), "原 1 帧+todo 帧=2");
        assertEquals(AguiEventType.CUSTOM, out.get(0).getType(), "todo 帧插批首（前端先更新清单再看后续帧）");
        AguiEvent.Custom custom = (AguiEvent.Custom) out.get(0);
        assertEquals("todo", custom.name());
        java.util.Map<String, Object> value = (java.util.Map<String, Object>) custom.value();
        java.util.List<java.util.Map<String, String>> items =
            (java.util.List<java.util.Map<String, String>>) value.get("items");
        assertEquals(2, items.size());
        assertEquals("查未交名单", items.get(0).get("content"));
        assertEquals("completed", items.get(0).get("status"));
        assertEquals("high", items.get(0).get("priority"));
        // 取走即清：下一批不再注入
        List<AguiEvent> next = List.of(new AguiEvent.TextMessageContent("th-1", "run-1", "m2", "续"));
        assertEquals(1, enricher.enrich(null, next, ctx("th-1", "run-1")).size(), "信号已 drain，不重复发帧");
    }

    /** AguiStreamContext 直构（与 E4CostMetaTest 同款——enricher 只读 threadId/runId） */
    private io.agentscope.core.agui.adapter.strategy.AguiStreamContext ctx(String threadId, String runId) {
        return new io.agentscope.core.agui.adapter.strategy.AguiStreamContext(threadId, runId,
            io.agentscope.core.agui.adapter.AguiAdapterConfig.builder().build());
    }
}
