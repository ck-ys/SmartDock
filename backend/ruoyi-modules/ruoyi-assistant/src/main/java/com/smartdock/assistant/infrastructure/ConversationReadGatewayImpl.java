package com.smartdock.assistant.infrastructure;

import com.smartdock.assistant.domain.gateway.ConversationReadGateway;
import lombok.RequiredArgsConstructor;
import com.smartdock.assistant.domain.model.DcsAssistantConversation;
import com.smartdock.assistant.domain.model.DcsAssistantMessage;
import com.smartdock.assistant.legacy.service.AssistantConversationService;
import com.smartdock.assistant.query.ConversationQueryService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ConversationReadGateway} 的唯一实现（批 7 · 7A，2026-11-20）。
 *
 * <p>只做投影转换，口径与 {@code AssistantStatusController} 原内联调用逐行一致。
 * 7B 起两条读链（按 thread 取会话 / 尾页消息）改指向新根 {@link ConversationQueryService}；
 * {@code latestContextTokens} 仍走 legacy 服务（消息写入侧本批未搬，7C 再收口）。接口未动。</p>
 */
@Component
@RequiredArgsConstructor
public class ConversationReadGatewayImpl implements ConversationReadGateway {

    /** 7B：会话/消息只读查询（新根） */
    private final ConversationQueryService conversationQueryService;
    /** 7C 前仍留：latestContextTokens 的写入侧（消息落库）本批未搬 */
    private final AssistantConversationService conversationService;

    @Override
    public ConversationRef conversationByThread(String tenantId, String threadId) {
        DcsAssistantConversation conv = conversationQueryService.conversationByThread(tenantId, threadId);
        return conv == null ? null : new ConversationRef(conv.getId(), conv.getUserId());
    }

    @Override
    public List<MessageRef> listMessagesTail(String tenantId, Long userId, Long conversationId, int limit) {
        // 批 7 · 7A 多审 dspro 席 P2-4：原写法对 rows 做 null 防御（rows==null → 空表），
        // 但 mapper 是 @Select 列表查询，MyBatis 只会返回空表不会返回 null——那是死代码，
        // 而且会把「假设发生 null」的异常吞成「消息桶 0」，比旧 controller 的 500 更宽。
        // 这里直接迭代，保持与旧代码同款吞异常宽度（真出 null 就在 <b>listMessagesTail</b> 的调用方 try 里落 0 并留 warn）。
        List<DcsAssistantMessage> rows =
            conversationQueryService.listMessagesTail(tenantId, userId, conversationId, limit);
        List<MessageRef> out = new ArrayList<>(rows.size());
        for (DcsAssistantMessage m : rows) {
            out.add(new MessageRef(m.getRole(), m.getContent(), m.getToolSteps()));
        }
        return out;
    }

    @Override
    public Long latestContextTokens(String tenantId, Long userId, Long conversationId) {
        return conversationService.latestContextTokens(tenantId, userId, conversationId);
    }
}
