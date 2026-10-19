package org.dromara.test.assistant;

import io.agentscope.core.memory.InMemoryMemory;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.spring.boot.agui.common.AguiProperties;
import org.dromara.assistant.core.AssistantReasoningGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A3 装配件 PG 实测（@SpringBootTest 连 dev 库真读写）。
 *
 * <p>判据：①JdbcAgentStateStore 落 agentscope schema（search_path 代理）写读对称
 * （坑65：autoCreateTable=false，表由 migration 预建）；②yml 定版关键开关按拍板落位；
 * ③思考闸默认关。</p>
 */
@Tag("p0probe")
@SpringBootTest
class A3StoreAssemblyTest {

    private static final String NS = "a3-assembly-test";
    private static final String SESSION = "sess-a3-1";

    @Autowired
    AgentStateStore stateStore;
    @Autowired
    AssistantReasoningGate reasoningGate;
    @Autowired
    AguiProperties aguiProperties;

    @AfterEach
    void cleanup() {
        stateStore.delete(NS, SESSION);
    }

    @Test
    void stateStoreRoundtripsOnPgAgentscopeSchema() {
        InMemoryMemory mem = new InMemoryMemory();
        mem.addMessage(new UserMessage("a3 第一句"));
        mem.addMessage(new UserMessage("a3 第二句"));
        mem.saveTo(stateStore, NS, SESSION);

        InMemoryMemory loaded = new InMemoryMemory();
        loaded.loadFrom(stateStore, NS, SESSION);
        assertEquals(2, loaded.getMessages().size(), "PG 写读对称：两条消息往返一致");
        assertEquals("a3 第一句", loaded.getMessages().get(0).getTextContent(),
            "内容保真（getTextContent 而非 toString——后者格式不保证）");
        // 再写一轮验证 upsert 覆盖（同 ns+session 键）
        loaded.addMessage(new UserMessage("a3 第三句"));
        loaded.saveTo(stateStore, NS, SESSION);
        InMemoryMemory reloaded = new InMemoryMemory();
        reloaded.loadFrom(stateStore, NS, SESSION);
        assertEquals(3, reloaded.getMessages().size(), "同键 upsert 覆盖而非追加");
    }

    @Test
    void ymlPinnedSwitchesMatchDecisions() {
        assertTrue(aguiProperties.isServerSideMemory(), "服务端记忆=按 thread 服务端管");
        assertTrue(aguiProperties.isEnableReasoning(), "yml 层放行思考数据（enricher 负责过滤）");
        assertFalse(aguiProperties.isInterruptOnDisconnect(), "断线不中断（HITL 恢复链前提）");
        assertTrue(aguiProperties.isEmitTokenUsage(), "token 用量事件=批D 计费数据源");
        assertEquals(60, aguiProperties.getSessionTimeoutMinutes(), "会话超时 60 分钟");
        assertEquals("dcs-assistant", aguiProperties.getDefaultAgentId(),
            "默认 agent 钉死 dcs-assistant");
    }

    @Test
    void reasoningGateDefaultsOff() {
        assertNotNull(reasoningGate);
        assertFalse(reasoningGate.isEnabled(), "思考闸默认关（用户拍板：默认只见思考中）");
    }
}
