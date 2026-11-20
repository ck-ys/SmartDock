package com.smartdock.assistant.service;

import com.smartdock.assistant.domain.gateway.InstanceIdentityGateway;
import com.smartdock.assistant.domain.model.DcsAssistantConversation;
import com.smartdock.assistant.domain.model.DcsAssistantRun;
import com.smartdock.assistant.domain.model.DcsAssistantRunIssue;
import com.smartdock.assistant.mapper.AssistantConversationMapper;
import com.smartdock.assistant.mapper.AssistantMessageMapper;
import com.smartdock.assistant.mapper.AssistantRunIssueMapper;
import com.smartdock.assistant.mapper.AssistantRunMapper;
import com.smartdock.assistant.support.AssistantPiiFilter;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 批 7 · 7C-1（2026-11-20）：{@link RunLedgerService} 的语义钉子。
 *
 * <p>为什么补：本类是从 legacy 698 行会话服务里整段搬出来的，搬迁最容易出的错不是编译，
 * 是「语义悄悄漂移」——归属校验漏一个谓词、并发门顺序调换、问题行的异常吞并范围变宽，
 * 这些都不会让编译红，只会让线上多一条越权或少一条问题行。故这里只钉三件不改就会出事的事：
 * ① 首绑的三道门（异主 403 / 归档 403 / 同主幂等返回同一 id）；
 * ② run 登记的两条 403 与「同主重放返回 false」；
 * ③ 问题行的三道短路（runId/code 空、run 不存在、非 RUNNING）与 detail 的脱敏顺序。</p>
 *
 * <p>纯 Mockito 不起 Spring：不连库、不连 Redis、不依赖时钟。</p>
 */
@Tag("local")
class RunLedgerServiceUnitTest {

    private static final String TENANT = "000000";
    private static final Long USER = 9100000001L;
    private static final String THREAD = "th-1";

    private final AssistantConversationMapper conversationMapper = mock(AssistantConversationMapper.class);
    private final AssistantMessageMapper messageMapper = mock(AssistantMessageMapper.class);
    private final AssistantRunMapper runMapper = mock(AssistantRunMapper.class);
    private final AssistantRunIssueMapper issueMapper = mock(AssistantRunIssueMapper.class);
    private final AssistantPiiFilter piiFilter = mock(AssistantPiiFilter.class);
    private final InstanceIdentityGateway instanceIdentity = mock(InstanceIdentityGateway.class);
    private final MessageWriteService messageWriteService = mock(MessageWriteService.class);

    private RunLedgerService svc() {
        return new RunLedgerService(conversationMapper, messageMapper, runMapper, issueMapper,
            piiFilter, instanceIdentity, messageWriteService);
    }

    // ── 钉 ① 首绑三道门 ────────────────────────────────────────────────

    @Test
    void 首绑_会话不存在时插行后回查_返回新会话id() {
        DcsAssistantConversation created = conv(77L, USER, "ACTIVE");
        when(conversationMapper.selectByThread(TENANT, THREAD)).thenReturn(null, created);

        assertEquals(77L, svc().ensureConversation(TENANT, USER, THREAD, "标题"));
        verify(conversationMapper).insertIgnoreThread(any(DcsAssistantConversation.class));
    }

    @Test
    void 首绑_异主会话一律403且不区分不存在与无权() {
        when(conversationMapper.selectByThread(TENANT, THREAD)).thenReturn(conv(77L, 999L, "ACTIVE"));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> svc().ensureConversation(TENANT, USER, THREAD, "标题"));
        assertEquals(Integer.valueOf(403), ex.getCode(), "异主必须 403（防探测：文案与不存在同款）");
    }

    @Test
    void 首绑_归档会话403且提示可恢复() {
        when(conversationMapper.selectByThread(TENANT, THREAD)).thenReturn(conv(77L, USER, "ARCHIVED"));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> svc().ensureConversation(TENANT, USER, THREAD, "标题"));
        assertEquals(Integer.valueOf(403), ex.getCode());
        assertTrue(ex.getMessage().contains("归档"), "文案要指明归档只读，否则用户不知道怎么继续");
    }

    // ── 钉 ② run 登记 ─────────────────────────────────────────────────

    @Test
    void 登记_runId为空视为新轮不落库() {
        assertTrue(svc().registerRun(TENANT, USER, THREAD, null));
        assertTrue(svc().registerRun(TENANT, USER, THREAD, "   "));
        verifyNoInteractions(runMapper);
    }

    @Test
    void 登记_首现写入本实例归属并返回true() {
        when(instanceIdentity.currentInstanceId()).thenReturn("host:1:abc");
        when(runMapper.insertIgnoreRun(any(DcsAssistantRun.class))).thenReturn(1);
        when(runMapper.selectByRunId("r-1")).thenReturn(run("r-1", USER, TENANT, "RUNNING"));

        assertTrue(svc().registerRun(TENANT, USER, THREAD, "r-1"));

        ArgumentCaptor<DcsAssistantRun> cap = ArgumentCaptor.forClass(DcsAssistantRun.class);
        verify(runMapper).insertIgnoreRun(cap.capture());
        assertEquals("host:1:abc", cap.getValue().getOwnerInstance(),
            "归属实例必须来自 gateway（写错=收割器把本机活 run 当别人的死 run 收掉）");
    }

    @Test
    void 登记_同主重放返回false() {
        when(runMapper.insertIgnoreRun(any(DcsAssistantRun.class))).thenReturn(0);
        when(runMapper.selectByRunId("r-1")).thenReturn(run("r-1", USER, TENANT, "RUNNING"));

        assertFalse(svc().registerRun(TENANT, USER, THREAD, "r-1"), "同主重放=已有行，交给上层 409");
    }

    @Test
    void 登记_异主或异租户一律403() {
        when(runMapper.insertIgnoreRun(any(DcsAssistantRun.class))).thenReturn(0);
        when(runMapper.selectByRunId("r-1")).thenReturn(run("r-1", 999L, TENANT, "RUNNING"));
        assertEquals(Integer.valueOf(403), assertThrows(ServiceException.class,
            () -> svc().registerRun(TENANT, USER, THREAD, "r-1")).getCode());

        when(runMapper.selectByRunId("r-2")).thenReturn(run("r-2", USER, "other", "RUNNING"));
        assertEquals(Integer.valueOf(403), assertThrows(ServiceException.class,
            () -> svc().registerRun(TENANT, USER, THREAD, "r-2")).getCode(),
            "异租户同样必须拒（runId 全局唯一不豁免租户隔离）");
    }

    // ── 钉 ③ 一轮三表写的门序与短路 ────────────────────────────────────

    @Test
    void 一轮落库_重放时409且不落user消息() {
        when(conversationMapper.selectByThread(TENANT, THREAD)).thenReturn(conv(77L, USER, "ACTIVE"));
        when(runMapper.insertIgnoreRun(any(DcsAssistantRun.class))).thenReturn(0);
        when(runMapper.selectByRunId("r-1")).thenReturn(run("r-1", USER, TENANT, "RUNNING"));

        ServiceException ex = assertThrows(ServiceException.class,
            () -> svc().persistUserRound(TENANT, USER, THREAD, "r-1", "标题", "内容"));
        assertEquals(Integer.valueOf(409), ex.getCode());
        verify(messageWriteService, never()).appendUserMessage(anyString(), anyLong(), anyLong(), anyString());
    }

    @Test
    void 一轮落库_存在其它在途run时409() {
        when(conversationMapper.selectByThread(TENANT, THREAD)).thenReturn(conv(77L, USER, "ACTIVE"));
        when(runMapper.insertIgnoreRun(any(DcsAssistantRun.class))).thenReturn(1);
        when(runMapper.selectByRunId("r-1")).thenReturn(run("r-1", USER, TENANT, "RUNNING"));
        when(runMapper.countOtherRunning(TENANT, THREAD, USER, "r-1")).thenReturn(1);

        ServiceException ex = assertThrows(ServiceException.class,
            () -> svc().persistUserRound(TENANT, USER, THREAD, "r-1", "标题", "内容"));
        assertEquals(Integer.valueOf(409), ex.getCode());
        assertTrue(ex.getMessage().contains("还在生成中"));
        verify(messageWriteService, never()).appendUserMessage(anyString(), anyLong(), anyLong(), anyString());
    }

    @Test
    void 一轮落库_先取线程锁再插行再查在途() {
        when(conversationMapper.selectByThread(TENANT, THREAD)).thenReturn(conv(77L, USER, "ACTIVE"));
        when(runMapper.insertIgnoreRun(any(DcsAssistantRun.class))).thenReturn(1);
        when(runMapper.selectByRunId("r-1")).thenReturn(run("r-1", USER, TENANT, "RUNNING"));

        svc().persistUserRound(TENANT, USER, THREAD, "r-1", "标题", "内容");

        var order = org.mockito.Mockito.inOrder(runMapper, conversationMapper, messageWriteService);
        order.verify(runMapper).acquireThreadSendLock(TENANT, USER, THREAD);
        order.verify(conversationMapper).selectByThread(TENANT, THREAD);
        order.verify(runMapper).insertIgnoreRun(any(DcsAssistantRun.class));
        order.verify(runMapper).countOtherRunning(TENANT, THREAD, USER, "r-1");
        order.verify(messageWriteService).appendUserMessage(eq(TENANT), eq(77L), eq(USER), eq("内容"));
    }

    @Test
    void 一轮落库_空白内容不落user消息() {
        when(conversationMapper.selectByThread(TENANT, THREAD)).thenReturn(conv(77L, USER, "ACTIVE"));
        when(runMapper.insertIgnoreRun(any(DcsAssistantRun.class))).thenReturn(1);
        when(runMapper.selectByRunId("r-1")).thenReturn(run("r-1", USER, TENANT, "RUNNING"));

        svc().persistUserRound(TENANT, USER, THREAD, "r-1", "标题", "  ");
        verify(messageWriteService, never()).appendUserMessage(anyString(), anyLong(), anyLong(), anyString());
    }

    // ── 钉 ④ 问题行 ───────────────────────────────────────────────────

    @Test
    void 问题行_code空或runId空直接跳过() {
        RunLedgerService s = svc();
        s.recordRunIssue(null, "empty", "x");
        s.recordRunIssue("r-1", null, "x");
        s.recordRunIssue("r-1", "  ", "x");
        verifyNoInteractions(runMapper, issueMapper);
    }

    @Test
    void 问题行_run不存在不落行() {
        when(runMapper.selectByRunId("r-1")).thenReturn(null);
        svc().recordRunIssue("r-1", "empty", "x");
        verifyNoInteractions(issueMapper);
    }

    @Test
    void 问题行_只挂仍在跑的run() {
        when(runMapper.selectByRunId("r-1")).thenReturn(run("r-1", USER, TENANT, "COMPLETED"));
        svc().recordRunIssueForRunningRun("r-1", "rate", "429 too many");
        verifyNoInteractions(issueMapper);

        when(runMapper.selectByRunId("r-2")).thenReturn(run("r-2", USER, TENANT, "RUNNING"));
        svc().recordRunIssueForRunningRun("r-2", "rate", "429 too many");
        verify(issueMapper).insertIssue(any(DcsAssistantRunIssue.class));
    }

    @Test
    void 问题行_先纯函数脱敏再按租户掩码() {
        when(runMapper.selectByRunId("r-1")).thenReturn(run("r-1", USER, TENANT, "FAILED"));
        when(conversationMapper.selectByThread(TENANT, THREAD)).thenReturn(conv(77L, USER, "ACTIVE"));
        when(messageMapper.selectLatestUserMessageId(TENANT, 77L, USER)).thenReturn(555L);
        when(piiFilter.mask(anyString(), eq(TENANT))).thenAnswer(inv -> "MASKED(" + inv.getArgument(0) + ")");

        svc().recordRunIssue("r-1", "auth", "401 sk-abcdefgh12345678");

        // 顺序判据必须落在「掩码器收到了什么」上：只看落库结果的话，先掩码后脱敏也能过
        // （两序都能把 sk- 抹掉），断言就分不出顺序——多审口径「测试变绿≠问题解决」。
        ArgumentCaptor<String> maskIn = ArgumentCaptor.forClass(String.class);
        verify(piiFilter).mask(maskIn.capture(), eq(TENANT));
        assertFalse(maskIn.getValue().contains("sk-abcdefgh12345678"),
            "掩码器拿到的入参必须已被纯函数脱敏（否则明文密钥进过掩码器=多一处暴露面）");

        ArgumentCaptor<DcsAssistantRunIssue> cap = ArgumentCaptor.forClass(DcsAssistantRunIssue.class);
        verify(issueMapper).insertIssue(cap.capture());
        DcsAssistantRunIssue row = cap.getValue();
        assertFalse(row.getDetail().contains("sk-abcdefgh"), "密钥必须先被纯函数抹掉");
        assertTrue(row.getDetail().startsWith("MASKED("), "然后才过租户 PII 掩码（顺序反了=掩码看不到明文密钥）");
        assertEquals(77L, row.getConversationId());
        assertEquals(555L, row.getMessageId());
    }

    @Test
    void 问题行_落库异常自吞不影响终态链() {
        when(runMapper.selectByRunId("r-1")).thenReturn(run("r-1", USER, TENANT, "FAILED"));
        when(issueMapper.insertIssue(any(DcsAssistantRunIssue.class))).thenThrow(new RuntimeException("db down"));

        // 不抛=锦上添花不连坐；抛了就会把终态链一起带崩
        svc().recordRunIssue("r-1", "other", "boom");
    }

    // ── 钉 ⑤ 问题行读口 ───────────────────────────────────────────────

    @Test
    void 按runId取问题行_异租户或异主一律null() {
        DcsAssistantRunIssue row = issue("r-1", USER, TENANT);
        when(issueMapper.selectLatestByRunId("r-1")).thenReturn(row);

        assertEquals(row, svc().latestRunIssue(TENANT, USER, "r-1"));
        assertNull(svc().latestRunIssue("other", USER, "r-1"), "异租户不得回行");
        assertNull(svc().latestRunIssue(TENANT, 999L, "r-1"), "异主不得回行");
        assertNull(svc().latestRunIssue(TENANT, USER, "r-404"), "无行返回 null");
    }

    @Test
    void 同thread在途runId_非RUNNING返回null() {
        when(runMapper.selectByThread(TENANT, THREAD, USER, 1)).thenReturn(List.of());
        assertNull(svc().latestRunningRunIdOfThread(TENANT, USER, THREAD), "无行=null");

        when(runMapper.selectByThread(TENANT, THREAD, USER, 1))
            .thenReturn(List.of(run("r-1", USER, TENANT, "COMPLETED")));
        assertNull(svc().latestRunningRunIdOfThread(TENANT, USER, THREAD), "非 RUNNING=null（不叠问题行）");

        when(runMapper.selectByThread(TENANT, THREAD, USER, 1))
            .thenReturn(List.of(run("r-9", USER, TENANT, "RUNNING")));
        assertEquals("r-9", svc().latestRunningRunIdOfThread(TENANT, USER, THREAD));
    }

    // ── 夹具 ─────────────────────────────────────────────────────────

    private static DcsAssistantConversation conv(Long id, Long userId, String status) {
        DcsAssistantConversation c = new DcsAssistantConversation();
        c.setId(id);
        c.setTenantId(TENANT);
        c.setUserId(userId);
        c.setThreadId(THREAD);
        c.setStatus(status);
        return c;
    }

    private static DcsAssistantRun run(String runId, Long userId, String tenantId, String status) {
        DcsAssistantRun r = new DcsAssistantRun();
        r.setRunId(runId);
        r.setUserId(userId);
        r.setTenantId(tenantId);
        r.setThreadId(THREAD);
        r.setStatus(status);
        return r;
    }

    private static DcsAssistantRunIssue issue(String runId, Long userId, String tenantId) {
        DcsAssistantRunIssue i = new DcsAssistantRunIssue();
        i.setRunId(runId);
        i.setUserId(userId);
        i.setTenantId(tenantId);
        i.setCode("rate");
        return i;
    }
}
