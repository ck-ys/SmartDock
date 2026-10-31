package org.dromara.assistant.core;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.tool.ToolResultMessageBuilder;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 计划态写操作硬闸（题8 案1，2026-10-31 拍板；三席回修同日）。
 *
 * <p>计划卡出来以后到用户点「直接执行/改计划/取消」之前（或 30 分钟 TTL 到期），
 * 写类工具一律拒绝。两道闸：</p>
 * <ol>
 *   <li><b>onReasoning 摘工具</b>：待确认态下把写类工具从本回合工具清单（schema）里摘掉，
 *       模型根本调不到——避免"拒绝了还反复试"的死循环；</li>
 *   <li><b>onActing 兜底</b>：同批里出现 submit_plan 时本批其余写调用立刻拒（否则同批并行
 *       会钻空子）；模型若在别处拿到写工具再调，也在这里被拦下并写入 DENIED 结果。</li>
 * </ol>
 *
 * <p><b>拒绝可见性</b>：中间件自产的 ToolResult 事件框架在 acting 链上只挑 RequestStopEvent、
 * 不发布（三席反汇编实证），所以拒绝回执写入上下文之外，还投递
 * {@link AssistantPlanSignal#submitDenied}，由 enricher 转 CUSTOM {@code tool_denied} 帧，
 * 前端把对应工具步骤标成「已拒绝」并提示。</p>
 *
 * <p><b>只读判定</b>：{@code toolkit.getTool(name).isReadOnly()}——平台工具的 @Tool(readOnly)
 * 与业务 SPI 的 {@code BizToolSpec.readOnly} 都落到这个字段，单一真相；未知工具按写类处理（安全默认拒）。</p>
 */
public class AssistantPlanGateMiddleware implements HarnessRuntimeMiddleware {

    /** 计划控制/清单类工具：与只读工具一样放行（它们不改变业务数据） */
    public static final Set<String> ALWAYS_ALLOWED = Set.of(
        "submit_plan", "todo_write", "plan_enter", "plan_write", "plan_exit",
        "load_skill_through_path", "view_skill");

    /** 合成拒绝文案：给模型看（它会转述人话），也给前端工具卡展示 */
    public static final String DENY_TEXT = "已拒绝：计划阶段不执行写操作。请先让用户确认计划卡，确认后再执行这一步。";

    private final AssistantPlanSignal planSignal;
    private final Predicate<String> readOnlyResolver;
    private final String threadId;

    public AssistantPlanGateMiddleware(AssistantPlanSignal planSignal, Predicate<String> readOnlyResolver,
                                       String threadId) {
        this.planSignal = planSignal;
        this.readOnlyResolver = readOnlyResolver;
        this.threadId = threadId;
    }

    /** 链位=1（与框架默认 order 同值；本闸只拦动作与工具清单，不与流层抢最外层） */
    @Override
    public int order() {
        return 1;
    }

    /** 待确认态：把写类工具从给模型的工具清单里摘掉（治本，避免模型反复试） */
    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext ctx, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (!planSignal.isPending(threadId) || input.tools() == null || input.tools().isEmpty()) {
            return next.apply(input);
        }
        List<ToolSchema> kept = new ArrayList<>(input.tools().size());
        for (ToolSchema schema : input.tools()) {
            if (schema != null && permitted(schema.getName())) {
                kept.add(schema);
            }
        }
        if (kept.size() == input.tools().size()) {
            return next.apply(input);
        }
        return next.apply(new ReasoningInput(input.messages(), kept, input.options()));
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> calls = input.toolCalls();
        if (calls == null || calls.isEmpty()) {
            return next.apply(input);
        }
        // 同批出现 submit_plan：本批其余写调用也按待确认处理（批次开始时 pending 还是 false）
        boolean pendingNow = planSignal.isPending(threadId)
            || calls.stream().anyMatch(c -> "submit_plan".equals(c.getName()));
        if (!pendingNow) {
            return next.apply(input);
        }
        List<ToolUseBlock> allowed = new ArrayList<>();
        List<ToolUseBlock> denied = new ArrayList<>();
        for (ToolUseBlock call : calls) {
            if (permitted(call.getName())) {
                allowed.add(call);
            } else {
                denied.add(call);
            }
        }
        if (denied.isEmpty()) {
            return next.apply(input);
        }
        // 拒绝结果写进上下文（模型下一轮能看到），并投递可见性回执
        AgentState state = RuntimeContext.resolveAgentState(ctx, agent);
        String agentName = agent.getName();
        for (ToolUseBlock call : denied) {
            ToolResultBlock result = ToolResultBlock.text(DENY_TEXT)
                .withIdAndName(call.getId(), call.getName())
                .withState(ToolResultState.DENIED);
            state.contextMutable().add(
                ToolResultMessageBuilder.buildToolResultMsg(result, call, agentName));
            Map<String, Object> notice = new LinkedHashMap<>();
            notice.put("toolCallId", call.getId());
            notice.put("toolName", call.getName());
            notice.put("message", DENY_TEXT);
            planSignal.submitDenied(threadId, notice);
        }
        // 不合成事件（框架不发布中间件事件）；DENIED 已入上下文，下一轮自然收敛
        return allowed.isEmpty() ? Flux.empty() : next.apply(new ActingInput(allowed));
    }

    private boolean permitted(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return ALWAYS_ALLOWED.contains(name)
            || (readOnlyResolver != null && readOnlyResolver.test(name));
    }
}
