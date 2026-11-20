package com.smartdock.assistant.domain.gateway;

import java.util.Set;

/**
 * run 在途状态读口（批 7 · 7B，2026-11-20 拍板 Q3 ★ 案 1）。
 *
 * <p>存在理由：会话列表要回填「生成中」标志，而 RUNNING 数据在 {@code dcs_assistant_run}
 * 表、其 Mapper 仍留在 legacy。新根 query 服务按规则 8 不许 import legacy，故把这一条
 * 读取收成接口放新根、实现落 infrastructure（跨线桥）。</p>
 *
 * <p>语义与 {@code AssistantConversationService#markRunning} 原内联调用逐行一致：
 * 取该 (租户, 用户) 当前 RUNNING 的 thread 集合；无在途轮返回空集，不返回 null。</p>
 */
public interface RunStatusGateway {

    /** @return 该用户当前 RUNNING 的 threadId 集合（无在途轮=空集） */
    Set<String> runningThreadIds(String tenantId, Long userId);
}
