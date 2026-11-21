package com.smartdock.assistant.infrastructure;

import com.smartdock.assistant.domain.gateway.ReadOnlyAgentGateway;
import com.smartdock.assistant.legacy.core.AssistantAgentFactory;
import io.agentscope.core.ReActAgent;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.model.LoginUser;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 批 8B 紫桥实现：只读 agent 装配链还在 legacy.core，桥面收窄为一个方法直传。
 */
@Component
@RequiredArgsConstructor
public class ReadOnlyAgentGatewayImpl implements ReadOnlyAgentGateway {

    private final AssistantAgentFactory agentFactory;

    @Override
    public ReActAgent createReadOnly(LoginUser user, String threadId, Set<String> mcpSel, Set<String> mountedMcp) {
        return agentFactory.createReadOnly(user, threadId, mcpSel, mountedMcp);
    }
}
