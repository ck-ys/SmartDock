package org.dromara.test.assistant;

import com.smartdock.assistant.domain.gateway.ConversationReadGateway;
import com.smartdock.assistant.domain.gateway.PromptCatalogGateway;
import com.smartdock.assistant.infrastructure.ConversationReadGatewayImpl;
import com.smartdock.assistant.infrastructure.PromptCatalogGatewayImpl;
import com.smartdock.assistant.legacy.core.DateInjectModelDecorator;
import com.smartdock.assistant.legacy.core.MemoryInjectModelDecorator;
import com.smartdock.assistant.domain.model.DcsAssistantConversation;
import com.smartdock.assistant.domain.model.DcsAssistantMessage;
import com.smartdock.assistant.legacy.service.AssistantConfigService;
import com.smartdock.assistant.legacy.service.AssistantConversationService;
import com.smartdock.assistant.query.ConversationQueryService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批 7 · 7A（2026-11-20）：两个 gateway 实现的投影/委托契约。
 *
 * <p>为什么补这层：多审 glm53flash 席 P1-2 指出——业务单测全把 gateway 当 mock，
 * <b>两个实现类零自动化覆盖</b>；7B 要把「会话只读」换成新根服务时，改的就是这两处的
 * 依赖指向，没有测试就等于裸改。这里只钉「接口 ↔ 实现」的映射契约（不做 Spring 装配）。</p>
 */
@Tag("local")
class AssistantGatewayImplTest {

    private static final String TENANT = "000000";
    private static final Long USER_ID = 9100000001L;
    private static final Long CONV_ID = 100L;

    // ------------------------------------------------------------------
    // ConversationReadGatewayImpl
    // ------------------------------------------------------------------

    @Test
    void 会话投影只带id与归属人_不存在返回null() {
        ConversationQueryService query = mock(ConversationQueryService.class);
        DcsAssistantConversation row = new DcsAssistantConversation();
        row.setId(CONV_ID);
        row.setUserId(USER_ID);
        when(query.conversationByThread(TENANT, "t-1")).thenReturn(row);
        when(query.conversationByThread(TENANT, "t-404")).thenReturn(null);

        // 7B：会话只读两条链走新根 query 服务；legacy 服务只剩 latestContextTokens
        ConversationReadGateway gw = new ConversationReadGatewayImpl(query, mock(AssistantConversationService.class));
        ConversationReadGateway.ConversationRef ref = gw.conversationByThread(TENANT, "t-1");
        assertEquals(CONV_ID, ref.id());
        assertEquals(USER_ID, ref.userId());
        assertNull(gw.conversationByThread(TENANT, "t-404"));
    }

    @Test
    void 消息投影保留角色正文与工具步骤且顺序不变() {
        ConversationQueryService query = mock(ConversationQueryService.class);
        DcsAssistantMessage a = new DcsAssistantMessage();
        a.setRole("user");
        a.setContent("hello");
        DcsAssistantMessage b = new DcsAssistantMessage();
        b.setRole("assistant");
        b.setContent("hi");
        b.setToolSteps("{\"tool\":\"x\"}");
        when(query.listMessagesTail(TENANT, USER_ID, CONV_ID, 500)).thenReturn(List.of(a, b));

        ConversationReadGateway gw = new ConversationReadGatewayImpl(query, mock(AssistantConversationService.class));
        List<ConversationReadGateway.MessageRef> out = gw.listMessagesTail(TENANT, USER_ID, CONV_ID, 500);
        assertEquals(2, out.size());
        assertEquals("user", out.get(0).role());
        assertEquals("hello", out.get(0).content());
        assertNull(out.get(0).toolSteps());
        assertEquals("assistant", out.get(1).role());
        // 工具步骤必须原样带出来——它计入「消息」桶字符，丢了统计就偏小
        assertEquals("{\"tool\":\"x\"}", out.get(1).toolSteps());
    }

    @Test
    void 末次tokens原样透传() {
        AssistantConversationService svc = mock(AssistantConversationService.class);
        when(svc.latestContextTokens(TENANT, USER_ID, CONV_ID)).thenReturn(23260L);
        assertEquals(23260L,
            new ConversationReadGatewayImpl(mock(ConversationQueryService.class), svc).latestContextTokens(TENANT, USER_ID, CONV_ID));
    }

    // ------------------------------------------------------------------
    // PromptCatalogGatewayImpl（只测纯委托/常量一致性，不碰 Spring）
    // ------------------------------------------------------------------

    @Test
    void 记忆前缀与日期段取与注入同一份常量() {
        PromptCatalogGateway gw = new PromptCatalogGatewayImpl(mock(AssistantConfigService.class));
        // 「面板里那一段」和「真正注入时那一段」必须是同一份文本，否则占比与实况对不上
        assertEquals(MemoryInjectModelDecorator.MEMORY_PROMPT_PREFIX, gw.memoryPromptPrefix());
        assertFalse(gw.memoryPromptPrefix().isEmpty());
        assertEquals(DateInjectModelDecorator.dateSegmentText(), gw.dateSegmentText());
        assertFalse(gw.dateSegmentText().isEmpty());
    }

    @Test
    void 分段与工具名委托保留原始件() {
        PromptCatalogGateway gw = new PromptCatalogGatewayImpl(mock(AssistantConfigService.class));
        List<PromptCatalogGateway.PromptSegment> segs = gw.segments(false);
        assertNotNull(segs);
        assertFalse(segs.isEmpty());
        assertTrue(segs.stream().anyMatch(s -> "cheatsheet".equals(s.key())), "工具提示词段必须仍在（归 tools_prompt）");
        for (PromptCatalogGateway.PromptSegment s : segs) {
            assertNotNull(s.key());
            assertNotNull(s.title());
            assertNotNull(s.content());
        }
        assertFalse(gw.systemToolNames().isEmpty(), "系统工具名清单不应为空（真实 schema 在引擎侧，这里只给名字）");
    }
}
