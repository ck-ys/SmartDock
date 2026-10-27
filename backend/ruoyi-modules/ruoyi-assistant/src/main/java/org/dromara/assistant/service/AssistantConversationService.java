package org.dromara.assistant.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsAssistantConversation;
import org.dromara.assistant.domain.DcsAssistantMessage;
import org.dromara.assistant.domain.DcsAssistantRun;
import org.dromara.assistant.mapper.AssistantActionMapper;
import org.dromara.assistant.mapper.AssistantConversationMapper;
import org.dromara.assistant.mapper.AssistantMessageMapper;
import org.dromara.assistant.mapper.AssistantRunMapper;
import org.dromara.assistant.security.AssistantPiiFilter;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.redisson.client.codec.StringCodec;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 助手会话/消息/run 持久化服务（批G G2/G3，2026-10-06）。
 *
 * <p>写入分两半：user 侧在 resolver（有身份有输入，同步）；assistant 侧在 enricher
 * （流线程按 runId 回查归属后异步落）。归属双保险：thread 首绑校验（conversation
 * 表异主 403）+ run 登记制（runId 异主 403，S-04①/S-12）。</p>
 *
 * <p>X-08 明文例外矩阵：user 消息=明文（本人原文通道）；assistant 消息=落库前 PII 脱敏。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantConversationService {

    private final AssistantConversationMapper conversationMapper;
    private final AssistantMessageMapper messageMapper;
    private final AssistantRunMapper runMapper;
    private final AssistantActionMapper actionMapper;
    /** 批A-2：自动压缩记录（链式审计） */
    private final org.dromara.assistant.mapper.AssistantCompactionMapper compactionMapper;
    /** 083/ISSUE-F14-02 修：retentionSweep 补附件回收 + purgeUser 补附件行 */
    private final org.dromara.assistant.mapper.AssistantAttachmentMapper attachmentMapper;
    private final AssistantPiiFilter piiFilter;
    private final JdbcTemplate jdbcTemplate;

    /**
     * thread 归属首绑（G4 Ownership 处置链）：返回会话 id。
     * 同 thread 异主 → 403（不区分"不存在"与"无权"——防探测）。
     */
    public Long ensureConversation(String tenantId, Long userId, String threadId, String title) {
        DcsAssistantConversation exists = conversationMapper.selectByThread(tenantId, threadId);
        if (exists == null) {
            DcsAssistantConversation row = new DcsAssistantConversation();
            row.setId(IdWorker.getId());
            row.setTenantId(tenantId);
            row.setUserId(userId);
            row.setThreadId(threadId);
            row.setTitle(title);
            row.setStatus("ACTIVE");
            row.setDelFlag("0");
            conversationMapper.insertIgnoreThread(row);
            exists = conversationMapper.selectByThread(tenantId, threadId);
        }
        if (exists == null || !exists.getUserId().equals(userId)) {
            throw new ServiceException("会话不存在或无权访问", 403);
        }
        // 归档会话拒写（批H P1-2）：「只读回放」后端强制——ARCHIVED 会话发消息 403，恢复后放行
        if ("ARCHIVED".equals(exists.getStatus())) {
            throw new ServiceException("会话已归档（只读回放），请先恢复会话后再发消息", 403);
        }
        return exists.getId();
    }

    /**
     * run 登记制（S-04①）：首现落库绑定归属；重放同 runId 异主/异租户 → 403。
     *
     * @return 本次是否新建 run 行（false=同主重放，user 消息不重复落库）
     */
    public boolean registerRun(String tenantId, Long userId, String threadId, String runId) {
        if (runId == null || runId.isBlank()) {
            return true;
        }
        DcsAssistantRun row = new DcsAssistantRun();
        row.setId(IdWorker.getId());
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setThreadId(threadId);
        row.setRunId(runId);
        int inserted = runMapper.insertIgnoreRun(row);
        DcsAssistantRun bound = runMapper.selectByRunId(runId);
        if (bound != null && (!bound.getUserId().equals(userId) || !bound.getTenantId().equals(tenantId))) {
            throw new ServiceException("请求不合法（runId 归属校验失败）", 403);
        }
        return inserted > 0;
    }

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

    /** 本会话最新一条 assistant 消息（批5 Q7-3：前端 done 后拉追问建议） */
    public DcsAssistantMessage latestAssistant(String tenantId, Long userId, Long conversationId) {
        return messageMapper.selectLatestAssistant(tenantId, conversationId, userId);
    }

    /** 批B B1 修：环数据源——最新一条 tokens 非空 assistant 行（truncated 空值行不遮蔽旧读数） */
    public Long latestContextTokens(String tenantId, Long userId, Long conversationId) {
        return messageMapper.selectLatestContextTokens(tenantId, conversationId, userId);
    }

    /** 本回合用户提问（批5 Q7-3：assistant 落库前该会话最后一条 user 消息即本回合问题。
     *  七席审查 P1 回修：DESC LIMIT 1 单行单列，替换 ASC 500 行倒扫（>500 条会话取远古问题+宽表浪费） */
    public String lastUserQuestion(String tenantId, Long userId, Long conversationId) {
        return messageMapper.selectLatestUserContent(tenantId, conversationId, userId);
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

    /** 批6-#2：失联收割——条件迁移 INTERRUPTED/'client-disconnect'，返回归属行供退回合
     *  （RETURNING 语义=与真实终态竞态时只有一方赢，防双退；m3 P1-1 回修补租户谓词——
     *  雪花 runId 全局唯一本无越权面，纵深防御与同 mapper 风格对齐） */
    public List<DcsAssistantRun> finalizeStalledRun(String tenantId, String runId) {
        return runMapper.finalizeStalled(tenantId, runId);
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
        DcsAssistantMessage msg = new DcsAssistantMessage();
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

    /** run 终态回写（enricher：COMPLETED/FAILED/INTERRUPTED）。返回实际更新行数——
     *  0 行=run 已被收割器/兜底 job 抢先终局化（迟到终态被条件丢弃），调用方 WARN 观测 */
    public int finishRun(String runId, String status, String endReason) {
        return runMapper.finish(runId, status, endReason);
    }

    public DcsAssistantRun runByRunId(String runId) {
        return runMapper.selectByRunId(runId);
    }

    public DcsAssistantConversation conversationByThread(String tenantId, String threadId) {
        return conversationMapper.selectByThread(tenantId, threadId);
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
     */
    public void appendCompactionRecord(String tenantId, Long conversationId, Long userId, String threadId,
                                       int beforeTokens, int afterTokens,
                                       int beforeMessages, int afterMessages,
                                       String status, String reason, String summary) {
        try {
            org.dromara.assistant.domain.DcsAssistantCompaction rec =
                new org.dromara.assistant.domain.DcsAssistantCompaction();
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
            rec.setCreateTime(new java.util.Date());
            compactionMapper.insert(rec);
        } catch (Exception e) {
            log.warn("[Compaction] 压缩记录落库失败(不影响对话) tenant={} thread={}: {}",
                tenantId, threadId, e.getMessage());
        }
    }

    /** 摘要审计正文上限（字符） */
    private static final int COMPACTION_SUMMARY_MAX_CHARS = 6000;

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

    /** 该会话当前是否有在途轮（批17 Q2 手动压缩配套；批A 手动通道删除后暂无调用方，
     *  保留是因 runMapper.selectRunningThreadIds 还有别处使用且本方法语义独立可复用） */
    public boolean hasRunningRound(String tenantId, Long userId, String threadId) {
        return runMapper.selectRunningThreadIds(tenantId, userId).contains(threadId);
    }


    /**
     * TODO 清单整表替换落库（ZCode 对齐 Q2，批A）。显式租户谓词 mapper
     * （InterceptorIgnore）——工具线程无请求上下文也安全。同租户异主 thread 实际会
     * 命中 1 行（WHERE 无 user_id；glm53 P2-6 回修口径）——正常路径不可达：agent 创建前
     * ensureConversation 归属校验已拒异主，工具 threadId 由 agent 构造时固化。
     */
    public void saveTodo(String tenantId, String threadId, List<org.dromara.assistant.core.AssistantTodoSignal.TodoItem> items) {
        try {
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(items);
            conversationMapper.updateTodo(tenantId, threadId, json);
        } catch (Exception e) {
            // 持久化失败不炸工具（信号仓仍会推帧，仅刷新后清单丢失）
            log.warn("[AssistantTodo] 清单落库失败(不影响对话): {}", e.getMessage());
        }
    }

    /** 当前会话 TODO 清单（前端打开会话恢复分区；无清单返回空数组） */
    public List<org.dromara.assistant.core.AssistantTodoSignal.TodoItem> todoOf(String tenantId, Long userId, String threadId) {
        DcsAssistantConversation conv = conversationMapper.selectByThread(tenantId, threadId);
        if (conv == null || !conv.getUserId().equals(userId) || conv.getTodoItems() == null || conv.getTodoItems().isBlank()) {
            return List.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(conv.getTodoItems(),
                new com.fasterxml.jackson.core.type.TypeReference<List<org.dromara.assistant.core.AssistantTodoSignal.TodoItem>>() {
                });
        } catch (Exception e) {
            log.warn("[AssistantTodo] 清单解析失败(按空处理): {}", e.getMessage());
            return List.of();
        }
    }

    /** 会话列表（180 天语义由清理 job 保证；前端 E2 数据源）。
     *  status 口径（批H P1-1）：ACTIVE=默认只列活跃（抽屉/悬浮球兼容）；ARCHIVED=只列归档
     *  （归档折叠条）；ALL=两者都列（会话页本地分组）。del_flag 兜底过滤（本表清理以物理删
     *  为主，软删行不出现）。 */
    public List<DcsAssistantConversation> listConversations(String tenantId, Long userId, int limit, int offset, String status) {
        return conversationMapper.selectList(conversationQuery(tenantId, userId, status)
            // id 决胜（AU-19）：last_message_time 并列（PG DESC NULLS FIRST + 同秒多行）时
            // 排序不稳定，跨页会漂移——前端「加载更多」按页追加会漏行/重行
            .orderByDesc(DcsAssistantConversation::getLastMessageTime)
            .orderByDesc(DcsAssistantConversation::getId)
            .last("LIMIT " + limit + " OFFSET " + offset));
    }

    /** 会话总数（列表分页 total 真值；dsflash P2-6 修——恒 rows.size() 会让翻页失真） */
    public long countConversations(String tenantId, Long userId, String status) {
        Long n = conversationMapper.selectCount(conversationQuery(tenantId, userId, status));
        return n == null ? 0 : n;
    }

    /**
     * 会话列表回填 running 标志（业务批4 Q4，2026-10-12）：按 run 表该用户当前
     * RUNNING 的 thread 集合标"生成中"——刷新/重开后侧栏与列表仍能识别在途轮。
     * 无 RUNNING 行时一行都不改（保持 null，前端按 false 渲染）。
     */
    public void markRunning(List<DcsAssistantConversation> rows, String tenantId, Long userId) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        List<String> runningThreads = runMapper.selectRunningThreadIds(tenantId, userId);
        if (runningThreads.isEmpty()) {
            return;
        }
        java.util.Set<String> set = new java.util.HashSet<>(runningThreads);
        for (DcsAssistantConversation row : rows) {
            row.setRunning(set.contains(row.getThreadId()));
        }
    }

    private com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DcsAssistantConversation>
            conversationQuery(String tenantId, Long userId, String status) {
        String safe = status == null ? "ACTIVE" : status.toUpperCase();
        com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DcsAssistantConversation> q =
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DcsAssistantConversation>()
                .eq(DcsAssistantConversation::getTenantId, tenantId)
                .eq(DcsAssistantConversation::getUserId, userId)
                .eq(DcsAssistantConversation::getDelFlag, "0");
        if (!"ALL".equals(safe)) {
            // ALL 之外一律按单值精确过滤（含非法值兜底 ACTIVE，controller 侧已 clamp）
            q.eq(DcsAssistantConversation::getStatus, "ARCHIVED".equals(safe) ? "ARCHIVED" : "ACTIVE");
        }
        return q;
    }

    /** 历史消息（本人会话；E2 会话重建/独立会话页数据源） */
    public List<DcsAssistantMessage> listMessages(String tenantId, Long userId, Long conversationId,
                                                  int limit, int offset) {
        DcsAssistantConversation conv = conversationMapper.selectOwned(tenantId, conversationId, userId);
        if (conv == null) {
            throw new ServiceException("会话不存在或无权访问", 403);
        }
        return messageMapper.selectPageOwned(tenantId, conversationId, userId, limit, offset);
    }

    /** 尾页消息（最新 N 条，返回前反转回时间序）。批A 二审 P1：压缩后的历史重载/长会话
     *  打开必须取尾部——ASC 首页在 >100 条会话上会把最新回答与压缩分隔条截掉 */
    public List<DcsAssistantMessage> listMessagesTail(String tenantId, Long userId, Long conversationId,
                                                       int limit) {
        DcsAssistantConversation conv = conversationMapper.selectOwned(tenantId, conversationId, userId);
        if (conv == null) {
            throw new ServiceException("会话不存在或无权访问", 403);
        }
        List<DcsAssistantMessage> rows =
            new java.util.ArrayList<>(messageMapper.selectPageOwnedDesc(tenantId, conversationId, userId, limit));
        java.util.Collections.reverse(rows);
        return rows;
    }

    /** 本人归档会话（软通道：status=ARCHIVED） */
    public void archiveConversation(String tenantId, Long userId, Long conversationId) {
        DcsAssistantConversation conv = conversationMapper.selectOwned(tenantId, conversationId, userId);
        if (conv == null) {
            throw new ServiceException("会话不存在或无权访问", 403);
        }
        conversationMapper.update(null, new LambdaUpdateWrapper<DcsAssistantConversation>()
            .eq(DcsAssistantConversation::getTenantId, tenantId)
            .eq(DcsAssistantConversation::getId, conversationId)
            .set(DcsAssistantConversation::getStatus, "ARCHIVED"));
    }

    /** 本人重命名会话（批D P2-7：PATCH title；title 列 varchar(200) 已有，零 DDL） */
    public void renameConversation(String tenantId, Long userId, Long conversationId, String title) {
        String safe = title == null ? "" : title.trim();
        if (safe.isEmpty() || safe.length() > 60) {
            throw new ServiceException("标题须为 1-60 个字符", 400);
        }
        DcsAssistantConversation conv = conversationMapper.selectOwned(tenantId, conversationId, userId);
        if (conv == null) {
            throw new ServiceException("会话不存在或无权访问", 403);
        }
        conversationMapper.update(null, new LambdaUpdateWrapper<DcsAssistantConversation>()
            .eq(DcsAssistantConversation::getTenantId, tenantId)
            .eq(DcsAssistantConversation::getId, conversationId)
            .set(DcsAssistantConversation::getTitle, safe));
    }

    /** 恢复已归档会话（批D：status=ARCHIVED→ACTIVE） */
    public void restoreConversation(String tenantId, Long userId, Long conversationId) {
        DcsAssistantConversation conv = conversationMapper.selectOwned(tenantId, conversationId, userId);
        if (conv == null) {
            throw new ServiceException("会话不存在或无权访问", 403);
        }
        conversationMapper.update(null, new LambdaUpdateWrapper<DcsAssistantConversation>()
            .eq(DcsAssistantConversation::getTenantId, tenantId)
            .eq(DcsAssistantConversation::getId, conversationId)
            .set(DcsAssistantConversation::getStatus, "ACTIVE"));
    }

    /**
     * 租户清除四表（spec §4.4/§15.7，GDPR 式），同事务原子。
     *
     * <p><b>事务红线（G2 实证）</b>：agentscope 清理绝不能进本事务——该表缺失的
     * 环境上 DELETE 报 bad grammar，虽可被 try-catch 吞掉，但失败语句已把 PG 事务
     * 打成 aborted，COMMIT 静默变 ROLLBACK（PG 对 aborted 事务的 COMMIT 不抛错，
     * 只返回 ROLLBACK tag），四表删除全部无声回滚。故拆两步：事务内只做四表，
     * 提交后由编排方（控制器/测试）调 {@link #purgeAgentscopeSessions}。
     * 快照归档 7 天=P2 冷备（登记）；操作留痕走端点 @Log。</p>
     */
    @Transactional(rollbackFor = Exception.class)
    public int purgeUser(String tenantId, Long userId) {
        int msgs = messageMapper.purgeUser(tenantId, userId);
        int convs = conversationMapper.purgeUser(tenantId, userId);
        int runs = runMapper.purgeUser(tenantId, userId);
        int acts = actionMapper.purgeUser(tenantId, userId);
        // 083/ISSUE-F14-02 修：清人补附件行（原四表缺附件=GDPR 半清除态；OSS 对象
        // 物理删除涉及文件中心全局行为，另立登记不并入本刀）
        int atts = attachmentMapper.purgeUser(tenantId, userId);
        // 批A 回修（三审 P1）：压缩记录同批清除——summary 列含对话派生内容，清人不得残留。
        // 二审 P1 回修：先探表再删——「先部署后跑 migration」的窗口里表不存在时，
        // BadSqlGrammar 会把整个清人事务打回滚（五表一条不删、接口 500），必须防御
        int compacts = compactionTableExists() ? compactionMapper.purgeUser(tenantId, userId) : 0;
        log.info("[AssistantPersist] 租户清除 tenant={} user={}: message={} conversation={} run={} action={} attachment={} compaction={}",
            tenantId, userId, msgs, convs, runs, acts, atts, compacts);
        return msgs + convs + runs + acts + atts + compacts;
    }

    /** dcs_assistant_compaction 是否已建表（同事务只读探测，异常按不存在处理） */
    private boolean compactionTableExists() {
        try {
            Boolean ok = jdbcTemplate.queryForObject(
                "SELECT to_regclass('public.dcs_assistant_compaction') IS NOT NULL", Boolean.class);
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * agentscope 官方会话清理（purgeUser 提交后调用；表缺失环境仅日志不阻塞）。
     * session_id = "userId:sessionId" 拼接（§4.4 P0 实证），userId 本身是
     * "t:{tenantId}:{uid}" 前缀形态 → LIKE 前缀精确圈定本人。
     *
     * <p>二站② AU-42：失败不再只留日志——登记进 Redis 补偿集合，由
     * {@link org.dromara.assistant.persist.AssistantRetentionJob} 每分钟重试，
     * 防「四表已删+框架表残留」的 GDPR 半清除态复活旧历史。重试成功即出队。</p>
     */
    public void purgeAgentscopeSessions(String tenantId, Long userId) {
        try {
            jdbcTemplate.update("DELETE FROM agentscope.agentscope_sessions WHERE session_id LIKE ?",
                "t:" + tenantId + ":" + userId + ":%");
        } catch (Exception e) {
            log.warn("[AssistantPersist] agentscope 会话清理失败(表缺失或仅剩框架表残留), 已入补偿队列: {}", e.getMessage());
            enqueuePurgeCompensation(tenantId, userId);
        }
    }

    /** 补偿队列键（裸键，PD-2 契约：与配额键同走 ignore） */
    static final String PURGE_COMPENSATE_KEY = "assistant:purge:compensate";

    private void enqueuePurgeCompensation(String tenantId, Long userId) {
        try {
            String entry = tenantId + ":" + userId;
            TenantHelper.ignore(() -> {
                org.redisson.api.RSet<String> queue = RedisUtils.getClient()
                    .getSet(PURGE_COMPENSATE_KEY, StringCodec.INSTANCE);
                return queue.add(entry);
            });
        } catch (Exception redisDown) {
            log.error("[AssistantPersist] 补偿登记也失败(Redis 不可用), 残留待人工核查 tenant={} user={}",
                tenantId, userId, redisDown);
        }
    }

    /**
     * 二站② AU-42：补偿重试单条（RetentionJob 每分钟调）——重跑 agentscope 清理，
     * 成功出队；仍失败留队下轮再来（表缺失环境=永久留队无害，量级=失败次数）。
     *
     * @return true=本条已清干净
     */
    public boolean retryPurgeAgentscopeSessions(String tenantId, Long userId) {
        try {
            jdbcTemplate.update("DELETE FROM agentscope.agentscope_sessions WHERE session_id LIKE ?",
                "t:" + tenantId + ":" + userId + ":%");
            return true;
        } catch (Exception e) {
            log.warn("[AssistantPersist] agentscope 补偿重试仍失败: {}", e.getMessage());
            return false;
        }
    }

    /** 二站② AU-42：补偿队列快照（RetentionJob 读；["tenantId:userId"]） */
    public java.util.Set<String> purgeCompensationQueue() {
        try {
            return TenantHelper.ignore(() -> {
                org.redisson.api.RSet<String> queue = RedisUtils.getClient()
                    .getSet(PURGE_COMPENSATE_KEY, StringCodec.INSTANCE);
                return queue.readAll();
            });
        } catch (Exception e) {
            return java.util.Set.of();
        }
    }

    /** 二站② AU-42：补偿出队（单条成功后调） */
    public void dequeuePurgeCompensation(String entry) {
        try {
            TenantHelper.ignore(() -> {
                org.redisson.api.RSet<String> queue = RedisUtils.getClient()
                    .getSet(PURGE_COMPENSATE_KEY, StringCodec.INSTANCE);
                return queue.remove(entry);
            });
        } catch (Exception ignored) {
            // 出队失败=下轮重试幂等无害
        }
    }

    /**
     * user 侧一轮三步同事务（dspro P2-8 修）：首绑+run 登记+user 消息原子落库——
     * 各自 autocommit 会在 PG 闪断时留"空壳会话"（会话行在、消息与 run 不在）。
     * resolver 的 DataAccessException 降级捕获语义不变（异常整体回滚=零残留）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void persistUserRound(String tenantId, Long userId, String threadId, String runId,
                                 String title, String content) {
        // 题5 三审回修（dsflash-P1/glm53-P2 共识）：先取同 thread 发送串行锁再「插行+查在途」
        // ——否则 READ COMMITTED 下两个并发事务互看不见对方未提交的 RUNNING 行，双双过门
        // =双流并发写同一 AgentSession（recorder 以 threadId 为键交错+双扣费）。锁事务级自动释放
        runMapper.acquireThreadSendLock(tenantId, userId, threadId);
        Long conversationId = ensureConversation(tenantId, userId, threadId, title);
        boolean freshRun = registerRun(tenantId, userId, threadId, runId);
        // runId 重放幂等（dsflash P1-3 收尾）+ goal 加固 H1：同主重放=断线重试重发同 runId——
        // 此前只挡 user 消息双落，agent 照跑第二遍＝全额双扣费+assistant 消息可双落
        // （计费幂等键含实例段+seq，重跑即新键；消息表无 run 级唯一约束）。
        // run 表已有行=第一发确实到过后端（可能仍在跑或已被断连取消），一律拒绝重跑；
        // 框架把 resolver 异常转 RUN_ERROR 帧（javap 实证），前端按错误收口不再自动重试
        if (!freshRun) {
            throw new ServiceException("检测到重复请求（网络重连触发），已拦截以防重复扣费。"
                + "上一轮可能仍在处理，稍候刷新对话查看；若未收到回答请重新发送", 409);
        }
        // 题5 深审 P1-1 回修（dspro/glm53 共识，2026-10-28）：断连不拆链桥接上线后，框架
        // resume 闩会随 HTTP 断开提前释放（doFinally 挂在汇热流上）而原链仍在跑——此时同
        // thread 再发新消息=双流并发写同一 AgentSession（框架明确不支持态；recorder 步骤/
        // 思考缓冲也依赖同 thread 单活跃 run）。DB 真源兜底：同 (租户,用户,thread) 存在
        // 其它 RUNNING run 即 409 人话拒绝（新 run 行由 registerRun 刚插入，按 runId 排除）。
        List<DcsAssistantRun> inflight = runMapper.selectByThread(tenantId, threadId, userId, 2);
        for (DcsAssistantRun r : inflight) {
            if ("RUNNING".equals(r.getStatus()) && !r.getRunId().equals(runId)) {
                throw new ServiceException("上一轮回答还在生成中，稍候再发；刷新页面即可接着看这一轮回答", 409);
            }
        }
        if (content != null && !content.isBlank()) {
            appendUserMessage(tenantId, conversationId, userId, content);
        }
    }

    /** 悬挂 run 兜底通道（retention job 每分钟调；dspro P1-1）：迁移成功的行才返回 */
    public List<DcsAssistantRun> closeStuckRuns(int minutes) {
        return runMapper.closeStuck(minutes);
    }

    /** 业务批10 #2：RUNNING 超时行扫描（收割器零事件二扫；调用方按 lastEventAt 差集过滤） */
    public List<DcsAssistantRun> listRunningOlderThan(int seconds) {
        return runMapper.selectRunningOlderThan(seconds);
    }

    /** 保留期清理（message 90 天 / conversation·run·action 180 天），分批物理删 */
    public void retentionSweep(int batch) {
        int total = 0;
        int n;
        do {
            n = messageMapper.deleteExpired(batch);
            total += n;
        } while (n >= batch);
        log.info("[AssistantPersist] 保留期清理 message 删除 {} 行", total);
        int convs = 0;
        do {
            n = conversationMapper.deleteExpired(batch);
            convs += n;
        } while (n >= batch);
        int runs = 0;
        do {
            n = runMapper.deleteExpired(batch);
            runs += n;
        } while (n >= batch);
        int acts = 0;
        do {
            n = actionMapper.deleteExpired(batch);
            acts += n;
        } while (n >= batch);
        // 083/ISSUE-F14-02 修：附件行纳入保留期清理（原版 deleteExpired 是死代码零调用方，
        // 表+307KB 级 parsed_text 永久累积）；与 conversation 同 180 天口径
        int atts = 0;
        do {
            n = attachmentMapper.deleteExpired(batch);
            atts += n;
        } while (n >= batch);
        // 批A 回修（三审 P1）：压缩记录纳入保留期清理（180 天口径，防审计行无界增长）。
        // 二审 P1 回修：表缺失环境（先部署后迁移）不让每日 job 整体报错
        int compacts = 0;
        if (compactionTableExists()) {
            do {
                n = compactionMapper.deleteExpired(batch);
                compacts += n;
            } while (n >= batch);
        }
        log.info("[AssistantPersist] 保留期清理 conversation={} run={} action={} attachment={} compaction={} 行(180 天)",
            convs, runs, acts, atts, compacts);
    }

    /** 080/ISSUE-R13-3 修配套：清人前的该用户 thread 清单（悬挂缓存/步骤缓冲按 thread
     *  键驻留内存，purgeUser 删行后控制器据此逐 thread 清缓存） */
    public List<String> threadIdsOfUser(String tenantId, Long userId) {
        return conversationMapper.threadIdsOfUser(tenantId, userId);
    }
}
