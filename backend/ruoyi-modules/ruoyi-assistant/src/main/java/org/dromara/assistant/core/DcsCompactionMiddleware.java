package org.dromara.assistant.core;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import io.agentscope.core.model.Model;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsAssistantConversation;
import org.dromara.assistant.service.AssistantConversationService;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 压缩中间件（批17 Q2 自动通道）：包一层官方 {@link CompactionMiddleware}，
 * 压缩真实发生（上下文消息数收缩）时向 dcs_assistant_message 落一条
 * role=system 的「已压缩」分隔条行——前端历史重建时在该位置渲染分隔条
 * （「前面的对话打包成摘要 · 省下约 X% 空间」），用户看得见压缩发生过。
 *
 * <p>设计取舍：</p>
 * <ul>
 *   <li>分隔条内容是纯数字 JSON（前后 token），不落摘要正文也不落任何用户内容——
 *       无 PII 出网/入库新增面；摘要本体由框架写进 AgentState，随状态持久化；</li>
 *   <li>标记行落库失败只 WARN 不影响对话（压缩本身已由父类完成，条子是锦上添花）；</li>
 *   <li>身份从 RuntimeContext 解析（"t:租户:用户" + sessionId=threadId）——中间件跑在
 *       框架流线程，无 Sa-Token 上下文，与 resolver 同款口径；解析不出/找不到会话行
 *       静默跳过（会话行由 resolver 先于 agent 落库，正常路径必命中）。</li>
 * </ul>
 */
@Slf4j
public class DcsCompactionMiddleware extends CompactionMiddleware {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    private final AssistantConversationService conversationService;

    public DcsCompactionMiddleware(WorkspaceManager workspaceManager, Model model,
                                   CompactionConfig config, AssistantConversationService conversationService) {
        super(workspaceManager, model, config);
        this.conversationService = conversationService;
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext rc, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (!(agent instanceof ReActAgent react)) {
            return super.onReasoning(agent, rc, input, next);
        }
        RuntimeContext ctx = rc != null ? rc : RuntimeContext.empty();
        // 快照必须取在 Flux.defer 内（父类压缩也在 defer 的订阅阶段发生，
        // onReasoning 调用时状态尚未装载，外面取快照恒为空）
        return Flux.defer(() -> {
            List<Msg> before;
            try {
                before = List.copyOf(react.getAgentState(ctx).getContext());
            } catch (Exception e) {
                // 状态读不到=只走原生压缩，不插条
                return super.onReasoning(agent, rc, input, next);
            }
            return super.onReasoning(agent, rc, input, next)
                .doFinally(signal -> {
                    try {
                        List<Msg> after = react.getAgentState(ctx).getContext();
                        // dspro P2-5：摘要失败时框架把 "(Summarization failed: …)" 当摘要正文
                        // （上下文照样收缩）——此时不给用户落「已压缩」分隔条（承诺是假的）
                        if (after.size() < before.size() && !summaryFailed(after)) {
                            recordDivider(ctx, before, after);
                        }
                    } catch (Exception e) {
                        log.warn("[Compaction] 压缩标记行落库失败(不影响对话) thread={}: {}",
                            ctx.getSessionId(), e.getMessage());
                    }
                });
        });
    }

    /** 压缩结果首条=框架摘要消息且以失败标记开头（jar 常量池实证的降级串） */
    private static boolean summaryFailed(List<Msg> after) {
        Msg head = after.isEmpty() ? null : after.get(0);
        return head != null && io.agentscope.harness.agent.memory.compaction.ConversationCompactor.SUMMARY_MSG_NAME.equals(head.getName())
            && head.getTextContent() != null && head.getTextContent().startsWith("(Summarization failed");
    }

    /** 解析 "t:租户:用户" → 查会话行 → 落 system 分隔条（内容与前端解析口径对齐：
     *  {"type":"compaction","beforeTokens":N,"afterTokens":N}） */
    private void recordDivider(RuntimeContext ctx, List<Msg> before, List<Msg> after) {
        String rcUser = ctx.getUserId();
        String threadId = ctx.getSessionId();
        if (rcUser == null || threadId == null || !rcUser.startsWith("t:")) {
            return;
        }
        String[] parts = rcUser.split(":", 3);
        if (parts.length < 3) {
            return;
        }
        Long userId;
        try {
            userId = Long.valueOf(parts[2]);
        } catch (NumberFormatException e) {
            return;
        }
        DcsAssistantConversation conv = conversationService.conversationByThread(parts[1], threadId);
        if (conv == null) {
            log.debug("[Compaction] 未找到会话行, 跳过分隔条 tenant={} thread={}", parts[1], threadId);
            return;
        }
        String content;
        try {
            content = MAPPER.writeValueAsString(Map.of(
                "type", "compaction",
                "beforeTokens", TokenCounterUtil.calculateToken(before),
                "afterTokens", TokenCounterUtil.calculateToken(after)));
        } catch (Exception e) {
            return;
        }
        conversationService.appendSystemDivider(parts[1], conv.getId(), userId, content);
        log.info("[Compaction] 自动压缩完成并落分隔条 tenant={} thread={} {}→{} 条消息",
            parts[1], threadId, before.size(), after.size());
    }
}
