package org.dromara.test.assistant;

import org.dromara.assistant.domain.DcsAssistantConversation;
import org.dromara.assistant.domain.DcsAssistantMessage;
import org.dromara.assistant.domain.DcsAssistantRun;
import org.dromara.assistant.service.AssistantConversationService;
import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批G G2 会话持久化+清理单测（2026-10-06）。
 * 判据：thread/run 归属首绑异主 403（G4 处置链数据源）；消息往返（assistant 落库脱敏）；
 * purge 租户隔离；保留期清理幂等。
 */
@Tag("p0probe")
@SpringBootTest
class G2ConversationTest {

    private static final String RUN_KEY = "g2-" + System.currentTimeMillis();
    private static final Long U1 = 910000000000101L;
    private static final Long U2 = 910000000000102L;

    @Autowired
    AssistantConversationService conversationService;
    @Autowired
    DataSource ds;

    /** 批H P1-2：归档会话拒写（「只读回放」后端强制）；恢复后放行 */
    @Test
    void archivedConversationRejectsWriteUntilRestored() {
        String thread = RUN_KEY + "-arch";
        Long conv = conversationService.ensureConversation("987311", U1, thread, "归档拒写");
        conversationService.archiveConversation("987311", U1, conv);
        ServiceException ex = assertThrows(ServiceException.class,
            () -> conversationService.ensureConversation("987311", U1, thread, "归档拒写"),
            "归档会话发消息必须 403");
        assertEquals(403, ex.getCode());
        conversationService.restoreConversation("987311", U1, conv);
        assertEquals(conv, conversationService.ensureConversation("987311", U1, thread, "归档拒写"),
            "恢复后同会话放行");
    }

    @Test
    void threadFirstBindRejectsCrossUser() {
        String thread = RUN_KEY + "-t1";
        Long conv1 = conversationService.ensureConversation("987311", U1, thread, "标题");
        assertNotNull(conv1);
        // 同租户异主 → 403（不区分不存在与无权, 防探测）
        ServiceException ex = assertThrows(ServiceException.class,
            () -> conversationService.ensureConversation("987311", U2, thread, "标题"),
            "thread 归属首绑后异主必须 403");
        assertEquals(403, ex.getCode());
        // 异租户同 threadId → 各自独立（租户隔离键）
        Long convOther = conversationService.ensureConversation("000000", U1, thread, "标题");
        assertNotEquals(conv1, convOther, "租户维度隔离");
    }

    @Test
    void runRegistryBindsOwnership() {
        String runId = RUN_KEY + "-r1";
        conversationService.registerRun("987311", U1, RUN_KEY + "-t2", runId);
        DcsAssistantRun bound = conversationService.runByRunId(runId);
        assertEquals(U1, bound.getUserId(), "runId 首现绑定归属");
        // 重放同 runId 异主 → 403（S-04① 防重放换人）
        ServiceException ex = assertThrows(ServiceException.class,
            () -> conversationService.registerRun("987311", U2, RUN_KEY + "-t2", runId));
        assertEquals(403, ex.getCode());
        // 同主重放（resume 同 runId）→ 幂等放行不换绑
        conversationService.registerRun("987311", U1, RUN_KEY + "-t2", runId);
        assertEquals(U1, conversationService.runByRunId(runId).getUserId());
    }

    @Test
    void messageRoundtripAssistantSideMasked() {
        String thread = RUN_KEY + "-t3";
        Long conv = conversationService.ensureConversation("987311", U1, thread, "消息往返");
        conversationService.appendUserMessage("987311", conv, U1, "请联系13800138000");
        conversationService.appendAssistantMessage("987311", conv, U1,
            "好的，已记录你的号码13800138000与邮箱 wang@x.com", null, "stop");
        List<DcsAssistantMessage> msgs = conversationService.listMessages("987311", U1, conv, 10, 0);
        assertEquals(2, msgs.size(), "一问一答两行");
        assertEquals("user", msgs.get(0).getRole());
        assertTrue(msgs.get(0).getContent().contains("13800138000"), "user 侧=X-08 明文例外通道");
        assertEquals("assistant", msgs.get(1).getRole());
        assertTrue(msgs.get(1).getContent().contains("138****"), "assistant 侧落库前脱敏: " + msgs.get(1).getContent());
        assertTrue(msgs.get(1).getContent().contains("w**@"), "邮箱脱敏形态 a**@域");
        // 异主读消息 → 403
        assertThrows(ServiceException.class,
            () -> conversationService.listMessages("987311", U2, conv, 10, 0));
    }

    @Test
    void purgeUserIsTenantScoped() throws Exception {
        String threadA = RUN_KEY + "-pa";
        String threadB = RUN_KEY + "-pb";
        Long convA = conversationService.ensureConversation("987311", U1, threadA, "A");
        conversationService.appendUserMessage("987311", convA, U1, "A 的消息");
        Long convB = conversationService.ensureConversation("000000", U1, threadB, "B");
        conversationService.appendUserMessage("000000", convB, U1, "B 的消息");

        int purged = conversationService.purgeUser("987311", U1);
        org.junit.jupiter.api.Assertions.assertTrue(purged >= 2, "至少删掉 A 会话+消息: " + purged);
        // 框架表清理独立于四表事务（表缺失环境 tolerated，且不得连坐回滚四表删除）
        conversationService.purgeAgentscopeSessions("987311", U1);

        assertNull(conversationService.conversationByThread("987311", threadA), "A 租户行已清");
        assertNotNull(conversationService.conversationByThread("000000", threadB), "B 租户不受影响");
    }

    @Test
    void userRoundReplayRejectedNotSilentlyIgnored() {
        String thread = RUN_KEY + "-rp";
        String runId = RUN_KEY + "-rp-run";
        conversationService.persistUserRound("987311", U1, thread, runId, "重放", "第一条消息");
        // goal 加固 H1：同主重放（断线重试同 runId）必须整体拒绝——此前静默吞掉会放行 agent
        // 重跑=双扣费+assistant 消息可双落；拒绝后框架转 RUN_ERROR，前端不再自动重试
        ServiceException ex = assertThrows(ServiceException.class,
            () -> conversationService.persistUserRound("987311", U1, thread, runId, "重放", "第一条消息"),
            "同 runId 同主重放必须拒绝");
        assertEquals(409, ex.getCode());
        assertTrue(ex.getMessage().contains("重复请求"), "话术指导用户刷新或重发: " + ex.getMessage());
        DcsAssistantConversation conv = conversationService.conversationByThread("987311", thread);
        List<DcsAssistantMessage> msgs = conversationService.listMessages("987311", U1, conv.getId(), 10, 0);
        assertEquals(1, msgs.size(), "user 消息仍只一行(拒绝不含双落): " + msgs.size());
    }

    @Test
    void retentionSweepDeletesExpiredAndIdempotent() throws Exception {
        Long conv = conversationService.ensureConversation("987311", U1, RUN_KEY + "-rt", "清理");
        conversationService.appendUserMessage("987311", conv, U1, "新消息(90 天内保留)");
        // 手工注入一条过期消息（91 天前）与一条过期会话
        exec("INSERT INTO dcs_assistant_message (id, tenant_id, conversation_id, user_id, role, content, create_time)"
            + " VALUES (" + (System.currentTimeMillis() % 100000) + ", '987311', " + conv + ", " + U1
            + ", 'user', 'old', CURRENT_TIMESTAMP - INTERVAL '91 days')");
        exec("INSERT INTO dcs_assistant_conversation (id, tenant_id, user_id, thread_id, title, create_time)"
            + " VALUES (" + (System.currentTimeMillis() % 100000 + 1) + ", '987311', " + U1 + ", '"
            + RUN_KEY + "-dead', '旧会话', CURRENT_TIMESTAMP - INTERVAL '181 days')");
        conversationService.retentionSweep(100);
        Long oldMsg = query("SELECT COUNT(*) FROM dcs_assistant_message WHERE content='old'");
        assertEquals(0L, oldMsg, "91 天消息已删");
        Long oldConv = query("SELECT COUNT(*) FROM dcs_assistant_conversation WHERE thread_id='" + RUN_KEY + "-dead'");
        assertEquals(0L, oldConv, "181 天会话已删");
        List<DcsAssistantMessage> fresh = conversationService.listMessages("987311", U1, conv, 10, 0);
        assertTrue(fresh.stream().anyMatch(m -> "新消息(90 天内保留)".equals(m.getContent())), "90 天内消息保留");
        // 幂等：再跑一遍无异常无残留
        conversationService.retentionSweep(100);
    }

    private void exec(String sql) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private Long query(String sql) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             var rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : null;
        }
    }
}
