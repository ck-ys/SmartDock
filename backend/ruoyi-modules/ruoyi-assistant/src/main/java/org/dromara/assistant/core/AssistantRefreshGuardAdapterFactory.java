package org.dromara.assistant.core;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.AguiAgentAdapter;
import io.agentscope.core.agui.adapter.AguiAgentAdapterFactory;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.model.RunAgentInput;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

/**
 * B 批题5「续看」桥接工厂：adapter 事件流改经 {@link AssistantRunStreamHub#bridge} 转发——
 * 原链由汇自订阅驱动（HTTP 断连不再拆链杀死 agent），出网一律订汇热流（先重放后直播）。
 *
 * <p><b>为什么需要它（2026-10-27 活体实测定案）</b>：starter 的 MVC 订阅器在
 * {@code sendEvent} 只捕获 IOException——断连后 Spring SseEmitter 状态机抛出的
 * IllegalStateException 未捕获，BaseSubscriber 收到异常即取消整链，agent 静默死亡
 * （run 终局诊断 INTERRUPTED|client-disconnect；{@code interrupt-on-disconnect: false}
 * 只关掉了 onError 回调的主动中断，管不到这条拆链——与批6-#2 注释同族，2.0.4 未根治）。
 * 本工厂从 {@code AguiAgentAdapterFactory} 扩展点（自动配置 ObjectProvider 让位）接管：
 * agent 生成本体由汇驱动，与 HTTP 连接生死解耦。</p>
 *
 * <p><b>口径</b>：无 runId 的请求（协议外）不接管原样透传；同 runId 重入
 * （断线重试/HITL resume 复用 runId）由 bridge 弃旧链换新汇。</p>
 */
@Component
@RequiredArgsConstructor
public class AssistantRefreshGuardAdapterFactory implements AguiAgentAdapterFactory {

    private final AssistantRunStreamHub runStreamHub;

    @Override
    public AguiAgentAdapter create(Agent agent, AguiAdapterConfig config) {
        return new AguiAgentAdapter(agent, config) {
            @Override
            public Flux<AguiEvent> run(RunAgentInput input, RuntimeContext runtimeContext) {
                Flux<AguiEvent> original = super.run(input, runtimeContext);
                String runId = input == null ? null : input.getRunId();
                if (runId == null || runId.isBlank()) {
                    return original;
                }
                return runStreamHub.bridge(runId, original);
            }
        };
    }
}
