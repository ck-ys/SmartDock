package com.smartdock.assistant.domain.gateway;

/**
 * 消息归属查询网关（批 6 题 3 案 1，2026-11-20）。
 *
 * <p>为什么建网关：反馈服务搬进新根后，提交反馈仍要校验「这条消息是不是本人的」——
 * 消息表与实体还在 legacy 区（会话线留批 7），而 backend/AGENTS.md 规则 8 要求
 * 「legacy 依赖一律经 gateway 收口进 infrastructure」。</p>
 *
 * <p>接口只回布尔：归属判定（租户 + 用户双匹配）和 legacy 实体都关在实现里，
 * 新根服务看不到 legacy 类型，也就不会顺着实体长出更多耦合。</p>
 */
public interface MessageOwnershipGateway {

    /**
     * 消息是否属于该租户该用户。
     *
     * @param tenantId  租户（显式传，流线程无租户上下文）
     * @param userId    用户
     * @param messageId 消息 id（null 返回 false，调用方自行决定「可空=整会话评价」的语义）
     */
    boolean ownedBy(String tenantId, Long userId, Long messageId);
}
