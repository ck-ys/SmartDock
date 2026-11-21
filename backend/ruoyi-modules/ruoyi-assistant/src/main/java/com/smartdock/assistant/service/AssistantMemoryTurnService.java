package com.smartdock.assistant.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.smartdock.assistant.domain.model.AssistantMemory;
import com.smartdock.assistant.engine.core.AssistantMemoryTurnLedger;
import com.smartdock.assistant.domain.model.AssistantConversation;
import com.smartdock.assistant.mapper.AssistantConversationMapper;
import com.smartdock.assistant.mapper.AssistantMemoryMapper;
import com.smartdock.assistant.service.AssistantMemoryService;
import com.smartdock.assistant.support.AssistantMemoryFacts;
import com.smartdock.assistant.support.AssistantPiiFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

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
 *
 * <p><b>D1 并发栅栏（批 7 前置 STEP 1，2026-11-20；多审回修加固）</b>：同 (租户,人,主题)
 * 只能一条生效中，靠三件套保证——库层部分唯一索引 {@code uk_asst_memory_active_topic} +
 * 保存口「先腾位后插入」同事务（{@link TransactionTemplate}，旧条覆盖与插入原子提交）+
 * 撞唯一索引时重读主题清单再来一轮。{@code @Transactional} 注解挡不住这个竞态
 * （读与写之间没有栅栏，批 6 二审已实证），所以用编程式事务 + 重试。
 * 回合台账只在事务提交成功后补记（回滚轮不留假帧）；重试只认栅栏索引的冲突，
 * 撞别的唯一约束原样上抛。</p>
 */
@Slf4j
@Service
public class AssistantMemoryTurnService {

    /** D1 重试上限：同主题并发保存时，输家最多重读重试这几轮；再撞就让人稍后再试 */
    private static final int SAVE_MAX_ATTEMPTS = 3;

    /**
     * 同主题栅栏索引名（迁移 2026-11-20_memory_active_topic_unique.sql 建的）。
     * 重试只认撞它的冲突；撞其它唯一约束（如主键）= 数据问题，原样上抛（批 7 多审 P2）。
     */
    private static final String TOPIC_FENCE_INDEX = "uk_asst_memory_active_topic";

    private final AssistantMemoryMapper memoryMapper;
    private final AssistantConversationMapper conversationMapper;
    private final AssistantPiiFilter piiFilter;
    /** 回合记忆账本（题8/题9 会话内可见；Enricher 在 RUN_FINISHED 取走发帧） */
    private final AssistantMemoryTurnLedger turnLedger;
    /** 存储面（新根）：本人开关判定 + 写后缓存失效 */
    private final AssistantMemoryService memoryService;
    /**
     * D1：覆盖旧条 + 插入新条必须同一事务（否则唯一索引会把自己拦死/留下半截状态）。
     * 固定 REQUIRES_NEW（批 7 多审席2/席3）：撞索引后 PG 事务已废，重试必须换新事务——
     * 默认 REQUIRED 的共享模板在未来被外层 {@code @Transactional} 包住时会静默加入外层事务，
     * 第一次 23505 就把外层打成 aborted，重试轮全部 25P02 失效（ProfileEventServiceImpl 同款先例）。
     */
    private final TransactionTemplate saveTx;

    public AssistantMemoryTurnService(AssistantMemoryMapper memoryMapper,
                                      AssistantConversationMapper conversationMapper,
                                      AssistantPiiFilter piiFilter,
                                      AssistantMemoryTurnLedger turnLedger,
                                      AssistantMemoryService memoryService,
                                      PlatformTransactionManager transactionManager) {
        this.memoryMapper = memoryMapper;
        this.conversationMapper = conversationMapper;
        this.piiFilter = piiFilter;
        this.turnLedger = turnLedger;
        this.memoryService = memoryService;
        this.saveTx = new TransactionTemplate(transactionManager);
        this.saveTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 落库结果（三批③，2026-11-01；题8 扩；二轮席1 加 abandoned）：
     * message=人话结果；memoryId=落库行 id；overwritten=本次被同主题覆盖的旧条内容
     * （无覆盖为空列表，供会话内「记忆已更新」卡）；abandoned=true=重试耗尽没存上
     * （并发/锁竞争放弃）——动作审计不得记成成功。
     */
    public record SaveOutcome(String message, Long memoryId, List<String> overwritten, boolean abandoned) {
        public SaveOutcome(String message, Long memoryId) {
            this(message, memoryId, List.of(), false);
        }

        public SaveOutcome(String message, Long memoryId, List<String> overwritten) {
            this(message, memoryId, overwritten, false);
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
        // 顺序铁律：先粗截断、再脱敏、后精截断——先截 200 会把跨边界的 18 位身份证切出 17 位前缀，正则认不出=明文落库；
        // 但脱敏不能直接吃超长正文（正则回溯成本随长度涨），所以先粗截到 MAX_INPUT_CHARS 再脱敏。
        if (content.length() > AssistantMemoryFacts.MAX_INPUT_CHARS) {
            content = content.substring(0, AssistantMemoryFacts.MAX_INPUT_CHARS);
        }
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
        Long conversationId = resolveConversationId(tenantId, threadId);
        final String text = content; // 截断/脱敏后的最终文本（lambda 捕获要求实际最终）
        for (int attempt = 1; ; attempt++) {
            try {
                SaveOutcome out = saveTx.execute(st ->
                    saveTopicScopedInTx(tenantId, userId, text, topicKey, conversationId));
                if (out != null && !out.overwritten().isEmpty()) {
                    // 会话内「记忆已更新」小卡（题8）：旧→新，随本回合 memory_turn 帧下发。
                    // 台账只在提交成功后按「该提交轮真正覆盖了谁」补记（批 7 多审 P1-b）——
                    // 它是内存 Map，不随数据库事务回滚，写在事务核里会给回滚轮留假帧。
                    // 键用 threadId 而非 ThreadLocal 里的 runId——工具跑在 loom 线程，读不到 run 上下文
                    try {
                        for (String old : out.overwritten()) {
                            turnLedger.recordSuperseded(threadId, old, text);
                        }
                    } catch (RuntimeException ex) {
                        log.debug("[AssistantMemory] 覆盖事件记台账失败(忽略): {}", ex.getMessage());
                    }
                }
                return out;
            } catch (DuplicateKeyException | PessimisticLockingFailureException e) {
                // D1：并发赢家已把同主题 ACTIVE 占住（库层唯一索引拦下插入）。
                // PostgreSQL 里撞索引的事务已废，必须换一个新事务重读主题清单再来一轮；
                // 40P01/55P03（死锁/锁等待失败）事务同样已废，同一套换新事务重试（多审席2实读映射）。
                if (e instanceof DuplicateKeyException dke && !isTopicFenceCollision(dke)) {
                    throw dke; // 撞的不是同主题栅栏（如主键）——属数据问题，不当并发竞态吞掉
                }
                if (attempt >= SAVE_MAX_ATTEMPTS) {
                    // 日志分型（二轮席1）：撞索引=并发竞态；锁等待失败/死锁=别的写者挡路，别混记
                    if (e instanceof DuplicateKeyException) {
                        log.warn("[AssistantMemory] 同主题并发保存连续 {} 轮撞唯一索引，放弃: tenant={} user={} topic={}",
                            attempt, tenantId, userId, topicKey);
                    } else {
                        log.warn("[AssistantMemory] 保存锁等待失败/死锁连续 {} 轮，放弃: tenant={} user={} topic={}",
                            attempt, tenantId, userId, topicKey);
                    }
                    return new SaveOutcome("这条正在被同时保存，请稍后再试一次", null, List.of(), true);
                }
                try {
                    Thread.sleep(10L * attempt); // 廉价抖动退避（二轮席1）：连续快重试会放大锁竞争
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return new SaveOutcome("这条正在被同时保存，请稍后再试一次", null, List.of(), true);
                }
            }
        }
    }

    /** 只把「同主题栅栏索引」的冲突当并发竞态重试；其它唯一冲突原样上抛 */
    private static boolean isTopicFenceCollision(DuplicateKeyException e) {
        String msg = e.getMessage();
        return msg != null && msg.contains(TOPIC_FENCE_INDEX);
    }

    /**
     * 事务核心（D1）：重读主题清单 → 预生成新条 id → 先把旧条腾成 SUPERSEDED → 再插入新条。
     *
     * <p>顺序不可反：唯一索引只认 ACTIVE，旧条不先腾位，插入会撞自己事务里还没提交的覆盖；
     * id 必须预生成（{@link IdWorker}），因为 superseded_by 要在插入前就指向新条。
     * 每轮重试都重新读主题清单（赢家的条这时才可见），输家转化为「后到的保存覆盖先到的」，
     * 与串行语义一致。这里不做任何不可回滚的副作用（台账在提交成功后由调用方补记）。</p>
     */
    private SaveOutcome saveTopicScopedInTx(String tenantId, Long userId, String content,
                                            String topicKey, Long conversationId) {
        List<AssistantMemory> sameTopic = topicKey == null
            ? List.of() : memoryMapper.listActiveByTopic(tenantId, userId, topicKey);

        AssistantMemory row = new AssistantMemory();
        row.setId(IdWorker.getId());
        row.setTenantId(tenantId);
        row.setUserId(userId);
        row.setContent(content);
        row.setSource("AI");
        row.setMemoryType(AssistantMemoryFacts.detectType(content));
        row.setTopicKey(topicKey);
        row.setStatus(AssistantMemoryFacts.ST_ACTIVE);
        row.setConversationId(conversationId);
        row.setCreateBy(userId);
        row.setCreateTime(new Date());

        // 题8：同主题旧条留痕（不删、可回滚）——先腾位后插入，同一事务原子提交
        List<String> overwritten = new ArrayList<>();
        Date now = new Date();
        for (AssistantMemory old : sameTopic) {
            if (memoryMapper.supersedeOwn(tenantId, userId, old.getId(), row.getId(), now) > 0
                // 同内容并发保存时，输家的重试轮会腾掉赢家那条一模一样的条目——
                // X→X 的帧/文案没有信息量，不进 overwritten（多审席2 检查单4）
                && !old.getContent().equals(content)) {
                overwritten.add(old.getContent());
            }
        }
        memoryMapper.insert(row);
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
            AssistantConversation conv = conversationMapper.selectByThread(tenantId, threadId);
            return conv == null ? null : conv.getId();
        } catch (RuntimeException e) {
            log.debug("[AssistantMemory] 出处会话反查失败(忽略): {}", e.getMessage());
            return null;
        }
    }
}
