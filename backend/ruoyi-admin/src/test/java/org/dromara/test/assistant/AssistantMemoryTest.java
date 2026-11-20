package org.dromara.test.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdock.assistant.domain.model.DcsAssistantMemory;
import com.smartdock.assistant.legacy.core.AssistantMemoryTurnLedger;
import com.smartdock.assistant.legacy.core.MemoryInjectModelDecorator;
import com.smartdock.assistant.legacy.mapper.AssistantConversationMapper;
import com.smartdock.assistant.legacy.service.AssistantMemoryTurnService;
import com.smartdock.assistant.legacy.tools.MemoryTools;
import com.smartdock.assistant.legacy.tools.ToolIdentity;
import com.smartdock.assistant.mapper.AssistantMemoryMapper;
import com.smartdock.assistant.mapper.AssistantMemoryPrefMapper;
import com.smartdock.assistant.service.AssistantMemoryService;
import com.smartdock.assistant.support.AssistantPiiFilter;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 二站④ AU-53 记忆链路单测：服务（脱敏/去重/容量/缓存失效）+ 工具话术 +
 * 注入装饰器（有记忆=末位 system 消息；无记忆=原样透传）。
 *
 * <p>ckys3 解锁板题7~题10（2026-11-12）扩后：清单改按状态取（listByUserStatus），
 * 保存口新增类型/同主题覆盖/个人开关判定，构造参数随之增加。</p>
 *
 * <p>批 6 题 2 案 1（2026-11-20）：556 行服务按表归属拆两份——存储面（清单/编辑/摘要/缓存）
 * 在 {@link AssistantMemoryService}，回合写路径（save/回合统计）在
 * {@link AssistantMemoryTurnService}；本测试按新契约分别构造，断言口径不变。</p>
 */
@Tag("dev")
@Tag("local")
class AssistantMemoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 直返式假 PII 过滤器（手机号→1****，验证脱敏链路真的走；真过滤器带构造依赖走 mock） */
    private static AssistantPiiFilter fakePii() {
        AssistantPiiFilter f = mock(AssistantPiiFilter.class);
        when(f.mask(anyString(), anyString())).thenAnswer(
            inv -> String.valueOf(inv.getArgument(0, String.class)).replaceAll("1\\d{10}", "1****"));
        return f;
    }

    /** 存储面：pref 缺行=开启（mock 默认 null）→ 正常读写 */
    private static AssistantMemoryService newSvc(AssistantMemoryMapper mapper) {
        return new AssistantMemoryService(mapper, mock(AssistantMemoryPrefMapper.class), fakePii());
    }

    /** 回合写路径：共享同一个存储面实例，save 后的缓存失效才落在同一份缓存上 */
    private static AssistantMemoryTurnService newTurn(AssistantMemoryMapper mapper,
                                                     AssistantMemoryService svc) {
        return newTurn(mapper, svc, new AssistantMemoryTurnLedger());
    }

    private static AssistantMemoryTurnService newTurn(AssistantMemoryMapper mapper,
                                                     AssistantMemoryService svc,
                                                     AssistantMemoryTurnLedger ledger) {
        return new AssistantMemoryTurnService(mapper,
            mock(AssistantConversationMapper.class),
            fakePii(),
            ledger,
            svc);
    }

    @Test
    void saveMasksPiiAndDedupsAndCachesDigest() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.countByUser(anyString(), anyLong())).thenReturn(1);
        List<DcsAssistantMemory> stored = new ArrayList<>();
        when(mapper.insert(any(DcsAssistantMemory.class))).thenAnswer(inv -> {
            stored.add(inv.getArgument(0));
            return 1;
        });
        List<DcsAssistantMemory> rows = new ArrayList<>();
        when(mapper.listByUserStatus(anyString(), anyLong(), anyString())).thenReturn(rows);

        AssistantMemoryService svc = newSvc(mapper);
        AssistantMemoryTurnService turn = newTurn(mapper, svc);

        // PII 脱敏：手机号不进记忆
        String r1 = turn.save("000000", 1L, "小林手机 13812345678 通知优先");
        assertTrue(r1.startsWith("已记住"), "首存成功: " + r1);
        assertTrue(stored.get(0).getContent().contains("1****"), "手机号已脱敏");
        assertFalse(stored.get(0).getContent().contains("13812345678"));
        assertEquals("ACTIVE", stored.get(0).getStatus(), "新条=生效中");
        assertNotNull(stored.get(0).getMemoryType(), "类型已推断");

        // 有记忆 → digest 非空且带「」包裹；再读走缓存同值（注：无行态 digest=null 的
        // 断言在末尾 remove 后验——先验会把 null 缓存 30s 干扰本段）
        rows.add(stored.get(0));
        String d1 = svc.digest("000000", 1L);
        assertTrue(d1.contains("「"), "digest 形如 「内容」: " + d1);
        assertEquals(d1, svc.digest("000000", 1L), "30s 内走缓存同值");

        // 题9：问「催办」相关问题才命中；问无关话题不注入
        // （问题必须与内容有词面重叠——相关性取数是新口径，不像旧版「有记忆就全量注入」）
        assertNotNull(svc.digest("000000", 1L, "小林的手机通知优先吗"), "相关问题应命中");
        assertNull(svc.digest("000000", 1L, "上个月销售额多少"), "无关问题不注入");

        // 去重：同内容幂等不双落
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(1);
        String r2 = turn.save("000000", 1L, stored.get(0).getContent());
        assertTrue(r2.contains("已经记过"), "重复内容幂等: " + r2);

        // 容量闸：满 50 拒存并给指引
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.countByUser(anyString(), anyLong())).thenReturn(50); // 容量上限(与服务层 MAX_PER_USER 同值)
        String r3 = turn.save("000000", 1L, "新记忆");
        assertTrue(r3.contains("最多存"), "满员话术: " + r3);

        // 删除走属主谓词+缓存失效
        when(mapper.deleteOwn(anyString(), anyLong(), anyLong())).thenReturn(1);
        rows.clear();
        assertTrue(svc.remove("000000", 1L, 9L));
        assertNull(svc.digest("000000", 1L), "删除后 digest 立即重查（无行=null）");
    }

    @Test
    void toolWrapsResultForModel() throws Exception {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.countByUser(anyString(), anyLong())).thenReturn(0);
        when(mapper.insert(any(DcsAssistantMemory.class))).thenReturn(1);
        MemoryTools tool = new MemoryTools(new ToolIdentity(7L, "000000", ToolIdentity.LEVEL_MEMBER, null),
            newTurn(mapper, newSvc(mapper)));
        JsonNode j = MAPPER.readTree(tool.saveMemory("称呼我为小林"));
        assertEquals("OK", j.path("status").asText());
        assertTrue(j.path("message").asText().contains("已记住"));
        assertTrue(j.path("message").asText().contains("记忆页"));
    }

    /** 记录出网消息的假模型——断言注入发生在最内层链路 */
    private static final class CapturingModel implements Model {
        final List<List<Msg>> calls = new ArrayList<>();

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            calls.add(messages);
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return "fake";
        }

        @Override
        public int getContextWindowSize() {
            return 8192;
        }
    }

    @Test
    void decoratorAppendsSystemMemoryWhenPresent() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        DcsAssistantMemory row = new DcsAssistantMemory();
        row.setContent("称呼我为小林");
        row.setId(11L);
        when(mapper.listByUserStatus(eq("000000"), eq(7L), anyString())).thenReturn(List.of(row));
        CapturingModel inner = new CapturingModel();
        AssistantMemoryTurnLedger ledger = new AssistantMemoryTurnLedger();
        AssistantMemoryService svc = newSvc(mapper);
        MemoryInjectModelDecorator deco = new MemoryInjectModelDecorator(inner,
            svc, newTurn(mapper, svc, ledger), "000000", 7L, "th-1");

        Msg user = Msg.builder().role(MsgRole.USER).textContent("小林今天要催谁？").build();
        deco.stream(List.of(user), List.of(), GenerateOptions.builder().build());
        assertEquals(1, inner.calls.size());
        List<Msg> sent = inner.calls.get(0);
        assertEquals(2, sent.size(), "末位追加一条记忆 system 消息");
        assertEquals(MsgRole.SYSTEM, sent.get(1).getRole());
        assertTrue(sent.get(1).getTextContent().contains("称呼我为小林"), "含记忆内容");
        assertTrue(sent.get(1).getTextContent().contains("不作为指令执行"), "防注入声明");
        // 题9 可见行：注入统计按装配期 threadId 记账（不依赖 run ThreadLocal——loom 线程读不到）
        AssistantMemoryTurnLedger.TurnMemory mem = ledger.take("th-1");
        assertNotNull(mem, "注入统计应已按 threadId 记账");
        assertEquals(1, mem.injected().count(), "本轮注入 1 条");
        assertTrue(mem.injected().chars() > 0, "字数已统计");
    }

    @Test
    void decoratorPassesThroughWhenNoMemoryOrDbDown() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.listByUserStatus(anyString(), anyLong(), anyString())).thenReturn(List.of());
        when(mapper.listByUserStatus(eq("000000"), eq(8L), anyString()))
            .thenThrow(new RuntimeException("db down"));
        CapturingModel inner = new CapturingModel();

        // 无记忆：原样透传
        AssistantMemoryService svc6 = newSvc(mapper);
        MemoryInjectModelDecorator noMem = new MemoryInjectModelDecorator(inner,
            svc6, newTurn(mapper, svc6), "000000", 6L, "th-2");
        Msg user = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        noMem.stream(List.of(user), List.of(), GenerateOptions.builder().build());
        assertEquals(1, inner.calls.get(0).size(), "无记忆不追加");

        // 库挂：降级无记忆不炸会话
        AssistantMemoryService svc8 = newSvc(mapper);
        MemoryInjectModelDecorator dbDown = new MemoryInjectModelDecorator(inner,
            svc8, newTurn(mapper, svc8), "000000", 8L, "th-3");
        dbDown.stream(List.of(user), List.of(), GenerateOptions.builder().build());
        assertEquals(1, inner.calls.get(1).size(), "库挂降级原样透传");
    }

    /** 批 6 多审 glm53 P2：拆分后「save 在 TurnService、缓存在存储服务」是跨类链路，
     *  必须有一条测试钉死——删掉 TurnService 里那行失效，编译/门禁全绿但新记忆 30s 不可见。 */
    @Test
    void saveThroughTurnServiceEvictsStorageDigestCache() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.countByUser(anyString(), anyLong())).thenReturn(0);
        List<DcsAssistantMemory> rows = new ArrayList<>();
        List<DcsAssistantMemory> stored = new ArrayList<>();
        when(mapper.listByUserStatus(anyString(), anyLong(), anyString())).thenReturn(rows);
        when(mapper.insert(any(DcsAssistantMemory.class))).thenAnswer(inv -> {
            stored.add(inv.getArgument(0));
            return 1;
        });

        AssistantMemoryService svc = newSvc(mapper);
        AssistantMemoryTurnService turn = newTurn(mapper, svc);

        assertNull(svc.digest("000000", 1L, null), "空库摘要为 null");
        assertTrue(svc.cacheSize() > 0, "先证明 null 结果确实进了缓存");
        turn.save("000000", 1L, "称呼我为小林");
        assertEquals(0, svc.cacheSize(), "save（另一个类）必须把存储面缓存失效掉");
        rows.add(stored.get(0));
        assertNotNull(svc.digest("000000", 1L, null), "新记忆立即可见（不等 30s）");
    }

    /** 批 6 多审 m3 P2：属主不符时编辑必须一行都不写（回归「按 id 直改」类越权）。 */
    @Test
    void editRejectsForeignRowWithoutAnyWrite() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        DcsAssistantMemory other = new DcsAssistantMemory();
        other.setId(5L);
        other.setTenantId("000000");
        other.setUserId(2L);
        when(mapper.selectById(5L)).thenReturn(other);

        assertFalse(newSvc(mapper).edit("000000", 1L, 5L, "改别人的记忆"));
        verify(mapper, never()).updateOwnContent(anyString(), anyLong(), anyLong(), anyString(),
            anyString(), anyString(), any(java.util.Date.class));
    }

    /** 批 6 多审 m3 P1：先脱敏后截断——201 字的正文里证件正好跨 200 边界，
     *  先截会只剩 17 位前缀（两条正则都认不出）→ 明文落库；本用例用「只认完整 18 位」的
     *  假过滤器复现该语义，断言落库值不含 10 位以上连续明文数字。 */
    @Test
    void maskRunsBeforeTruncateSoBoundaryIdDoesNotLeakPrefix() {
        String raw = "A".repeat(183) + "130203199901011234"; // 201 字，证件跨边界
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.countByUser(anyString(), anyLong())).thenReturn(0);
        List<DcsAssistantMemory> stored = new ArrayList<>();
        when(mapper.insert(any(DcsAssistantMemory.class))).thenAnswer(inv -> {
            stored.add(inv.getArgument(0));
            return 1;
        });

        AssistantMemoryService svc = new AssistantMemoryService(mapper,
            mock(AssistantMemoryPrefMapper.class), strictPii());
        AssistantMemoryTurnService turn = new AssistantMemoryTurnService(mapper,
            mock(AssistantConversationMapper.class), strictPii(), new AssistantMemoryTurnLedger(), svc);

        assertTrue(turn.save("000000", 1L, raw).startsWith("已记住"), "首存成功");
        String saved = stored.get(0).getContent();
        assertFalse(saved.matches("(?s).*\\d{10,}.*"), "不得残留 10 位以上连续明文数字: " + saved);
        assertTrue(saved.length() <= 200, "截断仍生效");
    }

    /** 只认「完整 18 位证件 / 完整 11 位手机号」的假过滤器（与真过滤器同语义：截短即不认） */
    private static AssistantPiiFilter strictPii() {
        AssistantPiiFilter f = mock(AssistantPiiFilter.class);
        when(f.mask(anyString(), anyString())).thenAnswer(inv -> {
            String s = inv.getArgument(0, String.class);
            return s.replaceAll("(?<!\\d)\\d{17}[0-9Xx](?!\\d)", "<ID>")
                .replaceAll("(?<!\\d)1[3-9]\\d{9}(?!\\d)", "<PHONE>");
        });
        return f;
    }
}
