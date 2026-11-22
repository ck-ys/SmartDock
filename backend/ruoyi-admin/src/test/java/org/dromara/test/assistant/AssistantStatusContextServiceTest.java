package org.dromara.test.assistant;

import com.smartdock.assistant.domain.gateway.ConversationReadGateway;
import com.smartdock.assistant.domain.gateway.PromptCatalogGateway;
import com.smartdock.assistant.domain.model.DigestResult;
import com.smartdock.assistant.domain.vo.ContextBreakdownVo;
import com.smartdock.assistant.infrastructure.AssistantModelFactory;
import com.smartdock.assistant.engine.core.MemoryInjectModelDecorator;
import com.smartdock.assistant.query.AssistantStatusContextService;
import com.smartdock.assistant.service.AssistantMemoryService;
import com.smartdock.assistant.service.AssistantSkillVisibilityService;
import com.smartdock.assistant.support.ContextBreakdownEstimator;
import com.smartdock.domain.AiConfig;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 批 7 · 7A（2026-11-20）：{@code /status/context} 主体从 controller 搬到
 * {@link AssistantStatusContextService} 后的<b>口径单测</b>。
 *
 * <p>为什么要有这层测试：这次是「168 行原样搬移」，只靠逐字节快照对账只能证明「这一个会话
 * 的输出没变」，证明不了口径本身（0 桶不下发、discipline-urge 剔除、记忆单列、占比降序、
 * 舍入到 0.0 的桶剔除、归属越权降级）。这些规则以前只活在 controller 方法体里，没有任何
 * 单测钉住；搬进 service 后正好补上，防后续批 9 引擎收口时静默改口径。</p>
 *
 * <p>全部是纯内存单测（gateway 全 mock），不连库、不起 Spring。</p>
 */
@Tag("local")
class AssistantStatusContextServiceTest {

    private static final String TENANT = "000000";
    /**
     * 故意取 Long 缓存区间（-128..127）之外：缓存区间内 {@code ==} 与 {@code equals} 同答案，
     * 归属判定写成 {@code ==} 也会绿——批 7 · 7A 多审 glm53flash 席 P1-1 就是这么漏过的。
     */
    private static final Long USER_ID = 9100000001L;
    /** 同值不同对象的用户 id：钉住「按值比较」而不是「按引用比较」 */
    private static Long userIdValue() {
        return Long.parseLong("9100000001");
    }
    private static final Long OTHER_USER_ID = 9100000002L;
    private static final String THREAD = "thread-1";
    private static final Long CONV_ID = 100L;

    // ------------------------------------------------------------------
    // 夹具
    // ------------------------------------------------------------------

    /** 默认空目录：各段为空、记忆关、角色档 1、无选中技能 */
    private static PromptCatalogGateway catalog() {
        PromptCatalogGateway c = mock(PromptCatalogGateway.class);
        when(c.segments(anyBoolean())).thenReturn(List.of());
        when(c.systemToolNames()).thenReturn(List.of());
        when(c.skills(anyString())).thenReturn(List.of());
        when(c.selectedSkillId(anyString())).thenReturn(null);
        when(c.dateSegmentText()).thenReturn("");
        when(c.memoryEnabled(anyString())).thenReturn(false);
        when(c.toolGate(anyString())).thenReturn(Map.of());
        when(c.currentRoleLevel()).thenReturn(1);
        when(c.memoryPromptPrefix()).thenReturn(MemoryInjectModelDecorator.MEMORY_PROMPT_PREFIX);
        return c;
    }

    /** 会话口：ref 为 null = 会话不存在（新会话首次发送前的 threadId） */
    private static ConversationReadGateway conv(ConversationReadGateway.ConversationRef ref) {
        ConversationReadGateway g = mock(ConversationReadGateway.class);
        when(g.conversationByThread(eq(TENANT), eq(THREAD))).thenReturn(ref);
        when(g.listMessagesTail(anyString(), anyLong(), anyLong(), anyInt())).thenReturn(List.of());
        when(g.latestContextTokens(anyString(), anyLong(), anyLong())).thenReturn(null);
        return g;
    }

    /** 生效模型：窗口 window；window=null 模拟「模型未配置」 */
    private static AssistantModelFactory model(Integer window) {
        AssistantModelFactory f = mock(AssistantModelFactory.class);
        if (window == null) {
            when(f.resolveConfigWithUserChoice(anyString(), any())).thenThrow(new IllegalStateException("模型未配置"));
            return f;
        }
        AiConfig cfg = new AiConfig();
        cfg.setContextWindow(window);
        when(f.resolveConfigWithUserChoice(anyString(), any())).thenReturn(cfg);
        return f;
    }

    private static AssistantStatusContextService svc(PromptCatalogGateway catalog, ConversationReadGateway conv,
                                                     AssistantSkillVisibilityService vis,
                                                     AssistantMemoryService mem, AssistantModelFactory factory) {
        return new AssistantStatusContextService(catalog, conv, vis, mem, factory);
    }

    private static Map<String, ContextBreakdownVo.Bucket> byKey(ContextBreakdownVo vo) {
        return vo.buckets().stream().collect(Collectors.toMap(ContextBreakdownVo.Bucket::key, Function.identity()));
    }

    // ------------------------------------------------------------------
    // 用例
    // ------------------------------------------------------------------

    /** 归属降级：新会话 threadId 尚未落库 → 200 空构成，且一个取数都不发（不泄露会话是否存在）。 */
    @Test
    void 会话不存在时返回空构成_且不读消息不读记忆() {
        PromptCatalogGateway catalog = catalog();
        ConversationReadGateway conv = conv(null);
        AssistantMemoryService mem = mock(AssistantMemoryService.class);

        ContextBreakdownVo vo = svc(catalog, conv, mock(AssistantSkillVisibilityService.class), mem, model(200000))
            .build(TENANT, USER_ID, THREAD);

        assertTrue(vo.buckets().isEmpty());
        assertNull(vo.totalTokens());
        assertNull(vo.windowTokens());
        assertEquals(ContextBreakdownEstimator.METHOD, vo.method());
        verify(conv, never()).listMessagesTail(anyString(), anyLong(), anyLong(), anyInt());
        verifyNoInteractions(mem);
        verify(catalog, never()).segments(anyBoolean());
    }

    /** 越权降级：会话属于别人 → 同样空构成，且不去读那个会话的消息（防拿他人 threadId 读走构成）。 */
    @Test
    void 会话不属于本人时返回空构成_且不读对方消息() {
        ConversationReadGateway conv = conv(new ConversationReadGateway.ConversationRef(CONV_ID, OTHER_USER_ID));
        AssistantMemoryService mem = mock(AssistantMemoryService.class);

        ContextBreakdownVo vo = svc(catalog(), conv, mock(AssistantSkillVisibilityService.class), mem, model(200000))
            .build(TENANT, USER_ID, THREAD);

        assertTrue(vo.buckets().isEmpty());
        verify(conv, never()).listMessagesTail(anyString(), anyLong(), anyLong(), anyInt());
        verify(conv, never()).latestContextTokens(anyString(), anyLong(), anyLong());
        verifyNoInteractions(mem);
    }

    /**
     * 分段口径：discipline-urge 剔除、cheatsheet 归「工具提示词」、记忆单列且带注入前缀、
     * 技能只算可见项 + 选中技能正文、消息含 assistant 行、系统工具名只按名字算、
     * 占比按总字符求和后降序、subs 只在系统提示词桶内按 sysPrompt 自身总量折算。
     */
    @Test
    void 分段口径_剔除催办段_记忆单列_降序与占比() {
        PromptCatalogGateway catalog = catalog();
        when(catalog.segments(true)).thenReturn(List.of(
            new PromptCatalogGateway.PromptSegment("discipline-urge", "催办纪律", "XXXXXXXXXX"), // 10，应剔除
            new PromptCatalogGateway.PromptSegment("core", "核心规则", "ABCDEFGHIJ"),            // 10
            new PromptCatalogGateway.PromptSegment("date-rule", "日期规则", "12345"),             // 5
            new PromptCatalogGateway.PromptSegment("cheatsheet", "工具提示词", "0123456789")));   // 10 → tools_prompt
        when(catalog.systemToolNames()).thenReturn(List.of("toolA", "toolB"));                  // 10
        when(catalog.skills(TENANT)).thenReturn(List.of(
            new PromptCatalogGateway.SkillEntry("s1", "技能一", "说明", 0),                      // 3+1+2 = 6
            new PromptCatalogGateway.SkillEntry("s2", "技能二", "不可见", 2)));                  // 不可见，0
        when(catalog.selectedSkillId(THREAD)).thenReturn("s1");
        when(catalog.skillBody("s1", 1, TENANT)).thenReturn("BODY");                            // 4 → 技能合计 10
        when(catalog.dateSegmentText()).thenReturn("DATE");                                     // 4
        when(catalog.memoryEnabled(TENANT)).thenReturn(true);
        // 多审 glm53flash 席 P2-3：前缀不取被测方同源常量（那样断言与实现同义反复），
        // 改成桩值 "PFX|"——桶里必须出现这 4 个字符，证明用的是 gateway 给的同一份前缀。
        when(catalog.memoryPromptPrefix()).thenReturn("PFX|");

        ConversationReadGateway conv = conv(new ConversationReadGateway.ConversationRef(CONV_ID, userIdValue()));
        when(conv.listMessagesTail(TENANT, USER_ID, CONV_ID, 500)).thenReturn(List.of(
            new ConversationReadGateway.MessageRef("user", "hello", null),                       // 5
            new ConversationReadGateway.MessageRef("assistant", "hi", "TOOLSTEPS"),              // 2 + 9 = 11（含工具步骤）
            new ConversationReadGateway.MessageRef("user", "second", null)));                    // 6 → 消息合计 22

        AssistantSkillVisibilityService vis = mock(AssistantSkillVisibilityService.class);
        when(vis.visible("s1", 0, 1, Map.of())).thenReturn(true);
        when(vis.visible("s2", 2, 1, Map.of())).thenReturn(false);

        AssistantMemoryService mem = mock(AssistantMemoryService.class);
        when(mem.digestReadOnly(TENANT, USER_ID, "second")).thenReturn(new DigestResult("MEM", 1, 3, List.of(1L)));

        ContextBreakdownVo vo = svc(catalog, conv, vis, mem, model(200000)).build(TENANT, USER_ID, THREAD);
        Map<String, ContextBreakdownVo.Bucket> m = byKey(vo);

        // ① 系统提示词 = core 10 + date-rule 5，催办段被剔除；cheatsheet 不算进来
        assertEquals(15, m.get("system_prompt").chars());
        // ② 工具提示词 = cheatsheet 段；③ 系统工具 = 工具名合计；④ 技能 = 可见目录 6 + 选中正文 4
        assertEquals(10, m.get("tools_prompt").chars());
        assertEquals(10, m.get("system_tools").chars());
        assertEquals(10, m.get("skills").chars());
        // ⑤ 消息 = 5 + (2+9) + 6，工具步骤必须计入；⑥ 记忆 = gateway 前缀(4) + 摘要正文(3)；⑦ 其他 = 日期段
        assertEquals(22, m.get("messages").chars());
        assertEquals(7, m.get("memory").chars());
        assertEquals(4, m.get("other").chars());
        assertEquals(7, m.size());

        // 占比：每桶 = 该桶 chars ÷ 全部桶 chars 之和；总和不超 100.5（各自四舍五入）
        long sum = vo.buckets().stream().mapToLong(ContextBreakdownVo.Bucket::chars).sum();
        for (ContextBreakdownVo.Bucket b : vo.buckets()) {
            assertEquals(ContextBreakdownEstimator.pctOf(b.chars(), sum), b.pct(), 1e-9, b.key());
        }
        assertEquals(100.0, vo.buckets().stream().mapToDouble(ContextBreakdownVo.Bucket::pct).sum(), 0.5);
        // 降序 + 并列桶顺序钉死（多审 glm53flash 席 P2-5：前端按下标取色，同桶换色会被当成状态变化）
        assertEquals(List.of("messages", "system_prompt", "tools_prompt", "system_tools", "skills", "memory", "other"),
            vo.buckets().stream().map(ContextBreakdownVo.Bucket::key).toList());
        // subs 只在系统提示词桶，且按 sysPrompt 自身总量（15）折算
        assertEquals(2, m.get("system_prompt").subs().size());
        assertEquals(66.7, m.get("system_prompt").subs().get(0).pct(), 1e-9);
        assertEquals(33.3, m.get("system_prompt").subs().get(1).pct(), 1e-9);
        assertNull(m.get("tools_prompt").subs());
        // 低置信标记：系统工具仅名称、消息只数近 500 条
        assertTrue(m.get("system_tools").lowConfidence());
        assertTrue(m.get("messages").lowConfidence());
        assertFalse(m.get("memory").lowConfidence());
        // 记忆打分用的是「最后一条非空用户提问」——尾部有两条 user 时必须是后一条 "second"
        verify(mem).digestReadOnly(TENANT, USER_ID, "second");
        verify(mem, never()).digestReadOnly(anyString(), anyLong(), eq("hello"));
    }

    /** 记忆关：不出记忆桶，且一次记忆读都不发（连打分都不做）。 */
    @Test
    void 记忆关闭时不出记忆桶_且不调记忆读取() {
        PromptCatalogGateway catalog = catalog();
        when(catalog.segments(false)).thenReturn(List.of(
            new PromptCatalogGateway.PromptSegment("core", "核心规则", "ABCDEFGHIJ")));
        when(catalog.memoryEnabled(TENANT)).thenReturn(false);

        AssistantMemoryService mem = mock(AssistantMemoryService.class);
        ContextBreakdownVo vo = svc(catalog, conv(new ConversationReadGateway.ConversationRef(CONV_ID, userIdValue())),
            mock(AssistantSkillVisibilityService.class), mem, model(200000)).build(TENANT, USER_ID, THREAD);

        assertFalse(byKey(vo).containsKey("memory"));
        verify(mem, never()).digestReadOnly(anyString(), anyLong(), any());
    }

    /** 技能正文读失败只影响「技能」这一桶，不让整个状态接口变 500（原 controller 的 try/catch 口径）。 */
    @Test
    void 选中技能正文读取失败时不影响其他桶() {
        PromptCatalogGateway catalog = catalog();
        when(catalog.skills(TENANT)).thenReturn(List.of(
            new PromptCatalogGateway.SkillEntry("s1", "技能一", "说明", 0)));
        when(catalog.selectedSkillId(THREAD)).thenReturn("s1");
        when(catalog.skillBody(anyString(), anyInt(), anyString())).thenThrow(new IllegalStateException("boom"));
        when(catalog.dateSegmentText()).thenReturn("DATE");

        AssistantSkillVisibilityService vis = mock(AssistantSkillVisibilityService.class);
        when(vis.visible(eq("s1"), anyInt(), anyInt(), any())).thenReturn(true);

        ContextBreakdownVo vo = svc(catalog, conv(new ConversationReadGateway.ConversationRef(CONV_ID, userIdValue())),
            vis, mock(AssistantMemoryService.class), model(200000)).build(TENANT, USER_ID, THREAD);
        Map<String, ContextBreakdownVo.Bucket> m = byKey(vo);

        // 目录部分（6）照算，正文（4）丢了——整段按失败前的累计值下发，不抛
        assertEquals(6, m.get("skills").chars());
        assertEquals(4, m.get("other").chars());
    }

    /** 真值两字段：totalTokens 取会话最新 tokens、windowTokens 取生效模型窗口；模型未配置时窗口 null。 */
    @Test
    void 总量与窗口取真值_模型未配置时窗口为空() {
        ConversationReadGateway conv = conv(new ConversationReadGateway.ConversationRef(CONV_ID, userIdValue()));
        when(conv.latestContextTokens(TENANT, USER_ID, CONV_ID)).thenReturn(1234L);
        PromptCatalogGateway catalog = catalog();
        when(catalog.segments(false)).thenReturn(List.of(
            new PromptCatalogGateway.PromptSegment("core", "核心规则", "ABCDEFGHIJ")));

        ContextBreakdownVo ok = svc(catalog, conv, mock(AssistantSkillVisibilityService.class),
            mock(AssistantMemoryService.class), model(200000)).build(TENANT, USER_ID, THREAD);
        assertEquals(1234L, ok.totalTokens());
        assertEquals(200000L, ok.windowTokens());

        ContextBreakdownVo noModel = svc(catalog, conv, mock(AssistantSkillVisibilityService.class),
            mock(AssistantMemoryService.class), model(null)).build(TENANT, USER_ID, THREAD);
        assertNull(noModel.windowTokens());
        assertEquals(1234L, noModel.totalTokens()); // 窗口解析失败不影响总量真值
        assertFalse(noModel.buckets().isEmpty());    // 也不影响构成
    }

    /** 占比四舍五入到 0.0 的桶不下发（避免面板出现「约 0%」被当成坏了）。 */
    @Test
    void 占比舍入为零的桶不下发() {
        PromptCatalogGateway catalog = catalog();
        when(catalog.dateSegmentText()).thenReturn("D"); // 1 字符
        ConversationReadGateway conv = conv(new ConversationReadGateway.ConversationRef(CONV_ID, userIdValue()));
        when(conv.listMessagesTail(TENANT, USER_ID, CONV_ID, 500)).thenReturn(List.of(
            new ConversationReadGateway.MessageRef("user", "x".repeat(100_000), null)));

        ContextBreakdownVo vo = svc(catalog, conv, mock(AssistantSkillVisibilityService.class),
            mock(AssistantMemoryService.class), model(200000)).build(TENANT, USER_ID, THREAD);

        assertEquals(List.of("messages"), vo.buckets().stream().map(ContextBreakdownVo.Bucket::key).toList());
    }

    /**
     * 批 10（2026-11-22）Q5 老尾巴：同一次 build 只查一次会话行。
     *
     * <p>原先 {@code build} 第 49 行查了一次拿归属，{@code contextTokensOf} 内部又查一次拿
     * conversationId——同一个 (tenant, thread) 在一次请求里打两遍库。这不是正确性缺陷（两次
     * 结果一致），是白白的往返；且两处各自吞异常/各自判空，将来任一处改了谓词就会出现
     * 「归属判定用 A 版本、tokens 取值用 B 版本」的隐性分叉。</p>
     */
    @Test
    void 同一次构建只查一次会话行() {
        ConversationReadGateway conv = conv(new ConversationReadGateway.ConversationRef(CONV_ID, userIdValue()));
        when(conv.latestContextTokens(TENANT, USER_ID, CONV_ID)).thenReturn(1234L);
        PromptCatalogGateway catalog = catalog();
        when(catalog.segments(false)).thenReturn(List.of(
            new PromptCatalogGateway.PromptSegment("core", "核心规则", "ABCDEFGHIJ")));

        svc(catalog, conv, mock(AssistantSkillVisibilityService.class),
            mock(AssistantMemoryService.class), model(200000)).build(TENANT, USER_ID, THREAD);

        org.mockito.Mockito.verify(conv, org.mockito.Mockito.times(1))
            .conversationByThread(TENANT, THREAD);
    }
}
