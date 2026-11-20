package com.smartdock.assistant.legacy.service;

import com.smartdock.assistant.domain.model.DcsAssistantMemory;
import com.smartdock.assistant.legacy.core.AssistantMemoryTurnLedger;
import com.smartdock.assistant.legacy.domain.DcsAssistantConversation;
import com.smartdock.assistant.legacy.mapper.AssistantConversationMapper;
import com.smartdock.assistant.mapper.AssistantMemoryMapper;
import com.smartdock.assistant.service.AssistantMemoryService;
import com.smartdock.assistant.support.AssistantMemoryFacts;
import com.smartdock.assistant.support.AssistantPiiFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * 助手长期记忆的回合写路径（批 6 题 2 案 1，2026-11-20 由 556 行旧服务改名拆出）。
 *
 * <p><b>边界</b>：只留必须碰外线的两件事——
 * ① {@link #saveReturningId} 保存记忆：要按 threadId 反查会话表记出处、命中同主题时写回合台账；
 * ② {@link #recordInjectedForTurn} 上报本轮注入统计（回合台账，供 RUN_FINISHED 发帧）。</p>
 *
 * <p>清单/编辑/开关/归档/导出/摘要等只碰记忆自有表的方法在
 * {@link AssistantMemoryService}（新根，按表归属切）。本类依赖它属于旧→新方向，
 * 门禁规则 8 只禁新→旧；保存成功后会调 {@code invalidateDigestCache} 让其立即失效缓存。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantMemoryTurnService {

    private final AssistantMemoryMapper memoryMapper;
    private final AssistantConversationMapper conversationMapper;
    private final AssistantPiiFilter piiFilter;
    /** 回合记忆账本（题8/题9 会话内可见；Enricher 在 RUN_FINISHED 取走发帧） */
    private final AssistantMemoryTurnLedger turnLedger;
    /** 存储面（新根）：本人开关判定 + 写后缓存失效 */
    private final AssistantMemoryService memoryService;

    /**
     * 落库结果（三批③，2026-11-01；题8 扩）：message=人话结果；memoryId=落库行 id；
     * overwritten=本次被同主题覆盖的旧条内容（无覆盖为空列表，供会话内「记忆已更新」卡）。
     */
    public record SaveOutcome(String message, Long memoryId, List<String> overwritten) {
        public SaveOutcome(String message, Long memoryId) {
            this(message, memoryId, List.of());
        }
    }

    /** save_memory 工具入口（旧签名，兼容存量调用）：只取人话结果 */
    public String save(String tenantId, Long userId, String raw) {
        return saveReturningId(tenantId, userId, raw, null).message();
    }

    /** save_memory 工具入口（题7 出处版）：threadId 可空，能查到会话就记 conversation_id */
    public SaveOutcome saveReturningId(String tenantId, Long userId, String raw, String threadId) {
        String content = raw == null ? "" : raw.trim();
        if (content.isEmpty()) {
            return new SaveOutcome("内容是空的，没有保存", null);
        }
        if (!memoryService.isUserEnabled(tenantId, userId)) {
            return new SaveOutcome("你已关闭长期记忆，这次没有保存（想开启请到「AI 助手记忆」页打开）", null);
        }
        // PII 脱敏（手机号/身份证不进长期记忆——记忆页用户可见，更要守住）。
        // 顺序铁律：先脱敏、后截断——先截会把跨 200 边界的 18 位身份证切出 17 位前缀，正则认不出=明文落库。
        content = piiFilter.mask(content, tenantId);
        if (content.length() > AssistantMemoryFacts.MAX_CONTENT) {
            content = content.substring(0, AssistantMemoryFacts.MAX_CONTENT);
        }
        if (memoryMapper.countByContent(tenantId, userId, content) > 0) {
            return new SaveOutcome("这条已经记过了，不重复保存", null);
        }
        if (memoryMapper.countByUser(tenantId, userId) >= AssistantMemoryFacts.MAX_PER_USER) {
            return new SaveOutcome("记忆最多存 " + AssistantMemoryFacts.MAX_PER_USER
                + " 条：请在「AI 助手记忆」页删掉不再需要的，再让我记新的", null);
        }

        String topicKey = AssistantMemoryFacts.topicKeyOf(content);
        List<DcsAssistantMemory> sameTopic = topicKey == null
            ? List.of() : memoryMapper.listActiveByTopic(tenantId, userId, topicKey);

        DcsAssistantMemory row = new DcsAssistantMemory();
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setContent(content);
        row.setSource("AI");
        row.setMemoryType(AssistantMemoryFacts.detectType(content));
        row.setTopicKey(topicKey);
        row.setStatus(AssistantMemoryFacts.ST_ACTIVE);
        row.setConversationId(resolveConversationId(tenantId, threadId));
        row.setCreateBy(userId);
        row.setCreateTime(new Date());
        memoryMapper.insert(row);

        // 题8：同主题旧条留痕（不删、可回滚）
        List<String> overwritten = new ArrayList<>();
        Date now = new Date();
        for (DcsAssistantMemory old : sameTopic) {
            if (memoryMapper.supersedeOwn(tenantId, userId, old.getId(), row.getId(), now) > 0) {
                overwritten.add(old.getContent());
                // 会话内「记忆已更新」小卡（题8）：旧→新，随本回合 memory_turn 帧下发
                // 键用 threadId 而非 ThreadLocal 里的 runId——工具跑在 loom 线程，读不到 run 上下文
                turnLedger.recordSuperseded(threadId, old.getContent(), content);
            }
        }
        memoryService.invalidateDigestCache(tenantId, userId);
        log.info("[AssistantMemory] 已保存 tenant={} user={} contentLen={} memoryId={} type={} topic={} conv={} superseded={}",
            tenantId, userId, content.length(), row.getId(), row.getMemoryType(), topicKey,
            row.getConversationId(), overwritten.size());
        return new SaveOutcome("已记住：" + content, row.getId(), overwritten);
    }

    /**
     * 上报本轮注入统计（题9 可见行）：装饰器注入成功后调。
     * threadId 由装配期显式传入（模型调用在 loom 线程，读不到 run ThreadLocal）。
     * count&lt;=0 = 本轮没命中，显式清空上一轮残留。
     */
    public void recordInjectedForTurn(String threadId, int count, int chars) {
        try {
            turnLedger.recordInjected(threadId, count, chars);
        } catch (RuntimeException e) {
            log.debug("[AssistantMemory] 注入统计上报失败(忽略): {}", e.getMessage());
        }
    }

    /** threadId → conversation_id（题7 出处；查不到返回 null，前端退化为「对话中沉淀」） */
    private Long resolveConversationId(String tenantId, String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        try {
            DcsAssistantConversation conv = conversationMapper.selectByThread(tenantId, threadId);
            return conv == null ? null : conv.getId();
        } catch (RuntimeException e) {
            log.debug("[AssistantMemory] 出处会话反查失败(忽略): {}", e.getMessage());
            return null;
        }
    }
}
