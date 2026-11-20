package org.dromara.test.assistant;

import com.smartdock.assistant.domain.model.DcsAssistantMemory;
import com.smartdock.assistant.legacy.core.AssistantMemoryTurnLedger;
import com.smartdock.assistant.legacy.mapper.AssistantConversationMapper;
import com.smartdock.assistant.legacy.service.AssistantMemoryTurnService;
import com.smartdock.assistant.mapper.AssistantMemoryMapper;
import com.smartdock.assistant.mapper.AssistantMemoryPrefMapper;
import com.smartdock.assistant.service.AssistantMemoryService;
import com.smartdock.assistant.support.AssistantPiiFilter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批 7 前置 STEP 1（05C 题 3 案 1，D1）：同一人同一主题只能一条生效中。
 *
 * <p>病根：{@code saveReturningId} 原先「预读主题清单 → 插入 → 覆盖旧条」三步无事务
 * 包裹，并发保存同主题会留下两条 ACTIVE。修法三件套：部分唯一索引（数据库层栅栏）
 * + 保存口「先腾位后插入」同事务（TransactionTemplate）+ 撞唯一索引重读重试。</p>
 *
 * <p>本文件只钉 D1 行为；常规保存/脱敏/缓存口径在 {@link AssistantMemoryTest}。</p>
 */
@Tag("dev")
@Tag("local")
class AssistantMemoryD1Test {

    /** 「以后叫我老周」→ topic_key=call:name（AssistantMemoryFacts.TOPIC_RULES 同源） */
    private static final String TOPIC_CONTENT = "以后叫我老周";
    private static final String TOPIC_KEY = "call:name";

    private static AssistantPiiFilter fakePii() {
        AssistantPiiFilter f = mock(AssistantPiiFilter.class);
        when(f.mask(anyString(), anyString())).thenAnswer(
            inv -> String.valueOf(inv.getArgument(0, String.class)).replaceAll("1\\d{10}", "1****"));
        return f;
    }

    private static AssistantMemoryService newSvc(AssistantMemoryMapper mapper) {
        return new AssistantMemoryService(mapper, mock(AssistantMemoryPrefMapper.class), fakePii());
    }

    /** 直返式假事务管理器：getTransaction→提交两步直通，回调原地执行；真库事务语义由 db-verify 探针
     *  + scratch 演习覆盖（二轮席2 纠偏：传播级别由 T10 断言钉住，这里只保证回调跑得起来） */
    private static org.springframework.transaction.PlatformTransactionManager testTxManager() {
        org.springframework.transaction.PlatformTransactionManager tm =
            mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(tm.getTransaction(any(org.springframework.transaction.TransactionDefinition.class)))
            .thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        return tm;
    }

    private static AssistantMemoryTurnService newTurn(AssistantMemoryMapper mapper,
                                                      AssistantMemoryService svc) {
        return newTurn(mapper, svc, new AssistantMemoryTurnLedger());
    }

    private static AssistantMemoryTurnService newTurn(AssistantMemoryMapper mapper,
                                                      AssistantMemoryService svc,
                                                      AssistantMemoryTurnLedger ledger) {
        return newTurn(mapper, svc, ledger, testTxManager());
    }

    private static AssistantMemoryTurnService newTurn(AssistantMemoryMapper mapper,
                                                      AssistantMemoryService svc,
                                                      AssistantMemoryTurnLedger ledger,
                                                      org.springframework.transaction.PlatformTransactionManager tm) {
        return new AssistantMemoryTurnService(mapper,
            mock(AssistantConversationMapper.class),
            fakePii(),
            ledger,
            svc,
            tm);
    }

    private static DcsAssistantMemory activeRow(long id, String content) {
        DcsAssistantMemory r = new DcsAssistantMemory();
        r.setId(id);
        r.setTenantId("000000");
        r.setUserId(1L);
        r.setContent(content);
        r.setTopicKey(TOPIC_KEY);
        r.setStatus("ACTIVE");
        return r;
    }

    /** T1：并发赢家已把同主题 ACTIVE 占住（唯一索引拦下插入）→ 重读主题清单、覆盖赢家、再插成功 */
    @Test
    void saveRetriesWhenConcurrentWinnerTookTheTopic() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.countByUser(anyString(), anyLong())).thenReturn(1);
        DcsAssistantMemory winner = activeRow(900L, "以后叫我老王");
        when(mapper.listActiveByTopic(anyString(), anyLong(), eq(TOPIC_KEY)))
            .thenReturn(List.of())         // 第一轮：预读时还没看见赢家
            .thenReturn(List.of(winner));  // 撞索引后重读：赢家的 ACTIVE 条已在
        when(mapper.supersedeOwn(anyString(), anyLong(), anyLong(), anyLong(), any(Date.class))).thenReturn(1);
        when(mapper.insert(any(DcsAssistantMemory.class)))
            .thenThrow(new DuplicateKeyException("uk_dcs_asst_memory_active_topic"))
            .thenAnswer(inv -> 1);

        AssistantMemoryTurnLedger ledger = new AssistantMemoryTurnLedger();
        AssistantMemoryTurnService.SaveOutcome out =
            newTurn(mapper, newSvc(mapper), ledger).saveReturningId("000000", 1L, TOPIC_CONTENT, "th-race");

        assertTrue(out.message().startsWith("已记住"), "重试后应保存成功: " + out.message());
        assertNotNull(out.memoryId(), "落库行 id 应带回");
        verify(mapper, times(2)).insert(any(DcsAssistantMemory.class));
        verify(mapper).supersedeOwn(eq("000000"), eq(1L), eq(900L), anyLong(), any(Date.class));
        assertEquals(List.of("以后叫我老王"), out.overwritten(), "赢家的旧条应进「记忆已更新」卡");
        AssistantMemoryTurnLedger.TurnMemory frame = ledger.take("th-race");
        assertNotNull(frame, "提交成功轮的覆盖事件应记台账（发「记忆已更新」卡）");
        assertEquals(1, frame.superseded().size(), "只记赢家那一条（输掉的轮不记）");
    }

    /** T2：连续撞索引（异常并发/重试风暴）→ 放弃并给人话提示，不把 500 抛给用户 */
    @Test
    void saveGivesUpAfterRetriesWithFriendlyMessage() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.countByUser(anyString(), anyLong())).thenReturn(1);
        when(mapper.listActiveByTopic(anyString(), anyLong(), eq(TOPIC_KEY))).thenReturn(List.of());
        when(mapper.insert(any(DcsAssistantMemory.class)))
            .thenThrow(new DuplicateKeyException("uk_dcs_asst_memory_active_topic"));

        AssistantMemoryTurnService.SaveOutcome out =
            newTurn(mapper, newSvc(mapper)).saveReturningId("000000", 1L, TOPIC_CONTENT, null);

        assertFalse(out.message().startsWith("已记住"), "不应谎报成功: " + out.message());
        assertNull(out.memoryId());
        assertTrue(out.message().contains("稍后再试"), "应给人话提示: " + out.message());
    }

    /** T3：行内编辑改出的主题键与另一条生效记忆相撞 → 拒绝（false），不是 500 */
    @Test
    void editConflictingTopicIsRejectedNot500() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.selectById(5L)).thenReturn(activeRow(5L, "以前叫我老周"));
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.updateOwnContent(anyString(), anyLong(), anyLong(), anyString(), any(), any(), any(Date.class)))
            .thenThrow(new DuplicateKeyException("uk_dcs_asst_memory_active_topic"));

        assertFalse(newSvc(mapper).edit("000000", 1L, 5L, TOPIC_CONTENT),
            "主题键相撞应返回 false 而不是抛异常");
    }

    /** T4：先腾位后插入——覆盖旧条必须发生在插入之前（否则唯一索引把自己拦死），
     *  且 superseded_by 用的是预生成的新条 id（与最终 memoryId 同一个）。 */
    @Test
    void saveSupersedesOldTopicRowsBeforeInsertWithPregeneratedId() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.countByUser(anyString(), anyLong())).thenReturn(1);
        when(mapper.listActiveByTopic(anyString(), anyLong(), eq(TOPIC_KEY)))
            .thenReturn(List.of(activeRow(700L, "以前叫我老王")));
        when(mapper.supersedeOwn(anyString(), anyLong(), anyLong(), anyLong(), any(Date.class))).thenReturn(1);
        when(mapper.insert(any(DcsAssistantMemory.class))).thenAnswer(inv -> 1);

        AssistantMemoryTurnService.SaveOutcome out =
            newTurn(mapper, newSvc(mapper)).saveReturningId("000000", 1L, TOPIC_CONTENT, null);

        assertTrue(out.message().startsWith("已记住"));
        InOrder order = inOrder(mapper);
        order.verify(mapper).supersedeOwn(eq("000000"), eq(1L), eq(700L), anyLong(), any(Date.class));
        order.verify(mapper).insert(any(DcsAssistantMemory.class));

        ArgumentCaptor<Long> newId = ArgumentCaptor.forClass(Long.class);
        verify(mapper).supersedeOwn(anyString(), anyLong(), anyLong(), newId.capture(), any(Date.class));
        assertNotNull(newId.getValue(), "superseded_by 应指向预生成的新条 id");
        assertEquals(newId.getValue(), out.memoryId(), "预生成 id 与最终落库 id 应一致");
    }

    /**
     * T5（多审 P1-b 幽灵帧，席1/席2/席4 一致）：第一轮「腾位成功→插入撞唯一索引→整轮回滚」，
     * 内存台账不随数据库事务回滚——修复前会留下假「记忆已更新」帧。台账必须只在提交成功后
     * 按该轮 overwritten 补记，回滚轮一条不留。
     */
    @Test
    void ghostFrameNotRecordedWhenRoundRollsBack() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.countByUser(anyString(), anyLong())).thenReturn(1);
        DcsAssistantMemory oldActive = activeRow(800L, "以前叫我老王");
        when(mapper.listActiveByTopic(anyString(), anyLong(), eq(TOPIC_KEY)))
            .thenReturn(List.of(oldActive))   // 第一轮：腾位成功，但插入撞索引 → 整轮回滚
            .thenReturn(List.of());           // 第二轮：重读为空 → 干净插入成功
        when(mapper.supersedeOwn(anyString(), anyLong(), eq(800L), anyLong(), any(Date.class))).thenReturn(1);
        when(mapper.insert(any(DcsAssistantMemory.class)))
            .thenThrow(new DuplicateKeyException("uk_dcs_asst_memory_active_topic"))
            .thenAnswer(inv -> 1);

        AssistantMemoryTurnLedger ledger = new AssistantMemoryTurnLedger();
        AssistantMemoryTurnService.SaveOutcome out =
            newTurn(mapper, newSvc(mapper), ledger).saveReturningId("000000", 1L, TOPIC_CONTENT, "th-ghost");

        assertTrue(out.message().startsWith("已记住"), "重试轮应保存成功: " + out.message());
        assertTrue(out.overwritten().isEmpty(), "回滚轮的腾位不算数，最终轮没覆盖任何条");
        AssistantMemoryTurnLedger.TurnMemory frame = ledger.take("th-ghost");
        assertTrue(frame == null || frame.superseded().isEmpty(),
            "回滚轮不得留台账帧（假「记忆已更新」卡）: " + (frame == null ? "null" : frame.superseded()));
    }

    /** T6（多审 P2，约束名白名单）：撞的不是同主题栅栏索引（如主键冲突）→ 原样上抛不当并发重试 */
    @Test
    void foreignDuplicateKeyPropagates() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.countByUser(anyString(), anyLong())).thenReturn(1);
        when(mapper.listActiveByTopic(anyString(), anyLong(), eq(TOPIC_KEY))).thenReturn(List.of());
        when(mapper.insert(any(DcsAssistantMemory.class)))
            .thenThrow(new DuplicateKeyException("pk_dcs_assistant_memory"));

        assertThrows(DuplicateKeyException.class,
            () -> newTurn(mapper, newSvc(mapper)).saveReturningId("000000", 1L, TOPIC_CONTENT, null),
            "非栅栏索引的唯一冲突应上抛（属数据问题，不是并发竞态）");
    }

    /**
     * T7（多审 P1-a 消费侧口径钉子）：陈旧轮 supersedeOwn 返回 0（行已被赢家腾走/归档，
     * WHERE 带 status='ACTIVE' 时匹配不到）→ 不进 overwritten、不留台账帧。
     * SQL 谓词语义已在 scratch 真库探针实证（db-verify2b：旧行返回 1、加谓词后 0）。
     */
    @Test
    void staleSupersedeYieldsNoOverwrittenNorLedgerFrame() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.countByUser(anyString(), anyLong())).thenReturn(1);
        DcsAssistantMemory stale = activeRow(850L, "以前叫我老王");
        when(mapper.listActiveByTopic(anyString(), anyLong(), eq(TOPIC_KEY)))
            .thenReturn(List.of(stale))   // 陈旧快照：这行已被赢家腾走
            .thenReturn(List.of());
        when(mapper.supersedeOwn(anyString(), anyLong(), eq(850L), anyLong(), any(Date.class))).thenReturn(0);
        when(mapper.insert(any(DcsAssistantMemory.class)))
            .thenThrow(new DuplicateKeyException("uk_dcs_asst_memory_active_topic"))
            .thenAnswer(inv -> 1);

        AssistantMemoryTurnLedger ledger = new AssistantMemoryTurnLedger();
        AssistantMemoryTurnService.SaveOutcome out =
            newTurn(mapper, newSvc(mapper), ledger).saveReturningId("000000", 1L, TOPIC_CONTENT, "th-stale");

        assertTrue(out.message().startsWith("已记住"));
        assertTrue(out.overwritten().isEmpty(), "0 行腾位不算覆盖");
        AssistantMemoryTurnLedger.TurnMemory frame = ledger.take("th-stale");
        assertTrue(frame == null || frame.superseded().isEmpty(), "0 行腾位不得留台账帧");
    }

    /**
     * T8（二轮席1 P2-1）：同内容并发保存——输家重试轮腾掉赢家那条一模一样的条目时，
     * X→X 不进 overwritten、不留台账帧（无信息量的「记忆已更新」卡不出）。
     * 钉死语义：把过滤条件 ! 取反/删掉，本测必红。
     */
    @Test
    void sameContentSupersedeDoesNotProduceOverwriteCard() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.countByUser(anyString(), anyLong())).thenReturn(1);
        // 同一提交轮里：腾掉的旧条内容与本次保存完全相同（并发同内容的赢家行）
        when(mapper.listActiveByTopic(anyString(), anyLong(), eq(TOPIC_KEY)))
            .thenReturn(List.of(activeRow(860L, TOPIC_CONTENT)));
        when(mapper.supersedeOwn(anyString(), anyLong(), eq(860L), anyLong(), any(Date.class))).thenReturn(1);
        when(mapper.insert(any(DcsAssistantMemory.class))).thenAnswer(inv -> 1);

        AssistantMemoryTurnLedger ledger = new AssistantMemoryTurnLedger();
        AssistantMemoryTurnService.SaveOutcome out =
            newTurn(mapper, newSvc(mapper), ledger).saveReturningId("000000", 1L, TOPIC_CONTENT, "th-same");

        assertTrue(out.message().startsWith("已记住"), "保存应成功");
        verify(mapper, times(1)).supersedeOwn(anyString(), anyLong(), eq(860L), anyLong(), any(Date.class));
        assertTrue(out.overwritten().isEmpty(), "X→X 同内容覆盖不进 overwritten（无信息量卡）");
        AssistantMemoryTurnLedger.TurnMemory frame = ledger.take("th-same");
        assertTrue(frame == null || frame.superseded().isEmpty(), "X→X 不得留台账帧");
    }

    /** T9（二轮席1 P2-2）：撞锁（40P01/55P03 → PessimisticLockingFailureException）与撞索引同路重试 */
    @Test
    void lockFailureRetriesLikeFenceCollision() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.countByUser(anyString(), anyLong())).thenReturn(1);
        when(mapper.listActiveByTopic(anyString(), anyLong(), eq(TOPIC_KEY))).thenReturn(List.of());
        when(mapper.insert(any(DcsAssistantMemory.class)))
            .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock timeout"))
            .thenAnswer(inv -> 1);

        AssistantMemoryTurnService.SaveOutcome out =
            newTurn(mapper, newSvc(mapper)).saveReturningId("000000", 1L, TOPIC_CONTENT, null);

        assertTrue(out.message().startsWith("已记住"), "锁等待失败应换新事务重试成功: " + out.message());
        assertFalse(out.abandoned(), "成功轮不得标 abandoned");
        verify(mapper, times(2)).insert(any(DcsAssistantMemory.class));
    }

    /** T9b（二轮席1 P2-2）：撞锁重试耗尽 → 放弃且 abandoned=true（动作审计记失败不记成功） */
    @Test
    void lockFailureGiveUpIsMarkedAbandoned() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.countByUser(anyString(), anyLong())).thenReturn(1);
        when(mapper.listActiveByTopic(anyString(), anyLong(), eq(TOPIC_KEY))).thenReturn(List.of());
        when(mapper.insert(any(DcsAssistantMemory.class)))
            .thenThrow(new org.springframework.dao.CannotAcquireLockException("lock timeout"));

        AssistantMemoryTurnService.SaveOutcome out =
            newTurn(mapper, newSvc(mapper)).saveReturningId("000000", 1L, TOPIC_CONTENT, null);

        assertFalse(out.message().startsWith("已记住"));
        assertTrue(out.abandoned(), "重试耗尽没存上必须标 abandoned（审计不记成功）");
    }

    /** T10（二轮席2 P2-1）：保存口的事务必须是 REQUIRES_NEW——删掉 setPropagationBehavior 也能被抓红 */
    @Test
    void saveTxUsesRequiresNewPropagation() {
        AssistantMemoryMapper mapper = mock(AssistantMemoryMapper.class);
        when(mapper.countByContent(anyString(), anyLong(), anyString())).thenReturn(0);
        when(mapper.countByUser(anyString(), anyLong())).thenReturn(1);
        when(mapper.listActiveByTopic(anyString(), anyLong(), eq(TOPIC_KEY))).thenReturn(List.of());
        when(mapper.insert(any(DcsAssistantMemory.class))).thenAnswer(inv -> 1);

        org.springframework.transaction.PlatformTransactionManager tm = testTxManager();
        newTurn(mapper, newSvc(mapper), new AssistantMemoryTurnLedger(), tm)
            .saveReturningId("000000", 1L, TOPIC_CONTENT, null);

        org.mockito.ArgumentCaptor<org.springframework.transaction.TransactionDefinition> def =
            org.mockito.ArgumentCaptor.forClass(org.springframework.transaction.TransactionDefinition.class);
        verify(tm).getTransaction(def.capture());
        assertEquals(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW,
            def.getValue().getPropagationBehavior(),
            "撞索引后 PG 事务已废，重试必须换新事务（REQUIRES_NEW），默认 REQUIRED 会被外层事务毒化");
    }
}
