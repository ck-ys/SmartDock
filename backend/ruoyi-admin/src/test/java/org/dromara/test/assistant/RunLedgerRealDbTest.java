package org.dromara.test.assistant;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder;
import com.smartdock.assistant.domain.gateway.InstanceIdentityGateway;
import com.smartdock.assistant.domain.model.AssistantRun;
import com.smartdock.assistant.domain.model.AssistantRunIssue;
import com.smartdock.assistant.mapper.AssistantConversationMapper;
import com.smartdock.assistant.mapper.AssistantMessageMapper;
import com.smartdock.assistant.mapper.AssistantRunIssueMapper;
import com.smartdock.assistant.mapper.AssistantRunMapper;
import com.smartdock.assistant.service.MessageWriteService;
import com.smartdock.assistant.service.RunLedgerService;
import com.smartdock.assistant.support.AssistantPiiFilter;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.mockito.Mockito.mock;

/**
 * 批 10 · 10A-4：{@link RunLedgerService} 的真库语义钉子（tag {@code realdb}，默认不跑）。
 *
 * <p><b>为什么需要这一层</b>：{@code RunLedgerServiceUnitTest} 用 Mockito 钉的是「服务层调了谁、
 * 传了什么」，SQL 本身被 mock 掉了。而本类 15 个 public 方法里有 5 个（{@code touchRun} /
 * {@code listStuckRuns} / {@code finalizeStuckRun} / {@code listRunningOlderThan} /
 * {@code listRunIssues}）在仓内**一次都没被调用过**，另外 3 个（{@code finishRun} /
 * {@code runByRunId} / {@code finalizeStalledRun}）只在 mock 上被调过。这些方法的风险全在
 * 注解 SQL 的谓词里——条件迁移写成无条件、COALESCE 漏了、RETURNING 防双退丢了、租户谓词写反——
 * 全都不会让编译红，只会让线上多一次越权或一条错误终态。</p>
 *
 * <p><b>连接口径</b>：一律从环境变量读，仓内不留任何地址口令（AGENTS.md：内网 IP / 密码
 * 不许进任何 git 追踪文件）。变量没设齐时本类 {@code abort} 成 skipped，而不是 failed——
 * 「跳过」和「验过」必须能分辨，故结果要在人话日志里如实登记为跳过或通过。</p>
 *
 * <p><b>为什么照抄不了 {@code P10JdbcStoreTest}</b>：它把连接串硬编码成 {@code 127.0.0.1:5432}
 * 且库名写死 {@code smartdock}，本机没有那个实例；照抄必红。本类只借它「手工装配
 * SqlSessionFactory」的手法，不借它的连接。</p>
 *
 * <p><b>清理口径</b>：本类所有行都用 {@link #RUN_PREFIX} 前缀，{@code @BeforeEach} 与
 * {@code @AfterAll} 都按前缀删，绝不碰业务行。租户号 {@link #TENANT} 同理。</p>
 */
@Tag("realdb")
class RunLedgerRealDbTest {

    /** 专用租户号，与演示数据（000000）区分开 */
    private static final String TENANT = "b10realdb";
    /** runId 前缀：本类唯一写入标记，删行锚点 */
    private static final String RUN_PREFIX = "b10-realdb-";
    private static final Long USER = 9100000010L;
    private static final Long OTHER_USER = 9100000011L;
    private static final String THREAD = "b10-realdb-th-1";
    private static final Long CONV_ID = 9100000020L;

    private static SqlSessionFactory factory;
    private static String skipReason;

    @BeforeAll
    static void initFactory() {
        String url = env("SMARTDOCK_TEST_PG_URL");
        String user = env("SMARTDOCK_TEST_PG_USER");
        String password = env("SMARTDOCK_TEST_PG_PASSWORD");
        if (url == null || user == null) {
            skipReason = "未设置 SMARTDOCK_TEST_PG_URL / SMARTDOCK_TEST_PG_USER，真库用例跳过（不是通过）";
            return;
        }
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(url);
        ds.setUser(user);
        ds.setPassword(password == null ? "" : password);

        MybatisConfiguration cfg = new MybatisConfiguration();
        cfg.setEnvironment(new Environment("b10-realdb", new JdbcTransactionFactory(), ds));
        cfg.setMapUnderscoreToCamelCase(true);
        cfg.addMapper(AssistantRunMapper.class);
        cfg.addMapper(AssistantRunIssueMapper.class);
        cfg.addMapper(AssistantConversationMapper.class);
        cfg.addMapper(AssistantMessageMapper.class);
        factory = new MybatisSqlSessionFactoryBuilder().build(cfg);
        cleanup();
    }

    @AfterAll
    static void tearDown() {
        if (factory != null) {
            cleanup();
        }
    }

    @BeforeEach
    void requireDb() {
        if (factory == null) {
            abort(skipReason);
        }
        cleanup();
    }

    private static RunLedgerService service(SqlSession session) {
        return new RunLedgerService(
            session.getMapper(AssistantConversationMapper.class),
            session.getMapper(AssistantMessageMapper.class),
            session.getMapper(AssistantRunMapper.class),
            session.getMapper(AssistantRunIssueMapper.class),
            mock(AssistantPiiFilter.class),
            mock(InstanceIdentityGateway.class),
            mock(MessageWriteService.class));
    }

    /** 服务层收的 runId 一律是本形态；夹具与断言都经此换算，避免「插的是带前缀、查的是裸 id」这类自造红 */
    private static String rid(String bare) {
        return RUN_PREFIX + bare;
    }

    // ══════════════════════════════════════════════════════════════════
    // ① runByRunId：按 runId 取行
    // ══════════════════════════════════════════════════════════════════

    @Test
    void runByRunId_按runId取到行_不存在返回null() {
        insertRun(1L, "r1", "RUNNING", null, null, 0);
        try (SqlSession s = factory.openSession(true)) {
            RunLedgerService svc = service(s);
            AssistantRun got = svc.runByRunId(rid("r1"));
            assertNotNull(got, "插入后必须能按 runId 取回");
            assertEquals(TENANT, got.getTenantId());
            assertEquals(USER, got.getUserId());
            assertEquals(THREAD, got.getThreadId());
            assertEquals("RUNNING", got.getStatus());

            assertNull(svc.runByRunId(rid("nope")), "不存在的 runId 返回 null（调用方靠它跳过）");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ② finishRun：条件 RUNNING → 终态，且不覆写已被收割的行
    // ══════════════════════════════════════════════════════════════════

    @Test
    void finishRun_只从RUNNING迁出_迟到终态不覆写已收割行() {
        insertRun(1L, "r1", "RUNNING", null, null, 0);
        insertRun(2L, "r2", "INTERRUPTED", "no-terminal-event", null, 0);
        try (SqlSession s = factory.openSession(true)) {
            RunLedgerService svc = service(s);

            assertEquals(1, svc.finishRun(rid("r1"), "COMPLETED", "normal"), "RUNNING 行应被迁出");
            AssistantRun after = svc.runByRunId(rid("r1"));
            assertEquals("COMPLETED", after.getStatus());
            assertEquals("normal", after.getEndReason());

            // 批6-#2 承重语义：收割器已把停滞 run 迁 INTERRUPTED，迟到的事件流终态不得覆写回去
            assertEquals(0, svc.finishRun(rid("r2"), "COMPLETED", "normal"),
                "已终态行必须 0 行受影响（覆写=线上把中断的轮谎报成完成）");
            assertEquals("INTERRUPTED", svc.runByRunId(rid("r2")).getStatus());
            assertEquals("no-terminal-event", svc.runByRunId(rid("r2")).getEndReason());

            assertEquals(0, svc.finishRun(rid("nope"), "COMPLETED", "normal"), "不存在的 run 0 行");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ③ touchRun：只续 RUNNING 行的心跳，不碰终态
    // ══════════════════════════════════════════════════════════════════

    @Test
    void touchRun_只续RUNNING行_last_event_at不被终态行改写() {
        insertRun(1L, "r-run", "RUNNING", null, LocalDateTime.of(2026, 1, 1, 0, 0), 0);
        insertRun(2L, "r-done", "COMPLETED", "normal", LocalDateTime.of(2026, 1, 1, 0, 0), 0);
        try (SqlSession s = factory.openSession(true)) {
            RunLedgerService svc = service(s);

            svc.touchRun(rid("r-run"));
            LocalDateTime touched = lastEventAt("r-run");
            assertNotNull(touched, "RUNNING 行的心跳必须被写入");
            assertTrue(touched.isAfter(LocalDateTime.of(2026, 1, 2, 0, 0)),
                "心跳必须被推到当前时刻，实际：" + touched);

            svc.touchRun(rid("r-done"));
            assertEquals(LocalDateTime.of(2026, 1, 1, 0, 0), lastEventAt("r-done"),
                "终态行的心跳不许被续（续了=收割器把已结束的轮当活的）");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ④ listStuckRuns：COALESCE(last_event_at, create_time) 判超时
    // ══════════════════════════════════════════════════════════════════

    @Test
    void listStuckRuns_按COALESCE判超时_无心跳回退create_time() {
        // 30 分钟前创建、从未有事件 → 靠 create_time 落进 15 分钟窗口
        insertRun(1L, "r-stuck-noevent", "RUNNING", null, null, 30);
        // 30 分钟前创建，但 1 分钟前刚有心跳 → 不该被判死
        insertRun(2L, "r-stuck-live", "RUNNING", null, minutesAgo(1), 30);
        // 终态行不该进候选
        insertRun(3L, "r-stuck-done", "COMPLETED", "normal", null, 30);
        try (SqlSession s = factory.openSession(true)) {
            List<String> ids = service(s).listStuckRuns(15).stream().map(AssistantRun::getRunId).toList();
            assertTrue(ids.contains(rid("r-stuck-noevent")),
                "从无事件的 RUNNING 行必须靠 create_time 回退被判死（否则 15min 兜底永远捞不到）");
            assertFalse(ids.contains(rid("r-stuck-live")),
                "刚有心跳的活 run 不许被判死（判死=中断用户正在看的回答）");
            assertFalse(ids.contains(rid("r-stuck-done")), "终态行不是候选");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ⑤ listRunningOlderThan：按秒级窗口扫 RUNNING
    // ══════════════════════════════════════════════════════════════════

    @Test
    void listRunningOlderThan_秒级窗口_只取RUNNING() {
        insertRun(1L, "r-old", "RUNNING", null, null, 5);
        insertRun(2L, "r-new", "RUNNING", null, null, 0);
        insertRun(3L, "r-old-done", "COMPLETED", "normal", null, 5);
        try (SqlSession s = factory.openSession(true)) {
            List<String> ids = service(s).listRunningOlderThan(60).stream()
                .map(AssistantRun::getRunId).toList();
            assertTrue(ids.contains(rid("r-old")), "超过 60s 的 RUNNING 行必须被扫到");
            assertFalse(ids.contains(rid("r-new")), "刚建的 RUNNING 行不在窗口内");
            assertFalse(ids.contains(rid("r-old-done")), "终态行不在扫描面");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ⑥ finalizeStuckRun / ⑦ finalizeStalledRun：条件迁移 + 两种 end_reason
    // ══════════════════════════════════════════════════════════════════

    @Test
    void finalizeStuckRun_迁INTERRUPTED且end_reason为no_terminal_event() {
        insertRun(1L, "r1", "RUNNING", null, null, 0);
        try (SqlSession s = factory.openSession(true)) {
            RunLedgerService svc = service(s);

            List<AssistantRun> out = svc.finalizeStuckRun(TENANT, rid("r1"));
            assertEquals(1, out.size(), "RETURNING 必须退回合归属行，供调用方退租约");
            assertEquals(rid("r1"), out.get(0).getRunId());
            assertEquals(USER, out.get(0).getUserId());

            AssistantRun after = svc.runByRunId(rid("r1"));
            assertEquals("INTERRUPTED", after.getStatus());
            assertEquals("no-terminal-event", after.getEndReason(),
                "end_reason 是运维区分「没收到终态」与「客户端断开」的唯一线索，写错=排查时误判");

            // 防双退：同一行再迁一次必须空（条件 RUNNING 已不成立）。
            // 必须换新 SqlSession——同一 session 内 MyBatis 一级缓存会把上一条 @Select 结果
            // 直接返回而不打库（本类第一版就栽在这：断言读到缓存，误以为防双退失效）。
            try (SqlSession s2 = factory.openSession(true)) {
                assertTrue(service(s2).finalizeStuckRun(TENANT, rid("r1")).isEmpty(),
                    "重复迁移必须返回空（RETURNING + 条件 RUNNING 的防双退语义）");
            }
        }
    }

    @Test
    void finalizeStalledRun_end_reason为client_disconnect_租户谓词不可绕过() {
        insertRun(1L, "r1", "RUNNING", null, null, 0);
        insertRun(2L, "r2", "RUNNING", null, null, 0);
        try (SqlSession s = factory.openSession(true)) {
            RunLedgerService svc = service(s);

            List<AssistantRun> out = svc.finalizeStalledRun(TENANT, rid("r1"));
            assertEquals(1, out.size());
            assertEquals("client-disconnect", svc.runByRunId(rid("r1")).getEndReason(),
                "与 finalizeStuckRun 的 no-terminal-event 必须区分开");

            // 纵深防御：租户谓词写错=跨租户迁移别人的 run
            assertTrue(svc.finalizeStalledRun("other-tenant", rid("r2")).isEmpty(),
                "异租户不得迁移（租户谓词是纵深防御，不是装饰）");
            assertEquals("RUNNING", svc.runByRunId(rid("r2")).getStatus(), "被拒的迁移不许留痕");

            assertTrue(svc.finalizeStalledRun(null, rid("r2")).isEmpty(),
                "tenantId 为 null 时恒不命中（与 run 行不存在同结果，不报错）");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // ⑧ listRunIssues：按会话取全部问题行
    // ══════════════════════════════════════════════════════════════════

    @Test
    void listRunIssues_按会话取全部问题行_异租户异主不串行() {
        insertRun(1L, "r1", "FAILED", "error", null, 0);
        insertRun(2L, "r2", "FAILED", "error", null, 0);
        insertRun(3L, "r3", "FAILED", "error", null, 0);
        try (SqlSession s = factory.openSession(true)) {
            AssistantRunIssueMapper m = s.getMapper(AssistantRunIssueMapper.class);
            // id 递增=落库序确定，前端按 messageId 挂回回合时依赖这个顺序
            m.insertIssue(issue(1L, "r1", USER, "empty"));
            m.insertIssue(issue(2L, "r2", USER, "rate"));
            // 同会话号但异主 → 不该出现在本人的回放里
            m.insertIssue(issue(3L, "r3", OTHER_USER, "quota"));

            List<AssistantRunIssue> rows = service(s).listRunIssues(TENANT, USER, CONV_ID);
            assertEquals(2, rows.size(), "只取本人的两行");
            assertEquals(List.of(rid("r1"), rid("r2")),
                rows.stream().map(AssistantRunIssue::getRunId).toList(),
                "按落库序返回（前端按 messageId 挂回对应回合，顺序错=挂错回合）");

            assertTrue(service(s).listRunIssues("other-tenant", USER, CONV_ID).isEmpty(),
                "异租户不得回行");
            assertEquals(1, service(s).listRunIssues(TENANT, OTHER_USER, CONV_ID).size(),
                "换本人视角只看自己那行（说明 tenant/user 双谓词都在生效）");
        }
    }

    @Test
    void insertIssue_同run只落一条() {
        insertRun(1L, "r1", "FAILED", "error", null, 0);
        try (SqlSession s = factory.openSession(true)) {
            AssistantRunIssueMapper m = s.getMapper(AssistantRunIssueMapper.class);
            assertEquals(1, m.insertIssue(issue(1L, "r1", USER, "empty")));
            assertEquals(0, m.insertIssue(issue(2L, "r1", USER, "rate")),
                "同 run 第二条必须被唯一索引挡下（两条写入路升级后可能双写）");
            AssistantRunIssue kept = m.selectLatestByRunId(rid("r1"));
            assertNotNull(kept, "先到的那条必须还在");
            assertEquals("empty", kept.getCode(), "先到的那条不被覆盖");
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // 夹具与清理
    // ══════════════════════════════════════════════════════════════════

    private static AssistantRunIssue issue(long id, String bareRunId, Long userId, String code) {
        AssistantRunIssue i = new AssistantRunIssue();
        i.setId(id);
        i.setTenantId(TENANT);
        i.setUserId(userId);
        i.setConversationId(CONV_ID);
        i.setThreadId(THREAD);
        i.setRunId(rid(bareRunId));
        i.setCode(code);
        i.setDetail("b10 realdb probe");
        return i;
    }

    /**
     * 插入一条 run 行。走原生 SQL 而非 {@code insertIgnoreRun}：后者只写 8 列，
     * 不写 {@code last_event_at} / 不写自定义 {@code create_time}，而本类三个用例正靠这两列
     * 判超时窗口。用一条 SQL 统一覆盖，避免「mapper 插入 + 再 UPDATE 补列」的绕路。
     *
     * @param createdAgoMin create_time 距今分钟数（0=当前）
     * @param lastEventAt   为 null 表示不设心跳
     */
    private static void insertRun(long id, String bareRunId, String status, String endReason,
                                  LocalDateTime lastEventAt, int createdAgoMin) {
        String sql = "INSERT INTO assistant_run (id, tenant_id, user_id, thread_id, run_id, status,"
            + " end_reason, owner_instance, last_event_at, create_time, update_time)"
            + " VALUES (?,?,?,?,?,?,?,?,?, CURRENT_TIMESTAMP - make_interval(mins => ?::int), CURRENT_TIMESTAMP)";
        try (SqlSession s = factory.openSession(true); Connection c = s.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, id);
            ps.setString(2, TENANT);
            ps.setLong(3, USER);
            ps.setString(4, THREAD);
            ps.setString(5, rid(bareRunId));
            ps.setString(6, status);
            ps.setString(7, endReason);
            ps.setString(8, "b10:test");
            ps.setTimestamp(9, lastEventAt == null ? null : Timestamp.valueOf(lastEventAt));
            ps.setInt(10, createdAgoMin);
            assertEquals(1, ps.executeUpdate(), "夹具插入必须恰好 1 行");
        } catch (Exception e) {
            throw new IllegalStateException("插入 run 行失败: " + bareRunId, e);
        }
    }

    private static LocalDateTime minutesAgo(int minutes) {
        return LocalDateTime.now().minusMinutes(minutes);
    }

    private static LocalDateTime lastEventAt(String bareRunId) {
        try (SqlSession s = factory.openSession(true); Connection c = s.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT last_event_at FROM assistant_run WHERE run_id=?")) {
            ps.setString(1, rid(bareRunId));
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "行必须存在: " + bareRunId);
                Timestamp ts = rs.getTimestamp(1);
                return ts == null ? null : ts.toLocalDateTime();
            }
        } catch (Exception e) {
            throw new IllegalStateException("读 last_event_at 失败: " + bareRunId, e);
        }
    }

    /** 只删本类前缀行，绝不碰业务行 */
    private static void cleanup() {
        try (SqlSession s = factory.openSession(true); Connection c = s.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM assistant_run_issue WHERE run_id LIKE ?")) {
                ps.setString(1, RUN_PREFIX + "%");
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM assistant_run WHERE run_id LIKE ?")) {
                ps.setString(1, RUN_PREFIX + "%");
                ps.executeUpdate();
            }
        } catch (Exception e) {
            throw new IllegalStateException("清理本类探针行失败", e);
        }
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? null : v;
    }
}
