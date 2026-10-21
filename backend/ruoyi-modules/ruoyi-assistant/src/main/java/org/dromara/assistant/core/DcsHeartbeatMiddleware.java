package org.dromara.assistant.core;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.CustomEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 流层心跳中间件（稳流① Q1，2026-10-20 拍板 A①：真事件心跳，双保险的下保险）。
 *
 * <p>背景：合法事件静默（联网搜索 10-25s、模型 TTFB、自动压缩摘要期）期间 SSE 零字节——
 * 前端 180s 看门狗误判断线自动重发（撞 409 防双计费）、后端 60s 收割器有误杀风险。
 * SSE 注释帧过不了部分网关/中间层缓存，必须是「真事件」。</p>
 *
 * <p>机制：把 {@code Flux.interval} 心跳流 merge 进被包阶段的事件流——合成的
 * {@link CustomEvent}（name=dcs-heartbeat）经框架 CustomAgentEventConverter 转成
 * AGUI CUSTOM 帧（真实 SSE data 帧）出网。三路消费者：</p>
 * <ul>
 *   <li>前端 aguiClient 看门狗：任意字节到达即重置停滞计时；</li>
 *   <li>Recorder：onEvents 批首刷新 lastEventAt——心跳覆盖到的静默段（工具段/
 *       压缩段/慢首 token）收割器一律放行（③工具段豁免的事件层实现）；</li>
 *   <li>诊断：心跳计数进 run 终局日志（④埋点）。</li>
 * </ul>
 *
 * <p>挂点与去重：onAgent/onReasoning/onActing 三钩全包（覆盖压缩摘要期——DcsCompactionMiddleware
 * 的摘要在其 onReasoning 内部完成，本件 order=0 链位在其外）；同一 sessionId 同一时刻只跑
 * 一条心跳流（外层先起，内层钩直通），杜绝嵌套阶段双发。阶段结束（正常/异常/取消）即停——
 * doFinally 联动 CompletableFuture 瞬时终止 interval，不拖慢阶段完成信号。</p>
 */
@Slf4j
public class DcsHeartbeatMiddleware implements MiddlewareBase {

    /** 心跳间隔：20s=60s 收割阈值的 1/3（3 刷新余量）+ 前端 600s 看门狗的 1/30（足够喂） */
    static final long HEARTBEAT_INTERVAL_MS = 20_000L;

    /** CUSTOM 帧名（前端/Recorder 按名识别；未知名前端静默忽略）——public：persist 包计数引用 */
    public static final String HEARTBEAT_NAME = "dcs-heartbeat";

    /** 实际跳动间隔（005 测试接缝：构造注入短间隔驱动帧到达断言，生产恒 HEARTBEAT_INTERVAL_MS） */
    private final java.time.Duration interval;

    public DcsHeartbeatMiddleware() {
        this(java.time.Duration.ofMillis(HEARTBEAT_INTERVAL_MS));
    }

    /** 包内测试接缝：注替短间隔 */
    DcsHeartbeatMiddleware(java.time.Duration interval) {
        this.interval = interval;
    }

    /** 链位：order=0（默认 1 的最外层）——包住压缩中间件的 onReasoning，摘要静默期也有心跳 */
    @Override
    public int order() {
        return 0;
    }

    /**
     * 会话级去重：外层阶段（如 onAgent）已起心跳则内层钩（onReasoning/onActing）直通。
     * 键=RuntimeContext.getSessionId()（agent 会话粒度）；值=完成信号（null=占位无义）。
     * 会话结束 doFinally 移除键，异常路径由 remove 兜底。
     * <p><b>已知取舍</b>（glm53 审查 P2-7，实测不修）：注册发生在装配期（merge 决策需要），
     * 若装配后流从未被订阅（请求断在订阅前），键滞留=该会话后续阶段直通缺心跳——
     * 窗口极窄且前端 600s 看门狗+onStall 弹窗兜底用户可感知；换订阅期注册需重构
     * merge 决策时机，不值。</p>
     */
    private final Map<String, CompletableFuture<Void>> activeHeartbeats = new ConcurrentHashMap<>();

    @Override
    public Flux<AgentEvent> onAgent(Agent agent, RuntimeContext ctx, AgentInput input,
                                    java.util.function.Function<AgentInput, Flux<AgentEvent>> next) {
        return withHeartbeat(ctx, next.apply(input));
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        java.util.function.Function<ReasoningInput, Flux<AgentEvent>> next) {
        return withHeartbeat(ctx, next.apply(input));
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     java.util.function.Function<ActingInput, Flux<AgentEvent>> next) {
        return withHeartbeat(ctx, next.apply(input));
    }

    /** 心跳包装：该会话已有活跃心跳→直通；否则 merge 一条 interval 流，阶段终止即停 */
    private Flux<AgentEvent> withHeartbeat(RuntimeContext ctx, Flux<AgentEvent> source) {
        String session = ctx != null && ctx.getSessionId() != null ? ctx.getSessionId() : null;
        if (session == null) {
            // 无会话键（非 AGUI 通道/内部调用）不包——心跳只服务流式用户面
            return source;
        }
        CompletableFuture<Void> stop = new CompletableFuture<>();
        CompletableFuture<Void> existing = activeHeartbeats.putIfAbsent(session, stop);
        if (existing != null) {
            // 外层钩已在跳：本层直通，心跳随外层阶段收尾
            return source;
        }
        Flux<AgentEvent> beats = Flux.interval(interval)
            .onBackpressureDrop()
            .map(i -> heartbeat())
            .takeUntilOther(Mono.fromFuture(() -> stop));
        return source
            .doFinally(sig -> {
                activeHeartbeats.remove(session, stop);
                stop.complete(null);
            })
            .mergeWith(beats);
    }

    /** 单帧：name+ts+源标识（诊断侧可区分心跳与业务帧；包内可见供单测直断言形状） */
    static AgentEvent heartbeat() {
        return new CustomEvent(HEARTBEAT_NAME, Map.of(
            "ts", Instant.now().toString(),
            "src", "middleware"));
    }

    /** 诊断只读：当前活跃心跳会话数（收尾埋点/排障用） */
    public int activeSessionCount() {
        return activeHeartbeats.size();
    }
}
