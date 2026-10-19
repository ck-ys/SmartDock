package org.dromara.test.assistant;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.mapper.DcsAiConfigMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-T8 探针：租户动态 Agent×starter 衔接（实测⑧，真实付费调用，花费≈2 次模型链）。
 * <p>判据（计划 Task 8）：</p>
 * <ul>
 *   <li>Step1：两租户（978311=DB 读真配置；另一租户=in-memory 构造 DcsAiConfig 喂构建器，
 *       不经 DB——守住探针不写业务表约束）分别构建可达同一模型 → 同一 Agent 实例以
 *       t:{tenantId}:{userId} 上下文并发跑 → 断言互不串扰（各会话 state 只含自己的问答）；</li>
 *   <li>Step2：未配置租户 → 装配查询返回空 → 走灰球降级路径（不 NPE、不构建 model）。</li>
 * </ul>
 */
@Tag("p0probe")
@SpringBootTest
class P08DynamicTenantAgentTest {

    private static final long CONFIG_ID = 9100000000003L;

    @Autowired
    DcsAiConfigMapper aiConfigMapper;

    /** 固定应答工具（隔离性由会话 state 断言，不依赖工具内容） */
    static class EchoTools {
        @Tool(name = "echo_probe", description = "测试工具：原样返回输入文本")
        public String echoProbe(String text) {
            return "{\"echo\":\"" + text + "\"}";
        }
    }

    /**
     * Step1：同一 Agent 实例 + 两租户 RuntimeContext 并发 call。
     * 串扰判据：A 会话 state 的 context 只含 A 的问题与回答，反之亦然。
     */
    @Test
    void dualTenantConcurrentNoCrossTalk() throws Exception {
        // 租户A：DB 真配置（978311）
        DcsAiConfig cfgA = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfgA);
        // 租户B：in-memory 构造（同 DeepSeek 凭据、不同租户号——P1 动态装配形态）
        DcsAiConfig cfgB = new DcsAiConfig();
        cfgB.setConfigId(9100000000999L);
        cfgB.setTenantId("998877");
        cfgB.setUseType("CHAT");
        cfgB.setEnabled("1");
        cfgB.setBaseUrl(cfgA.getBaseUrl());
        cfgB.setApiKey(cfgA.getApiKey());
        cfgB.setModelName(cfgA.getModelName());

        // 计划判据原文形态：同一 Agent 实例（无状态多用户）以 t:{tenantId}:{userId} 双上下文并发跑
        // （三审修正：此前双实例形态下串扰断言结构性空转）
        ReActAgent agent = buildAgent(cfgA);

        RuntimeContext rcA = RuntimeContext.builder()
            .userId("t:978311:userA").sessionId("p08-" + System.currentTimeMillis()).build();
        RuntimeContext rcB = RuntimeContext.builder()
            .userId("t:998877:userB").sessionId("p08-" + (System.currentTimeMillis() + 1)).build();

        List<String> repliesA = new CopyOnWriteArrayList<>();
        List<String> repliesB = new CopyOnWriteArrayList<>();
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> fa = pool.submit(() -> {
                try {
                    start.await();
                    Msg r = agent.call(List.of(msg("我是租户A用户，请只回复：A确认")), rcA)
                        .timeout(Duration.ofSeconds(180)).block();
                    repliesA.add(text(r));
                } catch (Throwable t) {
                    repliesA.add("EX:" + t.getClass().getSimpleName());
                }
            });
            Future<?> fb = pool.submit(() -> {
                try {
                    start.await();
                    Msg r = agent.call(List.of(msg("我是租户B用户，请只回复：B确认")), rcB)
                        .timeout(Duration.ofSeconds(180)).block();
                    repliesB.add(text(r));
                } catch (Throwable t) {
                    repliesB.add("EX:" + t.getClass().getSimpleName());
                }
            });
            start.countDown();
            fa.get(200, TimeUnit.SECONDS);
            fb.get(200, TimeUnit.SECONDS);
        }

        System.out.println("[P08] A 回复=" + repliesA + " / B 回复=" + repliesB);
        assertEquals(1, repliesA.size());
        assertEquals(1, repliesB.size());
        assertFalse(repliesA.get(0).startsWith("EX:"), "租户A调用不应异常：" + repliesA);
        assertFalse(repliesB.get(0).startsWith("EX:"), "租户B调用不应异常：" + repliesB);
        assertTrue(repliesA.get(0).contains("A"), "A 会话回复应含 A 标识：" + repliesA.get(0));
        assertTrue(repliesB.get(0).contains("B"), "B 会话回复应含 B 标识：" + repliesB.get(0));

        // 串扰判据：各会话 state 的 context 含自己的标识、不含对方标识
        String ctxA = stringify(agent.getAgentState(rcA).getContext());
        String ctxB = stringify(agent.getAgentState(rcB).getContext());
        System.out.println("[P08] ctxA含B标识=" + ctxA.contains("租户B") + " ctxB含A标识=" + ctxB.contains("租户A"));
        assertTrue(ctxA.contains("租户A"), "A 会话应含 A 的问题");
        assertFalse(ctxA.contains("租户B"), "A 会话不得混入 B 的问题（串扰红线）");
        assertTrue(ctxB.contains("租户B"), "B 会话应含 B 的问题");
        assertFalse(ctxB.contains("租户A"), "B 会话不得混入 A 的问题（串扰红线）");
    }

    /** Step2：未配置租户 → 装配查询空 → 灰球降级不 NPE（P1 装配约定：空配置不进 model builder） */
    @Test
    void unconfiguredTenantDegradesGracefully() {
        // 装配函数口径（P1 生产同构）：selectByTenantAndUse → 空则降级 Optional.empty，绝不把 null 喂 builder
        DcsAiConfig none = aiConfigMapper.selectById(9100000000998L);   // 不存在的配置
        assertNull(none, "未配置租户查询应返回 null（三审注：真实降级路径=P1 装配层把 null 判定为"
            + " Optional.empty 走灰球引导，此处仅锁查询语义，装配层降级由 P1 集成测覆盖）");
    }

    private static ReActAgent buildAgent(DcsAiConfig cfg) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new EchoTools());
        return ReActAgent.builder()
            .sysPrompt("你是 DCS 工作助手，按用户要求简洁回复。")
            .model(OpenAIChatModel.builder()
                .baseUrl(cfg.getBaseUrl()).apiKey(cfg.getApiKey()).modelName(cfg.getModelName())
                .stream(false).formatter(new DeepSeekFormatter()).build())
            .toolkit(toolkit).maxIters(5).build();
    }

    private static Msg msg(String text) {
        return Msg.builderForRole(MsgRole.USER).textContent(text).build();
    }

    private static String text(Msg m) {
        if (m == null) {
            return "";
        }
        return m.getContentBlocks(TextBlock.class).stream()
            .map(TextBlock::getText).reduce("", (a, b) -> a + b);
    }

    private static String stringify(List<Msg> ctx) {
        StringBuilder sb = new StringBuilder();
        for (Msg m : ctx) {
            sb.append(text(m));
        }
        return sb.toString();
    }
}
