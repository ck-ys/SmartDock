package org.dromara.assistant.service;

import io.agentscope.core.model.Model;
import io.agentscope.core.state.AgentState;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ConversationCompactor;
import io.agentscope.harness.agent.memory.compaction.TokenCounterUtil;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.core.AssistantCompactionAssets;
import org.dromara.assistant.domain.DcsAssistantConversation;
import org.dromara.assistant.domain.DcsAssistantMessage;
import org.dromara.assistant.core.AssistantAgentFactory;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 手动压缩服务（批17 Q2 手动通道，/压缩 与环卡「打包前文」的数据面）。
 *
 * <p>链路：stateStore 直读 AgentState → ConversationCompactor.compactIfNeeded
 * （与自动通道同引擎同模板）→ 新上下文写回 stateStore → 失效缓存 agent
 * （ThreadSessionManager——下次 /agui/run 从库重载已压缩状态，防缓存旧态覆盖）
 * → 落 system 分隔条行 → 返回前后 token（TokenCounterUtil 同口径）。</p>
 *
 * <p>摘要模型走 {@link AssistantModelFactory#buildModel}（含计费装饰前的原始模型，
 * 与主对话同厂商同配置）——P14 探针同款配方；压缩是帮用户省钱的动作，摘要调用
 * 走主模型计费链（诚实口径：压缩本身也产生一次小成本，费率与普通轮一致）。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantCompactionService {

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();
    /** 手动压缩的模型调用上限（超长会话摘要生成慢，120s 与 P14 探针同界） */
    private static final Duration COMPACT_TIMEOUT = Duration.ofSeconds(120);
    /** 太短不值得压的消息数下限（不到 6 条≈3 轮，摘要成本大于收益） */
    private static final int MIN_MESSAGES = 6;

    private final AgentStateStore stateStore;
    private final AssistantAgentFactory agentFactory;
    private final AssistantConversationService conversationService;
    private final AssistantConfigService configService;
    private final WorkspaceManager workspaceManager;
    private final io.agentscope.spring.boot.agui.common.ThreadSessionManager threadSessionManager;
    /** 工作目录（同 bean 的目录，见 AssistantWorkspaceConfig；此处只读兜底阈值用） */
    @Value("${dcs.assistant.compaction.min-trigger-tokens:4000}")
    private int minTriggerTokens;

    public record CompactResult(boolean compacted, String reason,
                                Integer beforeTokens, Integer afterTokens, Integer summarizedTurns) {
        static CompactResult skip(String reason) {
            return new CompactResult(false, reason, null, null, null);
        }
    }

    public CompactResult compact(String tenantId, Long userId, String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return CompactResult.skip("no_thread");
        }
        DcsAssistantConversation conv = conversationService.conversationByThread(tenantId, threadId);
        if (conv == null || !conv.getUserId().equals(userId)) {
            return CompactResult.skip("no_conversation");
        }
        // dspro P2-9 回修：归档会话=只读回放，不给压（与 ensureConversation 的 ARCHIVED 403 同口径）
        if ("ARCHIVED".equals(conv.getStatus())) {
            return CompactResult.skip("archived");
        }
        // 在途轮守卫：正在生成时压缩会与 enricher 的状态回写赛跑（saveStateToSession
        // 覆盖我们写回的压缩态），前端 sending 守卫之外的后端二道闸
        if (conversationService.hasRunningRound(tenantId, userId, threadId)) {
            throw new ServiceException("正在生成回答，请等这轮结束后再压缩", 409);
        }
        // dspro P2-2 回修：同会话压缩互斥（JVM 级——单机部署；双标签页/连点防双倍摘要成本+双分隔条）
        String lockKey = tenantId + ":" + threadId;
        if (!IN_FLIGHT.add(lockKey)) {
            return CompactResult.skip("busy");
        }
        try {
        String rcUserId = "t:" + tenantId + ":" + userId;
        Optional<AgentState> loaded = stateStore.get(rcUserId, threadId, "agent_state", AgentState.class);
        if (loaded.isEmpty()) {
            return CompactResult.skip("no_state");
        }
        AgentState state = loaded.get();
        // 状态快照是滑动窗（断点恢复用，只留最近几条），不能当压缩输入——压缩的
        // 全量上下文以消息表为准（与会话页历史同源，id 升序=时间序），快照仅用于写回。
        List<io.agentscope.core.message.Msg> context = new ArrayList<>();
        for (DcsAssistantMessage m : conversationService.listMessages(tenantId, userId, conv.getId(), 400, 0)) {
            if ("user".equals(m.getRole())) {
                context.add(io.agentscope.core.message.Msg.builder()
                    .role(io.agentscope.core.message.MsgRole.USER).textContent(m.getContent()).build());
            } else if ("assistant".equals(m.getRole())) {
                context.add(io.agentscope.core.message.Msg.builder()
                    .role(io.agentscope.core.message.MsgRole.ASSISTANT).textContent(m.getContent()).build());
            }
        }
        if (context.size() < MIN_MESSAGES) {
            return CompactResult.skip("too_short");
        }
        int beforeTokens = TokenCounterUtil.calculateToken(context);

        AssistantConfigService.EffectiveConfig cfg = configService.effective(tenantId);
        int trigger = Math.max(minTriggerTokens, cfg.compactionTriggerTokens());
        // 已在阈值以下=没有压缩收益（自动通道也还没到触发线）
        if (beforeTokens < trigger) {
            return CompactResult.skip("below_threshold");
        }

        // dspro P0-1 回修：摘要模型走与主链同源的装饰层（PII 脱敏+计费入账+钥匙池上报），
        // 不再用裸 OpenAIChatModel（明文出网+免费白嫖的合规/成本双缺口）
        Model model = agentFactory.buildCompactionModel(tenantId, userId, threadId);
        ConversationCompactor compactor = new ConversationCompactor(model,
            new MemoryFlushManager(workspaceManager, model));
        CompactionConfig compCfg = AssistantCompactionAssets.buildConfig(trigger);
        // RuntimeContext 身份与运行时同款（MemoryFlushManager 卸载路径按 rc 归档）
        io.agentscope.core.agent.RuntimeContext rc = io.agentscope.core.agent.RuntimeContext.builder()
            .userId(rcUserId).sessionId(threadId).build();
        Optional<List<io.agentscope.core.message.Msg>> compacted = Mono.from(compactor
                .compactIfNeeded(rc, context, compCfg, rcUserId, threadId))
            .block(COMPACT_TIMEOUT);
        if (compacted == null || compacted.isEmpty()) {
            // Optional.empty=框架判定无需压/失败降级——对用户口径统一「暂不需要」
            return CompactResult.skip("no_gain");
        }
        List<io.agentscope.core.message.Msg> newContext = compacted.get();
        // dspro P2-5 回修：摘要生成失败时框架把失败标记串当摘要正文（上下文照样收缩）——
        // 此时"关键结论都在摘要里"的承诺是假的，按失败口径返回，不落分隔条
        io.agentscope.core.message.Msg head = newContext.isEmpty() ? null : newContext.get(0);
        if (head != null && ConversationCompactor.SUMMARY_MSG_NAME.equals(head.getName())
            && head.getTextContent() != null && head.getTextContent().startsWith("(Summarization failed")) {
            return CompactResult.skip("summary_failed");
        }
        if (newContext.size() >= context.size()) {
            return CompactResult.skip("no_gain");
        }
        int afterTokens = TokenCounterUtil.calculateToken(newContext);

        // 状态原地更新并写回（contextMutable 清空重灌，其余字段保留），随后失效缓存
        // agent——缓存实例持有自己的 stateCache，不失效=下轮把旧上下文写回去
        state.contextMutable().clear();
        state.contextMutable().addAll(newContext);
        stateStore.save(rcUserId, threadId, "agent_state", state);
        boolean evicted = threadSessionManager.removeSession(rcUserId, threadId);
        if (evicted) {
            log.info("[Compact] 手动压缩完成, 缓存 agent 已失效重建 thread={}", threadId);
        }

        int summarizedTurns = (context.size() - newContext.size()) / 2;
        String content;
        try {
            content = MAPPER.writeValueAsString(Map.of(
                "type", "compaction",
                "beforeTokens", beforeTokens,
                "afterTokens", afterTokens));
        } catch (Exception e) {
            return new CompactResult(true, null, beforeTokens, afterTokens, summarizedTurns);
        }
        conversationService.appendSystemDivider(tenantId, conv.getId(), userId, content);
        log.info("[Compact] 手动压缩完成 tenant={} thread={} tokens {}→{} ({}→{} 条消息)",
            tenantId, threadId, beforeTokens, afterTokens, context.size(), newContext.size());
        return new CompactResult(true, null, beforeTokens, afterTokens, summarizedTurns);
        } finally {
            // 无论成功/跳过/异常都放行（防悬挂死锁）
            IN_FLIGHT.remove(lockKey);
        }
    }

    /** dspro P2-2：同会话压缩在飞标记（JVM 级；finally 必清防悬挂） */
    private static final java.util.Set<String> IN_FLIGHT = java.util.concurrent.ConcurrentHashMap.newKeySet();
}
