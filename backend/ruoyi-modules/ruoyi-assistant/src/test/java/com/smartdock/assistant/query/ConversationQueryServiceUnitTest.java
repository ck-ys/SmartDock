package com.smartdock.assistant.query;

import com.smartdock.assistant.domain.gateway.RunStatusGateway;
import com.smartdock.assistant.domain.model.AssistantConversation;
import com.smartdock.assistant.mapper.AssistantCompactionMapper;
import com.smartdock.assistant.mapper.AssistantConversationMapper;
import com.smartdock.assistant.mapper.AssistantMessageMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 批 9（2026-11-21，7B 多审登记项 P2-2）：{@link ConversationQueryService#conversationByThreadOwned}
 * 的 userId 空值语义钉子。
 *
 * <p>缺陷：原实现 {@code !userId.equals(row.getUserId())} 在 userId 为 null 时 NPE——
 * 当前调用方（controller 传 LoginHelper.getUserId()）登录态恒非空故不可达，但按 fail-closed
 * 原则，归属判定的任何入参缺位都必须「按无权处理返回 null」，不许把 5xx 抛给用户。
 */
@Tag("local")
class ConversationQueryServiceUnitTest {

    private static final String TENANT = "000000";
    private static final String THREAD = "th-1";

    private final AssistantConversationMapper conversationMapper = mock(AssistantConversationMapper.class);
    private final ConversationQueryService svc = new ConversationQueryService(
        conversationMapper, mock(AssistantMessageMapper.class),
        mock(AssistantCompactionMapper.class), mock(RunStatusGateway.class));

    private static AssistantConversation row(Long userId) {
        AssistantConversation c = new AssistantConversation();
        c.setId(77L);
        c.setTenantId(TENANT);
        c.setUserId(userId);
        return c;
    }

    @Test
    void userId为null时按无权处理_不NPE() {
        when(conversationMapper.selectByThread(TENANT, THREAD)).thenReturn(row(1L));
        assertNull(svc.conversationByThreadOwned(TENANT, null, THREAD), "登录态缺位=无权，必须返回 null 而不是 NPE");
    }

    @Test
    void 异主与本人两分支口径不变() {
        when(conversationMapper.selectByThread(TENANT, THREAD)).thenReturn(row(2L));
        assertNull(svc.conversationByThreadOwned(TENANT, 1L, THREAD), "异主=不区分不存在，返回 null");
        when(conversationMapper.selectByThread(TENANT, THREAD)).thenReturn(row(1L));
        assertEquals(Long.valueOf(77L), svc.conversationByThreadOwned(TENANT, 1L, THREAD).getId(), "本人=返回行");
    }
}
