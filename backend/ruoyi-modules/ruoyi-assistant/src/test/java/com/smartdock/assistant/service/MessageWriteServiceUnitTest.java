package com.smartdock.assistant.service;

import com.smartdock.assistant.domain.model.AssistantMessage;
import com.smartdock.assistant.mapper.AssistantCompactionMapper;
import com.smartdock.assistant.mapper.AssistantConversationMapper;
import com.smartdock.assistant.mapper.AssistantMessageMapper;
import com.smartdock.assistant.support.AssistantPiiFilter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 批 7 · 7C-1（2026-11-20）：{@link MessageWriteService} 的语义钉子。
 *
 * <p>钉两件搬迁最易漂移的事：① X-08 明文例外矩阵——user 明文、assistant 掩码、
 * partial 更新与插入同口径（三处掩码不一致=同一条回答在刷新前后不一样）；
 * ② 落库后必须 {@code touch} 会话行（漏了=会话列表排序不更新，用户看不到新消息）。</p>
 */
@Tag("local")
class MessageWriteServiceUnitTest {

    private static final String TENANT = "000000";
    private static final Long CONV = 77L;
    private static final Long USER = 9100000001L;

    private final AssistantConversationMapper conversationMapper = mock(AssistantConversationMapper.class);
    private final AssistantMessageMapper messageMapper = mock(AssistantMessageMapper.class);
    private final AssistantCompactionMapper compactionMapper = mock(AssistantCompactionMapper.class);
    private final AssistantPiiFilter piiFilter = mock(AssistantPiiFilter.class);

    private MessageWriteService svc() {
        return new MessageWriteService(conversationMapper, messageMapper, compactionMapper, piiFilter);
    }

    @Test
    void user消息走明文通道不脱敏() {
        svc().appendUserMessage(TENANT, CONV, USER, "请联系 13800138000");

        ArgumentCaptor<AssistantMessage> cap = ArgumentCaptor.forClass(AssistantMessage.class);
        verify(messageMapper).insert(cap.capture());
        assertEquals("请联系 13800138000", cap.getValue().getContent(), "user 侧是本人原文通道（X-08 明文例外）");
        assertEquals("user", cap.getValue().getRole());
        assertEquals("normal", cap.getValue().getEndReason());
        verifyNoInteractions(piiFilter);
        verify(conversationMapper).touch(TENANT, CONV);
    }

    @Test
    void assistant消息落库前按租户脱敏() {
        when(piiFilter.mask("手机 13800138000", TENANT)).thenReturn("手机 [手机号]");

        svc().appendAssistantMessage(TENANT, CONV, USER, "手机 13800138000", "deepseek", "stop");

        ArgumentCaptor<AssistantMessage> cap = ArgumentCaptor.forClass(AssistantMessage.class);
        verify(messageMapper).insert(cap.capture());
        assertEquals("手机 [手机号]", cap.getValue().getContent());
        assertEquals("assistant", cap.getValue().getRole());
        assertEquals("deepseek", cap.getValue().getModel());
    }

    @Test
    void assistant消息返回消息id供追问侧车回写() {
        when(messageMapper.insert(any(AssistantMessage.class))).thenAnswer(inv -> {
            AssistantMessage m = inv.getArgument(0);
            assertNotNull(m.getId(), "插入前必须已生成雪花 id（否则追问侧车拿不到定位键）");
            return 1;
        });

        Long id = svc().appendAssistantMessage(TENANT, CONV, USER, "答案", "m", "stop", "[]", 1234L);
        assertNotNull(id);
    }

    @Test
    void partial更新与插入同口径掩码() {
        when(piiFilter.mask("中途 13800138000", TENANT)).thenReturn("中途 [手机号]");

        svc().updatePartialMessage(TENANT, 9001L, "中途 13800138000", "[]", "partial");

        verify(messageMapper).updatePartial(9001L, TENANT, "中途 [手机号]", "[]", "partial");
        verify(conversationMapper, never()).touch(anyString(), anyLong());
    }

    @Test
    void 追问建议空值不覆盖() {
        MessageWriteService s = svc();
        s.writeFollowupSuggestions(TENANT, null, "[\"a\"]");
        s.writeFollowupSuggestions(TENANT, 9001L, null);
        s.writeFollowupSuggestions(TENANT, 9001L, "   ");
        verifyNoInteractions(messageMapper, conversationMapper);

        s.writeFollowupSuggestions(TENANT, 9001L, "[\"a\"]");
        verify(messageMapper).updateFollowupSuggestions(9001L, TENANT, "[\"a\"]");
    }

    @Test
    void 分隔条行走system角色且不脱敏() {
        svc().appendSystemDivider(TENANT, CONV, USER, "{\"type\":\"compaction\",\"beforeTokens\":1}");

        ArgumentCaptor<AssistantMessage> cap = ArgumentCaptor.forClass(AssistantMessage.class);
        verify(messageMapper).insert(cap.capture());
        assertEquals("system", cap.getValue().getRole(), "前端 rebuildFromHistory 按 role=system 渲染分隔条");
        assertNull(cap.getValue().getModel());
        verifyNoInteractions(piiFilter);
    }

    @Test
    void 删除partial行只按消息id与租户() {
        svc().deletePartialMessage(TENANT, 9001L);
        verify(messageMapper).deletePartial(9001L, TENANT);
        verify(conversationMapper, never()).touch(anyString(), anyLong());
    }

    @Test
    void 落库后必须刷新会话排序键() {
        svc().appendAssistantMessage(TENANT, CONV, USER, "x", "m", "stop");
        verify(conversationMapper).touch(eq(TENANT), eq(CONV));
    }
}
