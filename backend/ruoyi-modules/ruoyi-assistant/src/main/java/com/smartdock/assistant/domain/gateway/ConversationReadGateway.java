package com.smartdock.assistant.domain.gateway;

import java.util.List;

/**
 * 「会话只读」取数口（批 7 · 7A，2026-11-20）。
 *
 * <p>为什么建 gateway：{@code /status/context} 要读会话归属、消息尾部与最新 tokens，
 * 原先由 legacy 的 {@code AssistantConversationService} 提供——该类已在批 7C-2 整个删除，
 * 读侧现归新根 {@code query/ConversationQueryService}。规则 8 禁新根 service
 * import legacy，故收在此接口后面。</p>
 *
 * <p><b>现状（7C-2 后）</b>：接口签名与调用方一直没动，实现已指向新根查询服务，
 * 不再跨线读 legacy。</p>
 */
public interface ConversationReadGateway {

    /** 会话最小投影（归属校验要 userId；取消息/tokens 要 id） */
    record ConversationRef(Long id, Long userId) {
    }

    /** 消息最小投影（原文 + 工具步骤；两者都算进「消息」桶字符） */
    record MessageRef(String role, String content, String toolSteps) {
    }

    /** threadId → 会话（不存在返回 null；调用方据此走「0 桶」降级） */
    ConversationRef conversationByThread(String tenantId, String threadId);

    /** 该会话最近 limit 条消息（时间正序，与原服务同口径） */
    List<MessageRef> listMessagesTail(String tenantId, Long userId, Long conversationId, int limit);

    /** 该会话最新 assistant 消息末次调用 tokens（无值返回 null） */
    Long latestContextTokens(String tenantId, Long userId, Long conversationId);
}
