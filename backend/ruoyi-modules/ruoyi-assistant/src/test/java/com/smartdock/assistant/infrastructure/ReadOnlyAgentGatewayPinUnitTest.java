package com.smartdock.assistant.infrastructure;

import com.smartdock.assistant.legacy.core.AssistantAgentFactory;
import io.agentscope.core.ReActAgent;
import org.dromara.common.core.domain.model.LoginUser;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批 8B（2026-11-21）：紫桥 {@link com.smartdock.assistant.domain.gateway.ReadOnlyAgentGateway}
 * 实现的钉子。
 *
 * <p>桥面只有一句话「给我一个只读 agent」——本类钉两件事：① 四个参数原样直传
 * （threadId 串了会话会互相污染、mountedMcp 串了失败归因会错）；② 返回的就是工厂
 * 造的那个 agent 实例（不做二次包装，行为与换根前逐位一致）。</p>
 */
@Tag("local")
class ReadOnlyAgentGatewayPinUnitTest {

    @Test
    @SuppressWarnings("unchecked")
    void 四参原样直传且返回同一实例() {
        AssistantAgentFactory factory = mock(AssistantAgentFactory.class);
        ReActAgent agent = mock(ReActAgent.class);
        LoginUser user = new LoginUser();
        Set<String> mcpSel = Set.of("weather");
        Set<String> mountedMcp = Set.of("weather");
        when(factory.createReadOnly(user, "auto-1", mcpSel, mountedMcp)).thenReturn(agent);

        ReActAgent out = new ReadOnlyAgentGatewayImpl(factory)
            .createReadOnly(user, "auto-1", mcpSel, mountedMcp);

        assertSame(agent, out, "返回工厂造的同一个实例，不二次包装");
        verify(factory).createReadOnly(user, "auto-1", mcpSel, mountedMcp);
    }
}
