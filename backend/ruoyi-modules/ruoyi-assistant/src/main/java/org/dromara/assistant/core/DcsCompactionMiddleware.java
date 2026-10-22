package org.dromara.assistant.core;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.harness.agent.middleware.CompactionMiddleware;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsAssistantConversation;
import org.dromara.assistant.service.AssistantConversationService;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 压缩中间件（批A-1/A-2，2026-10-22 拍板 Q6/Q7/Q9）：包一层官方
 * {@link CompactionMiddleware}，并在前后补三件事：
 *
 * <ol>
 *   <li><b>微压缩（Q6-A）</b>：上下文到达自动触发线 9 成时，先把最老的几组工具结果
 *       内容清成占位符（保留最近 5 组，省不够 256 token 不算数，图片/音视频/读取类
 *       结果不碰）——常常清完就不必再走全量摘要；</li>
 *   <li><b>双熔断（Q7-A）</b>：连续 3 次摘要失败、或压缩后 3 个推理步内又满且连发 3 次，
 *       停用自动压缩并落一条「压缩已暂停」提示行（防反复烧钱/打转）；</li>
 *   <li><b>记录与分隔条（Q9-A）</b>：压缩真实发生时落 dcs_assistant_compaction 一行
 *       （前后 token/消息数 + prev_id 边界链），同时落 role=system 分隔条供前端渲染。</li>
 * </ol>
 *
 * <p>分隔条内容只含数字/固定文案，不落摘要正文与用户内容（PII 面不变）。落库失败只
 * WARN 不影响对话。身份从 RuntimeContext 解析（"t:租户:用户" + sessionId=threadId）。</p>
 */
@Slf4j
public class DcsCompactionMiddleware extends CompactionMiddleware {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /** 快速回填判定：压缩后小于该推理步数又触顶 = 一次快速回填（ZCode 同值 3） */
    private static final int RAPID_REFILL_STEP_THRESHOLD = 3;
    /** 连续失败/快速回填的封禁阈值（ZCode MAX_CONSECUTIVE_AUTOCOMPACT_FAILURES=3） */
    private static final int BREAKER_THRESHOLD = 3;
    private static final java.util.Set<String> MEDIA_BLOCKS =
        java.util.Set.of("ImageBlock", "AudioBlock", "VideoBlock", "DataBlock");

    private final AssistantConversationService conversationService;
    private final int triggerTokens;

    /** 上次压缩以来的推理步数（快速回填判定）。初值=阈值：首压不算 rapid
     *  （二审 P2：恢复超大会话首步即触线时不能误记一次快速回填） */
    private int stepsSinceCompact = RAPID_REFILL_STEP_THRESHOLD;
    /** 连续快速回填次数 */
    private int consecutiveRapidRefills;
    /** 连续摘要失败次数 */
    private int consecutiveFailures;
    /** 熔断后停用自动压缩（本 agent 实例生命周期内） */
    private boolean blocked;

    public DcsCompactionMiddleware(WorkspaceManager workspaceManager, Model model,
                                   CompactionConfig config, AssistantConversationService conversationService) {
        super(workspaceManager, model, config);
        this.conversationService = conversationService;
        this.triggerTokens = config.getTriggerTokens();
    }

    @Override
    public Flux<AgentEvent> onReasoning(Agent agent, RuntimeContext rc, ReasoningInput input,
                                        Function<ReasoningInput, Flux<AgentEvent>> next) {
        if (!(agent instanceof ReActAgent react)) {
            return super.onReasoning(agent, rc, input, next);
        }
        RuntimeContext ctx = rc != null ? rc : RuntimeContext.empty();
        // 快照必须取在 Flux.defer 内（父类压缩也在 defer 的订阅阶段发生，
        // onReasoning 调用时状态尚未装载，外面取快照恒为空）。before 取在微压缩之前——
        // 审计口径=压缩前原始占用（含随后被微压缩清掉的工具结果，合并计量）
        return Flux.defer(() -> {
            if (blocked) {
                // 熔断后不再尝试压缩（上下文继续增长由供应商窗口兜底报错，
                // 但不再反复烧摘要成本）；用户侧已收到暂停提示行
                return next.apply(input);
            }
            AgentState state;
            List<Msg> before;
            try {
                state = react.getAgentState(ctx);
                before = List.copyOf(state.getContext());
            } catch (Exception e) {
                // 状态读不到=只走原生压缩，不插条
                return super.onReasoning(agent, rc, input, next);
            }
            // ① 微压缩：先清旧工具结果，常常清完就不必全量摘要
            MicrocompactResult micro = MicrocompactResult.NONE;
            try {
                micro = microcompact(state);
                if (micro.savedTokens() > 0) {
                    log.info("[Compaction] 微压缩清理旧工具结果 thread={} 省 {} token",
                        ctx.getSessionId(), micro.savedTokens());
                }
            } catch (Exception e) {
                log.warn("[Compaction] 微压缩失败(不影响对话) thread={}: {}",
                    ctx.getSessionId(), e.getMessage());
            }
            stepsSinceCompact++;
            // 三审 P1-2 回修：框架在进入中间件链之前已把 input.messages() 拍成快照
            // （ReActAgent$CallExecution 先 prependSystemMessage 再 new ReasoningInput），
            // 光改 AgentState 本次调用仍带全量旧工具结果——按消息 id 把清理结果同步进本次输入
            ReasoningInput effectiveInput = withClearedMessages(input, micro.replacements());
            return super.onReasoning(agent, rc, effectiveInput, next)
                .doFinally(signal -> {
                    try {
                        List<Msg> after = react.getAgentState(ctx).getContext();
                        // 二审 P2 回修：判定压缩发生=出现新的摘要消息（按 id 区别于旧摘要）。
                        // 原先只比条数，cutoff=1 时前后同为 3 条 → 漏分隔条/审计且绕过快速回填计数
                        Msg head = after.isEmpty() ? null : after.get(0);
                        boolean compacted = head != null
                            && ConversationCompactor.SUMMARY_MSG_NAME.equals(head.getName())
                            && before.stream().noneMatch((m) ->
                                m.getId() != null && m.getId().equals(head.getId()));
                        if (compacted) {
                            if (summaryFailed(after)) {
                                // 摘要失败：上下文照样收缩但承诺是假的——只落 FAILED 审计行
                                // 与「本次没压成」提示行（用户得知道模型可能忘了较早内容），
                                // 不落成功分隔条
                                consecutiveFailures++;
                                log.warn("[Compaction] 摘要失败 {}/{} thread={}",
                                    consecutiveFailures, BREAKER_THRESHOLD, ctx.getSessionId());
                                recordRecord(ctx, before, after, "FAILED", "SUMMARY_FAILED", null);
                                recordNotice(ctx, "compaction_failed", "summary_failed");
                                if (consecutiveFailures >= BREAKER_THRESHOLD) {
                                    tripBreaker(ctx, "summary_failed");
                                }
                            } else {
                                consecutiveFailures = 0;
                                boolean rapid = stepsSinceCompact > 0 && stepsSinceCompact < RAPID_REFILL_STEP_THRESHOLD;
                                if (rapid) {
                                    consecutiveRapidRefills++;
                                } else {
                                    consecutiveRapidRefills = 0;
                                }
                                stepsSinceCompact = 0;
                                recordDivider(ctx, before, after);
                                if (consecutiveRapidRefills >= BREAKER_THRESHOLD) {
                                    tripBreaker(ctx, "rapid_refill");
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("[Compaction] 压缩标记行落库失败(不影响对话) thread={}: {}",
                            ctx.getSessionId(), e.getMessage());
                    }
                });
        });
    }

    /**
     * 压缩结果首条是否为失败的摘要占位。2.0.4 字节码实证两类失败形态：
     * 摘要流空 → {@code (Summary unavailable)}（常量 #343）；摘要调用异常 →
     * {@code (Summarization failed: …)}（#977，经 lambda$compactIfNeeded$5 拼接）；
     * 且两者都会被摘要模板 {@code "Here is a summary of the conversation to date:\n\n"}（#966）
     * 包在最前面——所以必须用 contains，startsWith 判不中（三审 P1-1 回修）。
     */
    static boolean summaryFailed(List<Msg> after) {
        Msg head = after.isEmpty() ? null : after.get(0);
        if (head == null || !ConversationCompactor.SUMMARY_MSG_NAME.equals(head.getName())) {
            return false;
        }
        String text = head.getTextContent();
        return text != null
            && (text.contains("(Summary unavailable)") || text.contains("(Summarization failed"));
    }

    /** 微压缩结果：省下的 token + 被替换成占位符的新消息（id 与原消息相同，供输入快照同款替换） */
    record MicrocompactResult(int savedTokens, List<Msg> replacements) {
        static final MicrocompactResult NONE = new MicrocompactResult(0, List.of());
    }

    /**
     * 微压缩：到达自动触发线 9 成时，清掉最老的工具结果组（保留最近 5 组）。
     * 直接改 AgentState 上下文（框架随后按状态持久化）；省不够 256 token 原样回滚。
     *
     * @return 省下的 token 与替换后的消息；未触发/无收益返回 {@link MicrocompactResult#NONE}
     */
    MicrocompactResult microcompact(AgentState state) {
        List<Msg> context = state.contextMutable();
        int total = TokenCounterUtil.calculateToken(context);
        if (total < AssistantCompactionPolicy.microcompactThreshold(triggerTokens)) {
            return MicrocompactResult.NONE;
        }
        // 按「assistant 带工具调用 → 其后 tool 消息」分组，与 ZCode 的轮次分组同构
        List<List<Integer>> groups = new ArrayList<>();
        List<Integer> current = null;
        for (int i = 0; i < context.size(); i++) {
            Msg m = context.get(i);
            if (m.getRole() == MsgRole.ASSISTANT && hasToolUse(m)) {
                current = new ArrayList<>();
                groups.add(current);
                continue;
            }
            if (m.getRole() == MsgRole.TOOL) {
                if (current == null) {
                    current = new ArrayList<>();
                    groups.add(current);
                }
                current.add(i);
            }
        }
        int clearGroups = Math.max(0, groups.size() - AssistantCompactionPolicy.MICROCOMPACT_KEEP_GROUPS);
        if (clearGroups == 0) {
            return MicrocompactResult.NONE;
        }
        List<Integer> clearedIndexes = new ArrayList<>();
        List<Msg> originals = new ArrayList<>();
        List<Msg> replacements = new ArrayList<>();
        for (int g = 0; g < clearGroups; g++) {
            for (int idx : groups.get(g)) {
                Msg m = context.get(idx);
                List<ContentBlock> replaced = clearToolResults(m);
                if (replaced != null) {
                    Msg cleared = rebuild(m, replaced);
                    originals.add(m);
                    clearedIndexes.add(idx);
                    replacements.add(cleared);
                    context.set(idx, cleared);
                }
            }
        }
        if (clearedIndexes.isEmpty()) {
            return MicrocompactResult.NONE;
        }
        int saved = total - TokenCounterUtil.calculateToken(context);
        if (saved < AssistantCompactionPolicy.MICROCOMPACT_MIN_SAVINGS) {
            // 省不够 256 token 不算数：原样回滚（状态与输入都不留痕）
            for (int k = 0; k < clearedIndexes.size(); k++) {
                context.set(clearedIndexes.get(k), originals.get(k));
            }
            return MicrocompactResult.NONE;
        }
        return new MicrocompactResult(saved, replacements);
    }

    /**
     * 把微压缩的替换结果同步进本次调用的输入快照（三审 P1-2 回修）。
     * 按消息 id 匹配：只动被清理的消息，输入里其余内容（system 头/其他中间件注入）原样保留；
     * id 缺失或无匹配时不改输入——本次调用退化为旧行为，状态清理下次调用仍生效。
     */
    static ReasoningInput withClearedMessages(ReasoningInput input, List<Msg> replacements) {
        if (replacements == null || replacements.isEmpty()) {
            return input;
        }
        java.util.Map<String, Msg> byId = new java.util.HashMap<>();
        for (Msg m : replacements) {
            if (m.getId() != null) {
                byId.put(m.getId(), m);
            }
        }
        if (byId.isEmpty()) {
            return input;
        }
        List<Msg> refreshed = new ArrayList<>(input.messages().size());
        boolean changed = false;
        for (Msg m : input.messages()) {
            Msg rep = m.getId() == null ? null : byId.get(m.getId());
            if (rep != null) {
                refreshed.add(rep);
                changed = true;
            } else {
                refreshed.add(m);
            }
        }
        return changed
            ? new ReasoningInput(List.copyOf(refreshed), input.tools(), input.options())
            : input;
    }

    private static boolean hasToolUse(Msg m) {
        return m.getContent().stream().anyMatch(b -> b instanceof ToolUseBlock);
    }

    /** 返回替换后的内容块列表；不可清理/无变化返回 null */
    private static List<ContentBlock> clearToolResults(Msg m) {
        List<ContentBlock> out = new ArrayList<>(m.getContent().size());
        boolean changed = false;
        for (ContentBlock block : m.getContent()) {
            if (!(block instanceof ToolResultBlock tr)) {
                out.add(block);
                continue;
            }
            String name = tr.getName();
            if (name != null && AssistantCompactionPolicy.MICROCOMPACT_EXCLUDED_TOOLS.contains(name)) {
                out.add(block);
                continue;
            }
            if (hasMedia(tr.getOutput())) {
                out.add(block);
                continue;
            }
            if (isAlreadyCleared(tr)) {
                out.add(block);
                continue;
            }
            out.add(new ToolResultBlock(tr.getId(), tr.getName(),
                List.of(TextBlock.builder().text(AssistantCompactionPolicy.MICROCOMPACT_CLEARED_TEXT).build()),
                // 二审 P2：保住原 metadata/state（悬挂/错误标记不因清理丢失）
                tr.getMetadata(), tr.getState()));
            changed = true;
        }
        return changed ? out : null;
    }

    private static boolean hasMedia(List<ContentBlock> blocks) {
        return blocks != null && blocks.stream()
            .anyMatch(b -> b != null && MEDIA_BLOCKS.contains(b.getClass().getSimpleName()));
    }

    private static boolean isAlreadyCleared(ToolResultBlock tr) {
        List<ContentBlock> output = tr.getOutput();
        return output != null && output.size() == 1
            && output.get(0) instanceof TextBlock tb
            && AssistantCompactionPolicy.MICROCOMPACT_CLEARED_TEXT.equals(tb.getText());
    }

    private static Msg rebuild(Msg m, List<ContentBlock> content) {
        return Msg.builder()
            .id(m.getId())
            .name(m.getName())
            .role(m.getRole())
            .content(content)
            .metadata(m.getMetadata())
            .timestamp(m.getTimestamp())
            .build();
    }

    /** 熔断：停用压缩并落一条暂停提示行（只落一次） */
    private void tripBreaker(RuntimeContext ctx, String reason) {
        blocked = true;
        log.warn("[Compaction] 自动压缩已熔断 thread={} reason={} rapidRefills={} failures={}",
            ctx.getSessionId(), reason, consecutiveRapidRefills, consecutiveFailures);
        recordNotice(ctx, "compaction_blocked", reason);
    }

    /** 落一条提示型 system 行（compaction_failed=单次没压成 / compaction_blocked=已熔断）；
     *  前端按 type 渲染人话警示条；失败只 WARN 不影响对话 */
    private void recordNotice(RuntimeContext ctx, String type, String reason) {
        try {
            CtxIdentity id = resolve(ctx);
            if (id == null) {
                return;
            }
            String content = MAPPER.writeValueAsString(Map.of("type", type, "reason", reason));
            conversationService.appendSystemDivider(id.tenantId(), id.conversationId(), id.userId(), content);
        } catch (Exception e) {
            log.warn("[Compaction] 提示行落库失败(不影响对话) thread={}: {}",
                ctx.getSessionId(), e.getMessage());
        }
    }

    /** 解析 "t:租户:用户" → 查会话行 → 落 system 分隔条 + 压缩记录 */
    private void recordDivider(RuntimeContext ctx, List<Msg> before, List<Msg> after) {
        try {
            CtxIdentity id = resolve(ctx);
            if (id == null) {
                return;
            }
            int beforeTokens = TokenCounterUtil.calculateToken(before);
            int afterTokens = TokenCounterUtil.calculateToken(after);
            String content = MAPPER.writeValueAsString(Map.of(
                "type", "compaction",
                "beforeTokens", beforeTokens,
                "afterTokens", afterTokens));
            conversationService.appendSystemDivider(id.tenantId(), id.conversationId(), id.userId(), content);
            recordRecord(ctx, id, before, after, beforeTokens, afterTokens,
                "SUCCESS", "AUTO_THRESHOLD", extractSummary(after));
            log.info("[Compaction] 自动压缩完成并落分隔条 tenant={} thread={} {}→{} 条消息",
                id.tenantId(), ctx.getSessionId(), before.size(), after.size());
        } catch (Exception e) {
            log.warn("[Compaction] 分隔条落库失败(不影响对话) thread={}: {}",
                ctx.getSessionId(), e.getMessage());
        }
    }

    /** 只落审计记录（不落分隔条）：摘要失败等「上下文收缩但结果不可信」的场景 */
    private void recordRecord(RuntimeContext ctx, List<Msg> before, List<Msg> after,
                              String status, String reason, String summary) {
        recordRecord(ctx, null, before, after,
            TokenCounterUtil.calculateToken(before), TokenCounterUtil.calculateToken(after),
            status, reason, summary);
    }

    private void recordRecord(RuntimeContext ctx, CtxIdentity known, List<Msg> before, List<Msg> after,
                              int beforeTokens, int afterTokens,
                              String status, String reason, String summary) {
        try {
            CtxIdentity id = known != null ? known : resolve(ctx);
            if (id == null) {
                return;
            }
            conversationService.appendCompactionRecord(id.tenantId(), id.conversationId(), id.userId(),
                ctx.getSessionId(), beforeTokens, afterTokens, before.size(), after.size(),
                status, reason, summary);
        } catch (Exception e) {
            log.warn("[Compaction] 审计记录落库失败(不影响对话) thread={}: {}",
                ctx.getSessionId(), e.getMessage());
        }
    }

    /** 摘要正文=压缩后首条框架摘要消息；剥 2.0.4 模板前缀（#966），失败占位不落审计表 */
    static String extractSummary(List<Msg> after) {
        Msg head = after.isEmpty() ? null : after.get(0);
        if (head == null || !ConversationCompactor.SUMMARY_MSG_NAME.equals(head.getName())) {
            return null;
        }
        String text = head.getTextContent();
        if (text == null || summaryFailed(after)) {
            return null;
        }
        String wrapper = "Here is a summary of the conversation to date:\n\n";
        return text.startsWith(wrapper) ? text.substring(wrapper.length()) : text;
    }

    private CtxIdentity resolve(RuntimeContext ctx) {
        String rcUser = ctx.getUserId();
        String threadId = ctx.getSessionId();
        if (rcUser == null || threadId == null || !rcUser.startsWith("t:")) {
            return null;
        }
        String[] parts = rcUser.split(":", 3);
        if (parts.length < 3) {
            return null;
        }
        Long userId;
        try {
            userId = Long.valueOf(parts[2]);
        } catch (NumberFormatException e) {
            return null;
        }
        DcsAssistantConversation conv = conversationService.conversationByThread(parts[1], threadId);
        if (conv == null) {
            log.debug("[Compaction] 未找到会话行, 跳过落库 tenant={} thread={}", parts[1], threadId);
            return null;
        }
        return new CtxIdentity(parts[1], conv.getId(), userId);
    }

    private record CtxIdentity(String tenantId, Long conversationId, Long userId) {
    }
}
