package com.smartdock.assistant.domain.gateway;

import io.agentscope.core.ReActAgent;
import org.dromara.common.core.domain.model.LoginUser;

import java.util.Set;

/**
 * 批 8B 紫桥（2026-11-21 板 Q4 ★案1）：自动化无人值守执行器搬进新四层后，
 * 只读 agent 的装配链（AssistantAgentFactory）仍在 legacy.core——
 * 桥面只暴露「给我一个只读 agent」，装配细节（模型选择/中间件/工具裁剪）不外泄。
 */
public interface ReadOnlyAgentGateway {

    /** 只读 agent：写类/交互类/MCP 可写工具全部摘掉，模型被诱导也无处可写（题2 甲口径） */
    ReActAgent createReadOnly(LoginUser user, String threadId, Set<String> mcpSel, Set<String> mountedMcp);
}
