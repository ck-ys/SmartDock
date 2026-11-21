package com.smartdock.assistant.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartdock.assistant.domain.model.AssistantTodoItem;
import com.smartdock.assistant.domain.model.AssistantCompaction;
import com.smartdock.assistant.domain.model.AssistantMessage;
import com.smartdock.assistant.mapper.AssistantCompactionMapper;
import com.smartdock.assistant.mapper.AssistantConversationMapper;
import com.smartdock.assistant.mapper.AssistantMessageMapper;
import com.smartdock.assistant.support.AssistantPiiFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * 会话消息写路径（批 7 · 7C-1，2026-11-20 拍板 Q3 ★ 案 1）。
 *
 * <p><b>纯搬移</b>：8 个方法与 {@code AssistantConversationService} 原同名方法逐行一致。
 * 只碰 {@code assistant_message} 一张表，外加落库后顺手 {@code touch} 会话行
 * （列表排序键 last_message_time，与消息插入同事务语义）。</p>
 *
 * <p><b>X-08 明文例外矩阵照旧</b>：user 消息=明文（本人原文通道）；assistant 消息与
 * 部分冲刷内容=落库前 PII 脱敏（{@link AssistantPiiFilter}）。两条路径的掩码口径必须一致，
 * 否则同一条回答在「插入」与「增量更新」两处会呈现不同的脱敏结果。</p>
 *
 * <p><b>不在本类</b>：① {@code persistUserRound}（一轮三表写 + 并发准入）归
 * {@link RunLedgerService}；② 三个只读方法（最新 assistant / 上下文 tokens / 本回合提问）在
 * {@code query.ConversationQueryService}——读写分家后，只读不该出现在写服务上；
 * ③ 清人与保留期清理归 {@link PurgeService}（7C-2）。</p>
 *
 * <p><b>7C-2 并入</b>：{@code appendCompactionRecord}（压缩审计行，落
 * {@code assistant_compaction}）。它与消息写入同属「一次回合里追加一行」，
 * 调用方只有压缩中间件一处，放这里比单开一个类更省。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageWriteService {

    private final AssistantConversationMapper conversationMapper;
    private final AssistantMessageMapper messageMapper;
    private final AssistantCompactionMapper compactionMapper;
    private final AssistantPiiFilter piiFilter;

    /** user 侧消息（resolver 同步；明文例外通道） */
    public void appendUserMessage(String tenantId, Long conversationId, Long userId, String content) {
        insertMessage(tenantId, conversationId, userId, "user", content, null, "normal");
    }

    /** assistant 侧消息（enricher 异步；PII 脱敏后落库） */
    public void appendAssistantMessage(String tenantId, Long conversationId, Long userId,
                                       String content, String model, String endReason) {
        appendAssistantMessage(tenantId, conversationId, userId, content, model, endReason, null);
    }

    /** assistant 侧消息带工具步骤(批J-4: toolSteps JSON 随消息落库, 历史回放恢复 stat/步骤行)；
     *  批5 Q7-3 起返回消息 id（追问侧车回写定位用） */
    public Long appendAssistantMessage(String tenantId, Long conversationId, Long userId,
                                       String content, String model, String endReason, String toolSteps) {
        return appendAssistantMessage(tenantId, conversationId, userId, content, model, endReason, toolSteps, null);
    }

    /** 批B B1：totalTokens=本回合末次模型调用 input(+cached)+output（上下文环数据源），
     *  null 不写列=前端环空态（估算/截断路径宁缺毋假） */
    public Long appendAssistantMessage(String tenantId, Long conversationId, Long userId,
                                       String content, String model, String endReason, String toolSteps,
                                       Long totalTokens) {
        return insertMessage(tenantId, conversationId, userId, "assistant",
            piiFilter.mask(content, tenantId), model, endReason, toolSteps, totalTokens);
    }

    /** 侧车回写追问建议（批5 Q7-3；空值不覆盖，防误抹已生成建议；tenantId=审查回修补的纵深防御谓词） */
    public void writeFollowupSuggestions(String tenantId, Long messageId, String suggestions) {
        if (messageId == null || suggestions == null || suggestions.isBlank()) {
            return;
        }
        messageMapper.updateFollowupSuggestions(messageId, tenantId, suggestions);
    }

    /** 批6-#2：partial 行原地更新（生成途中增量冲刷 / 收割时转 truncated）；
     *  content 过 PII 掩码（与插入路径同口径） */
    public void updatePartialMessage(String tenantId, Long messageId, String content, String toolSteps, String endReason) {
        messageMapper.updatePartial(messageId, tenantId, piiFilter.mask(content, tenantId), toolSteps, endReason);
    }

    /** 批6-#2：终道路径删本 run 的 partial 行（真实段落行落库前清重叠内容） */
    public void deletePartialMessage(String tenantId, Long messageId) {
        messageMapper.deletePartial(messageId, tenantId);
    }

    /**
     * 「已压缩」分隔条行（批17 Q2）：role=system、content=纯数字 JSON
     * （{"type":"compaction",beforeTokens,afterTokens}），无 PII 新增面（role 列
     * varchar(16) 无约束，零 DDL）。前端 rebuildFromHistory 按本行渲染分隔条。
     */
    public void appendSystemDivider(String tenantId, Long conversationId, Long userId, String content) {
        insertMessage(tenantId, conversationId, userId, "system", content, null, null);
    }

    /**
     * 自动压缩记录（批A-2 Q9-A）：一行一次压缩，prev_id 串起同会话边界链。
     * 失败只 WARN 不上抛——压缩本身已完成，审计行是锦上添花。
     *
     * <p>摘要正文落库前过 PII 脱敏（与 assistant 消息同口径）并截断
     * {@value #COMPACTION_SUMMARY_MAX_CHARS} 字——够审计「它忘了什么」，又不把整段
     * 原文在库里再存一份。</p>
     *
     * <p>7C-2 从 {@code legacy.service.AssistantConversationService} 搬入，逐行一致。</p>
     */
    public void appendCompactionRecord(String tenantId, Long conversationId, Long userId, String threadId,
                                       int beforeTokens, int afterTokens,
                                       int beforeMessages, int afterMessages,
                                       String status, String reason, String summary) {
        try {
            AssistantCompaction rec = new AssistantCompaction();
            rec.setId(IdWorker.getId());
            rec.setTenantId(tenantId);
            rec.setConversationId(conversationId);
            rec.setUserId(userId);
            rec.setThreadId(threadId);
            rec.setTriggerType("AUTO");
            rec.setStatus(status);
            rec.setReason(reason);
            rec.setBeforeTokens(beforeTokens);
            rec.setAfterTokens(afterTokens);
            rec.setBeforeMessages(beforeMessages);
            rec.setAfterMessages(afterMessages);
            rec.setSummary(truncateMaskedSummary(summary, tenantId));
            rec.setPrevId(compactionMapper.selectPrevId(tenantId, conversationId));
            rec.setCreateTime(new Date());
            compactionMapper.insert(rec);
        } catch (Exception e) {
            log.warn("[Compaction] 压缩记录落库失败(不影响对话) tenant={} thread={}: {}",
                tenantId, threadId, e.getMessage());
        }
    }

    /** 摘要审计正文上限（字符） */
    private static final int COMPACTION_SUMMARY_MAX_CHARS = 6000;

    /**
     * TODO 清单整表替换落库（ZCode 对齐 Q2，批A）。显式租户谓词 mapper
     * （InterceptorIgnore）——工具线程无请求上下文也安全。同租户异主 thread 实际会
     * 命中 1 行（WHERE 无 user_id；glm53 P2-6 回修口径）——正常路径不可达：agent 创建前
     * ensureConversation 归属校验已拒异主，工具 threadId 由 agent 构造时固化。
     *
     * <p>批 7C-2 从 legacy 搬入，逐行一致；条目类型改用 {@link AssistantTodoItem}
     * （字段与 JSON 键名与原内部 record 相同）。</p>
     */
    public void saveTodo(String tenantId, String threadId, List<AssistantTodoItem> items) {
        try {
            String json = new ObjectMapper().writeValueAsString(items);
            conversationMapper.updateTodo(tenantId, threadId, json);
        } catch (Exception e) {
            // 持久化失败不炸工具（信号仓仍会推帧，仅刷新后清单丢失）
            log.warn("[AssistantTodo] 清单落库失败(不影响对话): {}", e.getMessage());
        }
    }

    private String truncateMaskedSummary(String summary, String tenantId) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        String masked;
        try {
            masked = piiFilter.mask(summary, tenantId);
        } catch (Exception e) {
            // 脱敏器异常时宁可不存摘要，也不把原始正文落库
            log.warn("[Compaction] 摘要脱敏失败, 本次不存摘要: {}", e.getMessage());
            return null;
        }
        if (masked.length() <= COMPACTION_SUMMARY_MAX_CHARS) {
            return masked;
        }
        // 按码点截断（三审 P2）：substring 按 UTF-16 单元切可能劈开代理对（emoji 落库变 ?）
        int cut = masked.offsetByCodePoints(0,
            Math.min(COMPACTION_SUMMARY_MAX_CHARS, masked.codePointCount(0, masked.length())));
        return masked.substring(0, cut) + "…";
    }

    private void insertMessage(String tenantId, Long conversationId, Long userId,
                               String role, String content, String model, String endReason) {
        insertMessage(tenantId, conversationId, userId, role, content, model, endReason, null);
    }

    private Long insertMessage(String tenantId, Long conversationId, Long userId,
                               String role, String content, String model, String endReason, String toolSteps) {
        return insertMessage(tenantId, conversationId, userId, role, content, model, endReason, toolSteps, null);
    }

    private Long insertMessage(String tenantId, Long conversationId, Long userId,
                               String role, String content, String model, String endReason,
                               String toolSteps, Long totalTokens) {
        AssistantMessage msg = new AssistantMessage();
        msg.setId(IdWorker.getId());
        msg.setTenantId(tenantId);
        msg.setConversationId(conversationId);
        msg.setUserId(userId);
        msg.setRole(role);
        msg.setContent(content);
        msg.setModel(model);
        msg.setEndReason(endReason);
        msg.setToolSteps(toolSteps);
        msg.setTotalTokens(totalTokens);
        messageMapper.insert(msg);
        conversationMapper.touch(tenantId, conversationId);
        return msg.getId();
    }
}
