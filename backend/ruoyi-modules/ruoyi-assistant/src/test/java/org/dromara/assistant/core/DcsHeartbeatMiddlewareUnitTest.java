package org.dromara.assistant.core;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.middleware.ReasoningInput;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 稳流① Q1（2026-10-20）：流层心跳中间件单测。
 *
 * <p>契约四条：①静默源也有帧（interval merge 后业务帧零到达期间心跳照出）；
 * ②同会话嵌套阶段不双发（外层先起、内层直通）；③阶段终止即停（doFinally 撤键，
 * 心跳不拖慢完成信号）；④帧形状=CustomEvent(name=dcs-heartbeat, value 含 ts/src)。
 * 测试接缝：构造注入 10ms 短间隔驱动①③断言，不睡 20s 墙钟。</p>
 */
@Tag("dev")
class DcsHeartbeatMiddlewareUnitTest {

    private static RuntimeContext ctx(String session) {
        return RuntimeContext.builder().sessionId(session).build();
    }

    @Test
    void silentSourceStillEmitsHeartbeatFrames() throws InterruptedException {
        DcsHeartbeatMiddleware mw = new DcsHeartbeatMiddleware(Duration.ofMillis(10));
        List<AgentEvent> seen = new CopyOnWriteArrayList<>();
        // 源=Flux.never()：合法事件静默期（工具执行/慢首 token 的流层形态）
        Disposable d = mw.onReasoning(null, ctx("hb-silent"), reasonInput(),
            in -> reactor.core.publisher.Flux.<AgentEvent>never())
            .subscribe(seen::add);
        Thread.sleep(80);
        d.dispose();
        assertThat(seen).isNotEmpty();
        assertThat(seen).allSatisfy(e -> {
            assertThat(e).isInstanceOf(CustomEvent.class);
            assertThat(((CustomEvent) e).getName()).isEqualTo(DcsHeartbeatMiddleware.HEARTBEAT_NAME);
        });
    }

    @Test
    void nestedPhasesDoNotDoubleStart() {
        DcsHeartbeatMiddleware mw = new DcsHeartbeatMiddleware(Duration.ofMillis(10));
        RuntimeContext c = ctx("hb-dedup");
        AgentEvent marker = DcsHeartbeatMiddleware.heartbeat();
        // 外层（onAgent）先包：源立即完成一个事件
        wrapOnAgent(mw, c, marker).blockLast();
        // 内层（onReasoning）同会话跟进：直通不注册第二条心跳
        mw.onReasoning(null, c, reasonInput(), in -> reactor.core.publisher.Flux.just(marker))
            .blockLast();
        assertThat(mw.activeSessionCount()).isZero();
    }

    /** onAgent 包装（外层模拟）——blockLast 驱动源完成，doFinally 撤键 */
    private static reactor.core.publisher.Flux<AgentEvent> wrapOnAgent(
        DcsHeartbeatMiddleware mw, RuntimeContext c, AgentEvent marker) {
        return mw.onAgent(null, c, new io.agentscope.core.middleware.AgentInput(List.of()),
            in -> reactor.core.publisher.Flux.just(marker));
    }

    /** ReasoningInput 三参构造（零消息/零工具/缺省选项——钩子签名只透传 input，不消费内容） */
    private static ReasoningInput reasonInput() {
        return new ReasoningInput(List.of(), List.of(), null);
    }

    @Test
    void completionReleasesSessionKeyImmediately() {
        DcsHeartbeatMiddleware mw = new DcsHeartbeatMiddleware(Duration.ofMillis(10));
        RuntimeContext c = ctx("hb-stop");
        AgentEvent marker = DcsHeartbeatMiddleware.heartbeat();
        // 源 30ms 后完成——完成信号不得被 interval 拖住（doFinally 瞬时撤停）
        long start = System.currentTimeMillis();
        mw.onReasoning(null, c, reasonInput(),
                in -> reactor.core.publisher.Flux.just(marker).delayElements(Duration.ofMillis(30)))
            .blockLast(Duration.ofSeconds(2));
        long elapsed = System.currentTimeMillis() - start;
        assertThat(elapsed).isLessThan(500);
        assertThat(mw.activeSessionCount()).isZero();
    }

    @Test
    void frameShapeCarriesNameAndDiagnostics() {
        AgentEvent e = DcsHeartbeatMiddleware.heartbeat();
        assertThat(e).isInstanceOf(CustomEvent.class);
        CustomEvent c = (CustomEvent) e;
        assertThat(c.getName()).isEqualTo("dcs-heartbeat");
        assertThat(c.getValue()).isInstanceOf(java.util.Map.class);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> v = (java.util.Map<String, Object>) c.getValue();
        assertThat(v).containsKeys("ts", "src");
        assertThat(String.valueOf(v.get("src"))).isEqualTo("middleware");
    }

    @Test
    void noSessionIdPassesThroughUnwrapped() {
        DcsHeartbeatMiddleware mw = new DcsHeartbeatMiddleware(Duration.ofMillis(10));
        AgentEvent marker = DcsHeartbeatMiddleware.heartbeat();
        // 无会话键（内部调用形态）直通：不注册心跳（activeSessionCount 恒 0）
        mw.onReasoning(null, RuntimeContext.empty(), reasonInput(),
                in -> reactor.core.publisher.Flux.just(marker))
            .blockLast();
        assertThat(mw.activeSessionCount()).isZero();
    }
}
