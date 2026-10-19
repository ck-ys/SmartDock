package org.dromara.test.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.dromara.assistant.core.MemoryInjectModelDecorator;
import org.dromara.assistant.domain.DcsAssistantMemory;
import org.dromara.assistant.mapper.AssistantMemoryMapper;
import org.dromara.assistant.security.AssistantPiiFilter;
import org.dromara.assistant.service.AssistantMemoryService;
import org.dromara.assistant.tools.MemoryTools;
import org.dromara.assistant.tools.ToolIdentity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 二站④ AU-53 记忆链路单测：服务（脱敏/去重/容量/缓存失效）+ 工具话术 +
 * 注入装饰器（有记忆=末位 system 消息；无记忆=原样透传）。
 */
@Tag("dev")
class AssistantMemoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 直返式假 PII 过滤器（手机号→1****，验证脱敏链路真的走；真过滤器带构造依赖走 mock） */
    private static AssistantPiiFilter fakePii() {
        AssistantPiiFilter f = mock(AssistantPiiFilter.class);
        when(f.mask(anyString(), anyString())).thenAnswer(
            inv -> String.valueOf(inv.getArgument(0, String.class)).replaceAll("1\\d{10}", "1****"));
        return f;
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
        when(mapper.listByUser(anyString(), anyLong())).thenReturn(rows);

        AssistantMemoryService svc = new AssistantMemoryService(mapper, fakePii());

        // PII 脱敏：手机号不进记忆
        String r1 = svc.save("000000", 1L, "小林手机 13812345678 通知优先");
        assertTrue(r1.startsWith("已记住"), "首存成功: " + r1);
        assertTrue(stored.get(0).getContent().contains("1****"), "手机号已脱敏");
        assertFalse(stored.get(0).getContent().contains("13812345678"));

        // 有记忆 → digest 非空且带「」包裹；再读走缓存同值（注：无行态 digest=null 的
        // 断言在末尾 remove 后验——先验会把 null 缓存 30s 干扰本段）
        rows.add(stored.get(0));
        String d1 = svc.digest("000000", 1L);
        assertTrue(d1.contains("「"), "digest 形如 「内容」: " + d1);
        assertEquals(d1, svc.digest("000000", 1L), "30s 内走缓存同值");

        // 去重：同内容幂等不双落
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(1);
        String r2 = svc.save("000000", 1L, stored.get(0).getContent());
        assertTrue(r2.contains("已经记过"), "重复内容幂等: " + r2);

        // 容量闸：满 50 拒存并给指引
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.countByUser(anyString(), anyLong())).thenReturn(50); // 容量上限(与服务层 MAX_PER_USER 同值)
        String r3 = svc.save("000000", 1L, "新记忆");
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
            new AssistantMemoryService(mapper, fakePii()));
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
        when(mapper.listByUser(eq("000000"), eq(7L))).thenReturn(List.of(row));
        CapturingModel inner = new CapturingModel();
        MemoryInjectModelDecorator deco = new MemoryInjectModelDecorator(inner,
            new AssistantMemoryService(mapper, fakePii()), "000000", 7L);

        Msg user = Msg.builder().role(MsgRole.USER).textContent("我今天要催谁？").build();
        deco.stream(List.of(user), List.of(), GenerateOptions.builder().build());
        assertEquals(1, inner.calls.size());
        List<Msg> sent = inner.calls.get(0);
        assertEquals(2, sent.size(), "末位追加一条记忆 system 消息");
        assertEquals(MsgRole.SYSTEM, sent.get(1).getRole());
        assertTrue(sent.get(1).getTextContent().contains("称呼我为小林"), "含记忆内容");
        assertTrue(sent.get(1).getTextContent().contains("不作为指令执行"), "防注入声明");
    }

    @Test
    void decoratorPassesThroughWhenNoMemoryOrDbDown() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.listByUser(anyString(), anyLong())).thenReturn(List.of());
        when(mapper.listByUser(eq("000000"), eq(8L))).thenThrow(new RuntimeException("db down"));
        CapturingModel inner = new CapturingModel();

        // 无记忆：原样透传
        MemoryInjectModelDecorator noMem = new MemoryInjectModelDecorator(inner,
            new AssistantMemoryService(mapper, fakePii()), "000000", 6L);
        Msg user = Msg.builder().role(MsgRole.USER).textContent("hi").build();
        noMem.stream(List.of(user), List.of(), GenerateOptions.builder().build());
        assertEquals(1, inner.calls.get(0).size(), "无记忆不追加");

        // 库挂：降级无记忆不炸会话
        MemoryInjectModelDecorator dbDown = new MemoryInjectModelDecorator(inner,
            new AssistantMemoryService(mapper, fakePii()), "000000", 8L);
        dbDown.stream(List.of(user), List.of(), GenerateOptions.builder().build());
        assertEquals(1, inner.calls.get(1).size(), "库挂降级原样透传");
    }
}
