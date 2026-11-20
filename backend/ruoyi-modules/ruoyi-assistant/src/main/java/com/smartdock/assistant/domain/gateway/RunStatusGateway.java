package com.smartdock.assistant.domain.gateway;

import java.util.Set;

/**
 * run 在途状态读口（批 7 · 7B，2026-11-20 拍板 Q3 ★ 案 1）。
 *
 * <p>存在理由：会话列表要回填「生成中」标志，而 RUNNING 数据在 {@code dcs_assistant_run}
 * 表。建接口时该表的 Mapper 还在 legacy，新根 query 服务按规则 8 不许 import legacy，
 * 故把这一条读取收成接口放新根、实现落 infrastructure（跨线桥）。</p>
 *
 * <p><b>现状（7C-1 后）</b>：run 表 Mapper 已搬进新根 {@code mapper/AssistantRunMapper}，
 * 台账读写归 {@code service/RunLedgerService}；本接口仍保留，因为它把「列表回填要一条
 * 集合查询」这件事与新根 query 服务隔开，实现内部改指向新根 mapper，不再跨线。</p>
 *
 * <p>语义与搬移前 {@code AssistantConversationService.markRunning} 的内联查询逐行一致
 * （该方法已随旧类删除）：取该 (租户, 用户) 当前 RUNNING 的 thread 集合；
 * 无在途轮返回空集，不返回 null。</p>
 */
public interface RunStatusGateway {

    /** @return 该用户当前 RUNNING 的 threadId 集合（无在途轮=空集） */
    Set<String> runningThreadIds(String tenantId, Long userId);
}
