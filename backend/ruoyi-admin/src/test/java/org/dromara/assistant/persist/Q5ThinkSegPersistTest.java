package org.dromara.assistant.persist;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Q5-A 思考段落库单测（2026-10-10 拍板 A；纯逻辑不起 Spring——同包直取 snapshotSteps，
 * 依赖注入全 null，异步落库支路自吞）。
 *
 * <p>判据：①START/CONTENT/END 三帧聚成一段（text/durMs/seq 齐全）；
 * ②与工具步交错时 seq 共用同一计数器（时间线可还原真实时序）；
 * ③闸关形态（只有 START/END 无内容）=空文本段有 durMs；
 * ④无思考段时保持纯数组旧形态（零迁移兼容）；⑤终态清缓冲、INTERRUPTED 保留。</p>
 */
@Tag("dev")
class Q5ThinkSegPersistTest {

    private static final String TH = "th-q5";
    private static final String RUN = "run-q5";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AssistantRunRecorder recorder = new AssistantRunRecorder(null, null, null, null, null, new org.dromara.assistant.billing.AssistantCostLedger());

    private AguiStreamContext ctx() {
        return new AguiStreamContext(TH, RUN, AguiAdapterConfig.builder().build());
    }

    @Test
    void thinkFramesAggregateIntoSegWithTextDurAndSeq() throws Exception {
        recorder.onEvents(ctx(), List.of(
            new AguiEvent.ReasoningStart(TH, RUN, "rm-1", null, 1000L, null),
            new AguiEvent.ReasoningMessageContent(TH, RUN, "rm-1", "先查"),
            new AguiEvent.ReasoningMessageContent(TH, RUN, "rm-1", "名单"),
            new AguiEvent.ReasoningEnd(TH, RUN, "rm-1", 4000L, null)));
        JsonNode root = MAPPER.readTree(recorder.snapshotSteps(TH));
        JsonNode segs = root.get("thinkSegs");
        assertEquals(1, segs.size(), "三帧聚成一段");
        JsonNode seg = segs.get(0);
        assertEquals("先查名单", seg.get("text").asText());
        assertEquals(3000L, seg.get("durMs").asLong(), "durMs=END-START");
        assertEquals(1000L, seg.get("startMs").asLong());
        assertEquals(0, seg.get("seq").asInt(), "首段 seq=0");
        assertTrue(root.get("steps").isArray() && root.get("steps").isEmpty(), "无工具步=空 steps 数组");
    }

    @Test
    void messageStartEndWireShapeAlsoAggregates() throws Exception {
        // E2E 实证网关真实帧族=REASONING_MESSAGE_START/CONTENT/END（前端 aguiClient 同款）；
        // 首版只收 REASONING_START/END → 段永不开、CONTENT 全丢、落库恒无 thinkSegs
        recorder.onEvents(ctx(), List.of(
            new AguiEvent.ReasoningMessageStart(TH, RUN, "wm-1", "assistant", 1000L, null),
            new AguiEvent.ReasoningMessageContent(TH, RUN, "wm-1", "真实帧"),
            new AguiEvent.ReasoningMessageEnd(TH, RUN, "wm-1", 2500L, null)));
        JsonNode segs = MAPPER.readTree(recorder.snapshotSteps(TH)).get("thinkSegs");
        assertEquals(1, segs.size(), "MESSAGE_START 族必须同样开段");
        assertEquals("真实帧", segs.get(0).get("text").asText());
        assertEquals(1500L, segs.get(0).get("durMs").asLong());
    }

    @Test
    void seqInterleavesWithToolStepsOnSharedCounter() throws Exception {
        recorder.onEvents(ctx(), List.of(
            new AguiEvent.ReasoningStart(TH, RUN, "rm-1", null, 1000L, null),
            new AguiEvent.ReasoningEnd(TH, RUN, "rm-1", 2000L, null),
            new AguiEvent.ToolCallStart(TH, RUN, "call-1", "list_my_tasks", null, null),
            new AguiEvent.ReasoningStart(TH, RUN, "rm-2", null, 3000L, null),
            new AguiEvent.ReasoningEnd(TH, RUN, "rm-2", 3500L, null)));
        JsonNode root = MAPPER.readTree(recorder.snapshotSteps(TH));
        assertEquals(0, root.get("thinkSegs").get(0).get("seq").asInt());
        assertEquals(1, root.get("steps").get(0).get("seq").asInt(), "工具步与思考段共用计数器");
        assertEquals(2, root.get("thinkSegs").get(1).get("seq").asInt());
    }

    @Test
    void gateClosedShapeIsTextlessSegWithDur() throws Exception {
        // 闸关租户：内容帧在 enricher 层被丢，只有结构标记到达 recorder
        recorder.onEvents(ctx(), List.of(
            new AguiEvent.ReasoningStart(TH, RUN, "rm-1", null, 1000L, null),
            new AguiEvent.ReasoningEnd(TH, RUN, "rm-1", 93000L, null)));
        JsonNode seg = MAPPER.readTree(recorder.snapshotSteps(TH)).get("thinkSegs").get(0);
        assertEquals("", seg.get("text").asText(), "闸关=无正文");
        assertEquals(92000L, seg.get("durMs").asLong(), "耗时常在（回放可显示 已思考 92 秒）");
    }

    @Test
    void noThinkSegsKeepsLegacyArrayShape() throws Exception {
        recorder.onEvents(ctx(), List.of(
            new AguiEvent.ToolCallStart(TH, RUN, "call-1", "list_my_tasks", null, null)));
        String json = recorder.snapshotSteps(TH);
        JsonNode root = MAPPER.readTree(json);
        assertTrue(root.isArray(), "无思考段=纯数组旧形态（批J-4 行零迁移）");
        assertEquals("list_my_tasks", root.get(0).get("name").asText());
    }

    @Test
    void terminalClearsBuffersInterruptedKeeps() {
        // keep 路径须带真悬挂 interrupt（三席审查 P2-4 后空表=不保留）：真缓存实例防 store NPE
        AssistantRunRecorder withCache = new AssistantRunRecorder(null, null, new AssistantPendingInterruptCache(), null, null, new org.dromara.assistant.billing.AssistantCostLedger());
        withCache.onEvents(ctx(), List.of(
            new AguiEvent.ReasoningStart(TH, RUN, "rm-1", null, 1000L, null),
            new AguiEvent.ReasoningEnd(TH, RUN, "rm-1", 2000L, null)));
        // INTERRUPTED 终态（挂起真 interrupt）：保留（resume 轮续接）
        withCache.onEvents(ctx(), List.of(new AguiEvent.RunFinished(TH, RUN, null,
            new AguiEvent.RunFinishedInterruptOutcome(List.of(
                new AguiEvent.Interrupt("int-1", "hitl", "确认?", "call-9", null, null, null))))));
        assertTrue(withCache.snapshotSteps(TH) != null, "INTERRUPTED(有真悬挂) 保留思考缓冲");
        // 干净终态：清除
        withCache.onEvents(ctx(), List.of(new AguiEvent.RunFinished(TH, RUN, null,
            new AguiEvent.RunFinishedSuccessOutcome())));
        assertNull(withCache.snapshotSteps(TH), "COMPLETED 清思考缓冲");
    }

    /** 三席审查 P2-4：interrupts 空表 → 悬挂缓存不建条目（RetentionJob 看不见该 thread），
     *  缓冲须按终态直接清——否则永不回收且污染同 thread 下一轮快照（幽灵步骤串台） */
    @Test
    void interruptedWithNoRegisteredInterruptEvictsBuffers() throws Exception {
        recorder.onEvents(ctx(), List.of(
            new AguiEvent.ReasoningMessageStart(TH, RUN, "wm-1", "assistant", 1000L, null),
            new AguiEvent.ReasoningMessageEnd(TH, RUN, "wm-1", 2000L, null),
            new AguiEvent.ToolCallStart(TH, RUN, "call-1", "list_my_tasks", null, null),
            new AguiEvent.RunFinished(TH, RUN, null, new AguiEvent.RunFinishedInterruptOutcome(List.of()))));
        assertNull(recorder.snapshotSteps(TH), "空 interrupts=不保留");
        // 同 thread 下一轮快照只见新步骤（旧幽灵步骤已被清）
        recorder.onEvents(ctx(), List.of(
            new AguiEvent.ToolCallStart(TH, RUN, "call-2", "query_goal", null, null)));
        JsonNode root = MAPPER.readTree(recorder.snapshotSteps(TH));
        assertTrue(root.isArray(), "只剩步骤=纯数组形态");
        assertEquals(1, root.size());
        assertEquals("query_goal", root.get(0).get("name").asText());
    }

    /** 三席审查 P2-7：同 messageId 重复 START（重连/重放）不重开段不烧 seq */
    @Test
    void duplicateStartDoesNotReopenSegOrBurnSeq() throws Exception {
        recorder.onEvents(ctx(), List.of(
            new AguiEvent.ReasoningMessageStart(TH, RUN, "wm-1", "assistant", 1000L, null),
            new AguiEvent.ReasoningMessageStart(TH, RUN, "wm-1", "assistant", 1200L, null),
            new AguiEvent.ReasoningMessageEnd(TH, RUN, "wm-1", 2000L, null),
            new AguiEvent.ToolCallStart(TH, RUN, "call-1", "list_my_tasks", null, null)));
        JsonNode root = MAPPER.readTree(recorder.snapshotSteps(TH));
        assertEquals(1, root.get("thinkSegs").size(), "同 messageId 重复 START 不重开段");
        assertEquals(0, root.get("thinkSegs").get(0).get("seq").asInt(), "seq 不被重复 START 烧号");
        assertEquals(1, root.get("steps").get(0).get("seq").asInt(), "工具步 seq 紧随其后");
    }

    /** 三席审查（m3 P2-3）：思考正文落库前过 PII 掩码——thinkSegs.text 掩码、步骤机器字段不动 */
    @Test
    void thinkTextMaskedBeforePersistShape() throws Exception {
        AssistantRunRecorder masked = new AssistantRunRecorder(null, null, null,
            new org.dromara.assistant.security.AssistantPiiFilter(null) {
                @Override
                public String mask(String text, String tenantId) {
                    return "[已脱敏]";
                }
            }, null, new org.dromara.assistant.billing.AssistantCostLedger());
        masked.onEvents(ctx(), List.of(
            new AguiEvent.ReasoningMessageStart(TH, RUN, "wm-1", "assistant", 1000L, null),
            new AguiEvent.ReasoningMessageContent(TH, RUN, "wm-1", "联系人 13800000000 尽快"),
            new AguiEvent.ReasoningMessageEnd(TH, RUN, "wm-1", 2000L, null),
            new AguiEvent.ToolCallStart(TH, RUN, "call-1", "list_my_tasks", null, null)));
        JsonNode root = MAPPER.readTree(masked.maskThinkText(masked.snapshotSteps(TH), "000000"));
        assertEquals("[已脱敏]", root.get("thinkSegs").get(0).get("text").asText(), "思考正文过 PII 掩码");
        assertEquals("list_my_tasks", root.get("steps").get(0).get("name").asText(), "步骤行 name 不受掩码影响");
    }

    /** goal 加固 H6：纯数组旧形态的 steps[].argsText/brief 也过掩码（模型复述 PII 入参兜底） */
    @Test
    void legacyArrayShapeArgsTextMasked() throws Exception {
        AssistantRunRecorder masked = new AssistantRunRecorder(null, null, null,
            new org.dromara.assistant.security.AssistantPiiFilter(null) {
                @Override
                public String mask(String text, String tenantId) {
                    return "[已脱敏]";
                }
            }, null, new org.dromara.assistant.billing.AssistantCostLedger());
        masked.onEvents(ctx(), List.of(
            new AguiEvent.ToolCallStart(TH, RUN, "call-1", "list_my_tasks", null, null),
            new AguiEvent.ToolCallArgs(TH, RUN, "call-1", "{\"phone\":\"13800000000\"}", null, null)));
        JsonNode root = MAPPER.readTree(masked.maskThinkText(masked.snapshotSteps(TH), "000000"));
        assertTrue(root.isArray(), "纯数组旧形态保持");
        assertEquals("[已脱敏]", root.get(0).get("argsText").asText(), "argsText 过 PII 掩码");
        assertEquals("list_my_tasks", root.get(0).get("name").asText(), "name 不动");
    }

    /** goal 加固 H6：新形态（steps+thinkSegs）双掩码；未命中 PII 的文本字节级不动（免重序列化） */
    @Test
    void noPiiHitReturnsOriginalString() throws Exception {
        AssistantRunRecorder plain = new AssistantRunRecorder(null, null, null,
            new org.dromara.assistant.security.AssistantPiiFilter(null) {
                @Override
                public String mask(String text, String tenantId) {
                    return text;
                }
            }, null, new org.dromara.assistant.billing.AssistantCostLedger());
        plain.onEvents(ctx(), List.of(
            new AguiEvent.ReasoningMessageStart(TH, RUN, "wm-1", "assistant", 1000L, null),
            new AguiEvent.ReasoningMessageContent(TH, RUN, "wm-1", "无敏感内容"),
            new AguiEvent.ReasoningMessageEnd(TH, RUN, "wm-1", 2000L, null)));
        String snap = plain.snapshotSteps(TH);
        assertTrue(snap == plain.maskThinkText(snap, "000000"), "掩码未命中=原串引用直返（字节稳定）");
    }

    @Test
    void textCappedAt4000() {
        StringBuilder big = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            big.append("二十个字符的一段思考内容垫量！");
        }
        String chunk = big.toString();
        recorder.onEvents(ctx(), List.of(
            new AguiEvent.ReasoningStart(TH, RUN, "rm-1", null, 1000L, null),
            new AguiEvent.ReasoningMessageContent(TH, RUN, "rm-1", chunk),
            new AguiEvent.ReasoningEnd(TH, RUN, "rm-1", 2000L, null)));
        List<AssistantRunRecorder.ThinkSegBuf> segs =
            recorder.thinkBuffers.get(TH);
        assertEquals(4000, segs.get(0).text.length(), "思考文本 4000 上限");
    }
}
