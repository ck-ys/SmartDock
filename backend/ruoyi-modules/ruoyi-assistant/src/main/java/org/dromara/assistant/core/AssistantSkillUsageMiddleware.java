package org.dromara.assistant.core;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import org.dromara.assistant.service.AssistantSkillUsageService;
import reactor.core.publisher.Flux;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * 技能使用统计中间件（题2 案3，2026-10-31 拍板）。
 *
 * <p><b>为什么不用官方 SkillUsageMiddleware</b>：官方那个只统计 agent 自己新建的技能
 * （provenance 限定），内置技能取用不计；而且落盘在工作区文件里，多租户共用一个文件会串账。
 * 所以这里只借它的挂钩位置（onActing），统计落库（{@code dcs_assistant_skill_usage}）。</p>
 *
 * <p><b>口径</b>：两条取用路径都算——官方 {@code load_skill_through_path}（主路径）与
 * 遗留 {@code view_skill}；只有工具真的返回成功（ToolResultState.SUCCESS）才 +1，
 * 且技能必须在本租户可见集内（先过同一道闸，否则模型瞎编一个 id 也会把统计搞脏）。</p>
 */
public class AssistantSkillUsageMiddleware implements HarnessRuntimeMiddleware {

    /** 会「取技能正文」的工具：官方主路径 + 遗留自取工具 */
    static final Set<String> SKILL_TOOLS = Set.of("load_skill_through_path", "view_skill");

    private final AssistantSkillUsageService usageService;
    private final String tenantId;
    private final int roleLevel;
    private final Map<String, String> toolGate;

    public AssistantSkillUsageMiddleware(AssistantSkillUsageService usageService, String tenantId,
                                         int roleLevel, Map<String, String> toolGate) {
        this.usageService = usageService;
        this.tenantId = tenantId;
        this.roleLevel = roleLevel;
        this.toolGate = toolGate;
    }

    @Override
    public Flux<AgentEvent> onActing(Agent agent, RuntimeContext ctx, ActingInput input,
                                     Function<ActingInput, Flux<AgentEvent>> next) {
        Map<String, String> pending = new HashMap<>();
        for (ToolUseBlock call : input.toolCalls()) {
            String skillId = skillIdOf(call.getName(), call.getInput());
            if (skillId != null && AssistantSkillGateMiddleware.visibleTo(
                skillId, roleLevel, toolGate, tenantId)) {
                pending.put(call.getId(), skillId);
            }
        }
        if (pending.isEmpty()) {
            return next.apply(input);
        }
        return next.apply(input).doOnNext(event -> {
            if (event instanceof ToolResultEndEvent end
                && end.getState() == ToolResultState.SUCCESS
                && pending.containsKey(end.getToolCallId())) {
                usageService.record(tenantId, pending.get(end.getToolCallId()));
            }
        });
    }

    /**
     * 从工具调用参数里取技能 id（纯函数，单测锚点）。
     * 官方工具参数名是 {@code skillId}，遗留 view_skill 是 {@code skill_id}；其它工具恒 null。
     */
    public static String skillIdOf(String toolName, Map<String, Object> args) {
        if (toolName == null || args == null || !SKILL_TOOLS.contains(toolName)) {
            return null;
        }
        Object raw = "view_skill".equals(toolName) ? args.get("skill_id") : args.get("skillId");
        if (raw == null) {
            raw = "view_skill".equals(toolName) ? args.get("skillId") : args.get("skill_id");
        }
        if (raw == null) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        return s.isEmpty() ? null : s;
    }
}
