package org.dromara.test.assistant;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.model.RunAgentInput;
import io.agentscope.core.agui.processor.AgentResolver;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import io.agentscope.core.agui.runtime.AguiRuntimeContextResolver;
import io.agentscope.core.message.Msg;
import io.agentscope.spring.boot.agui.common.DefaultAgentResolver;
import io.agentscope.spring.boot.agui.common.ThreadSessionManager;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-T7 探针：resume 归属鉴权（实测⑦，R-07 补排）。
 * <p>判据（计划 Task 7）：</p>
 * <ul>
 *   <li>Step1 双用户探针：用户 A 的 threadId 由用户 B resume → 记录框架默认行为。
 *       静态实锤（AguiRequestProcessor.process 字节码 28-49 行）：无自定义
 *       AguiRuntimeContextResolver bean 时 resolved=null → RuntimeContext.builder(null)
 *       .sessionId(threadId) → userId=null → 会话键=(null, threadId)——
 *       所有调用者（无论登录身份）持同 threadId 命中同一会话=越权面坐实；</li>
 *   <li>对照：显式派生不同 userId 时框架自身隔离良好（(userId, threadId) 二元组键）；</li>
 *   <li>Step2 归属断言方案（userId↔threadId 前缀比对）复测 → 拒绝。
 *       落点=自定义 AguiRuntimeContextResolver（P1 生产形态：userId 取 LoginHelper.getUserId()，
 *       threadId 规范 "{userId}:{uuid}"，前缀失配抛 SecurityException → RUN_ERROR 事件）。</li>
 * </ul>
 * <p>纯单元探针（无 Spring 上下文、零模型成本）；HTTP 层登录态链路由 T6 覆盖。</p>
 */
@Tag("p0probe")
class P07ResumeAuthzTest {

    /** 最小 Agent 实现（探针只测会话归属，不真调模型；接口多默认方法，仅补抽象项） */
    static final class DummyAgent implements Agent {
        @Override public String getAgentId() { return "p0-dummy"; }
        @Override public String getName() { return "p0-dummy"; }
        @Override public void interrupt() { }
        @Override public void interrupt(Msg msg) { }
        @Override public Mono<Msg> call(java.util.List<Msg> msgs) { return Mono.empty(); }
        @Override public Mono<Msg> call(java.util.List<Msg> msgs, java.lang.Class<?> type) {
            return Mono.empty();
        }
        @Override public Mono<Msg> call(java.util.List<Msg> msgs, com.fasterxml.jackson.databind.JsonNode extra) {
            return Mono.empty();
        }
        @Override public Flux<io.agentscope.core.agent.Event> stream(java.util.List<Msg> msgs,
                io.agentscope.core.agent.StreamOptions opts) { return Flux.empty(); }
        @Override public Flux<io.agentscope.core.agent.Event> stream(java.util.List<Msg> msgs,
                io.agentscope.core.agent.StreamOptions opts, java.lang.Class<?> type) { return Flux.empty(); }
        @SuppressWarnings("deprecation")
        @Override public Flux<io.agentscope.core.agent.Event> stream(java.util.List<Msg> msgs,
                io.agentscope.core.agent.StreamOptions opts, com.fasterxml.jackson.databind.JsonNode extra) {
            return Flux.empty();
        }
        @Override public Mono<Void> observe(Msg msg) { return Mono.empty(); }
        @Override public Mono<Void> observe(java.util.List<Msg> msgs) { return Mono.empty(); }
    }

    private static DefaultAgentResolver buildResolver(AguiAgentRegistry registry, ThreadSessionManager sm) {
        // 与 AgentscopeAguiMvcAutoConfiguration 默认装配同构：serverSideMemory=true
        return DefaultAgentResolver.builder()
            .registry(registry).sessionManager(sm).serverSideMemory(true).build();
    }

    /** Step1：默认路径（无 resolver bean → userId=null）——A/B 任意调用者同 threadId 接管同一会话 */
    @Test
    void defaultPathSharesSessionAcrossCallers() {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        registry.registerFactory("p0", DummyAgent::new);
        ThreadSessionManager sm = new ThreadSessionManager(64, 30);
        AgentResolver resolver = buildResolver(registry, sm);

        // 复刻 AguiRequestProcessor 默认派生：resolved=null → builder(null) → getUserId()=null
        RuntimeContext defaultCtx = RuntimeContext.builder(
                (RuntimeContext) null).sessionId("th-t7").build();
        String derivedUserId = defaultCtx.getUserId();
        System.out.println("[P07] 默认派生 userId = " + derivedUserId + "（null=全调用者同一命名空间）");

        Agent agentA = resolver.resolveAgent("p0", "th-t7", derivedUserId);
        Agent agentB = resolver.resolveAgent("p0", "th-t7", derivedUserId);
        assertSame(agentA, agentB,
            "默认路径 userId=null：任何调用者持同 threadId 命中同一 agent 会话（越权面坐实）");
        assertEquals(1, sm.getSessionCount(), "应只有一个会话（未按人隔离）");
    }

    /** 对照：显式派生 userId 时框架自身隔离良好（越权根因=派生层缺位，非框架无能力） */
    @Test
    void explicitUserIdsGetIsolatedSessions() {
        AguiAgentRegistry registry = new AguiAgentRegistry();
        registry.registerFactory("p0", DummyAgent::new);
        ThreadSessionManager sm = new ThreadSessionManager(64, 30);
        AgentResolver resolver = buildResolver(registry, sm);

        Agent agentA = resolver.resolveAgent("p0", "th-t7", "userA");
        Agent agentB = resolver.resolveAgent("p0", "th-t7", "userB");
        assertNotSame(agentA, agentB, "显式 userId 下同人同 thread 各自会话（框架 (userId,threadId) 键隔离）");
        assertEquals(2, sm.getSessionCount());
    }

    /**
     * Step2：归属断言方案（userId↔threadId 前缀比对）——P1 生产落点原型。
     * <p>生产装配：注册为 AguiRuntimeContextResolver bean（starter 的 ObjectProvider 自动拾取，
     * AguiMvcController 构造时注入 AguiRequestProcessor）；userId 从 sa-token 登录态取。</p>
     */
    static final class OwnershipPrefixResolver implements AguiRuntimeContextResolver {
        @Override
        public RuntimeContext resolve(AguiRuntimeContextRequest<?> req) {
            // 探针用头模拟认证身份；生产=LoginHelper.getUserId()（无登录态时抛 NotLoginException）
            String authUser = req.firstHeader("X-Test-User");
            if (authUser == null || authUser.isBlank()) {
                throw new SecurityException("未认证调用（生产形态=NotLoginException）");
            }
            String threadId = req.getInput().getThreadId();
            // threadId 规范 "{userId}:{uuid}"：前缀失配=resume 他人会话 → 拒绝
            if (threadId == null || !threadId.startsWith(authUser + ":")) {
                throw new SecurityException("会话归属校验失败：threadId=" + threadId
                    + " 不属于当前用户（前缀比对失配）");
            }
            return RuntimeContext.builder().userId(authUser).build();
        }
    }

    private static AguiRuntimeContextRequest<?> requestAs(String user, String threadId) {
        return AguiRuntimeContextRequest.builder()
            .input(RunAgentInput.builder().threadId(threadId).runId("r-" + System.nanoTime()).build())
            .method("POST").path("/agui/run")
            .headers(java.util.Map.of("X-Test-User", java.util.List.of(user)))
            .build();
    }

    /** Step2 复测：A 正常持有；B resume A 的 thread → 拒绝；未认证 → 拒绝 */
    @Test
    void ownershipPrefixGuardRejectsForeignResume() {
        OwnershipPrefixResolver guard = new OwnershipPrefixResolver();

        // 本人 resume 本人线程：放行且 userId 正确注入（后续会话键=(userA,threadId) 天然隔离）
        RuntimeContext rcA = guard.resolve(requestAs("userA", "userA:abc-123"));
        assertEquals("userA", rcA.getUserId());

        // B 冒用 A 的 threadId：前缀失配 → 拒绝（生产形态下 RUN_ERROR 事件返回，不接触会话）
        SecurityException ex = assertThrows(SecurityException.class,
            () -> guard.resolve(requestAs("userB", "userA:abc-123")));
        System.out.println("[P07] B resume A 线程被拒：" + ex.getMessage());
        assertTrue(ex.getMessage().contains("不属于当前用户"));

        // 无认证头：拒绝
        assertThrows(SecurityException.class, () -> guard.resolve(
            AguiRuntimeContextRequest.builder()
                .input(RunAgentInput.builder().threadId("userA:abc-123").runId("r-x").build())
                .method("POST").path("/agui/run").build()));
    }
}
