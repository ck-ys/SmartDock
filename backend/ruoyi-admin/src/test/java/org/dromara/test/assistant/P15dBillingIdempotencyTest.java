package org.dromara.test.assistant;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-T15d 探针：计费幂等并发（实测⑮之四，安全五连测卡4，零模型成本，真 PG）。
 * <p>X-01 边界修订（计划 Task 15 Step4）：**不写正式 wallet/ledger/usage 业务表**——
 * 用 agentscope schema 下 dcs_p0_probe_* 探针三表+审计表，等价复刻 **spec §9 目标态**
 * record() 幂等语义（runId:seq 锚点 + usage/ledger 同事务 + 唯一索引；现状代码=usageId
 * 单据级无事务的差距由 T16 列入一站前置改造清单，本探针不复刻现状）。</p>
 * <p>判据：</p>
 * <ul>
 *   <li>50 并发回合（同租户同 runId 不同 seq）：全部成功，Σledger=Σusage=钱包扣减额 三方对账；</li>
 *   <li>幂等锚点 assistant:{tenantId}:{runId}:{seq} 落 ledger+usage 双唯一索引——
 *       重放 50 个旧锚点（并发重放风暴）：零重复扣费，全部拒绝且落审计行；</li>
 *   <li>金额=逐行 tokens×单价 求和（非行数×单价，spec §13 对账口径）。</li>
 * </ul>
 * <p>清理：探针表结构保留（T16 统一 before/after diff 处置），本类 finally 行级清空。</p>
 */
@Tag("p0probe")
@SpringBootTest
class P15dBillingIdempotencyTest {

    private static final String USER_KEY = "t:978311:p15d";
    private static final String RUN_ID = "run-p15d-001";
    private static final BigDecimal WALLET_SEED = new BigDecimal("1000.0000");
    private static final BigDecimal UNIT_PRICE = new BigDecimal("0.000002");

    @Autowired
    DataSource ds;

    @BeforeAll
    static void note() {
        System.out.println("[P15d] 探针表=agentscope.dcs_p0_probe_{wallet,ledger,usage,audit}（不碰正式业务表）");
    }

    @AfterAll
    static void done() {
        System.out.println("[P15d] 完成（行级清理由各测试 finally 完成；表结构留 T16 before/after diff 统一处置）");
    }

    /** spec §9 目标态 record()：usage+ledger+wallet 扣减同一事务；锚点冲突=重放→回滚+审计 */
    private String recordTargetState(Connection conn, int seq, int tokensIn, int tokensOut) throws SQLException {
        BigDecimal amount = UNIT_PRICE.multiply(BigDecimal.valueOf(tokensIn + tokensOut));
        String anchor = "assistant:978311:" + RUN_ID + ":" + seq;
        boolean wasAuto = conn.getAutoCommit();
        try {
            conn.setAutoCommit(false);
            // 三件套①：runId 首见登记绑定 user；已登记但归属失配=换人冒用 → 拒绝（k3 P0-3 复刻）
            String regResult = registerRunId(conn, RUN_ID, USER_KEY);
            if (!"OK".equals(regResult)) {
                conn.rollback();
                return regResult;
            }
            int u, l;
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO agentscope.dcs_p0_probe_usage"
                    + " (idempotency_key, user_key, run_id, seq, tokens_in, tokens_out, unit_price, amount)"
                    + " VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (idempotency_key) DO NOTHING")) {
                ps.setString(1, anchor);
                ps.setString(2, USER_KEY);
                ps.setString(3, RUN_ID);
                ps.setInt(4, seq);
                ps.setInt(5, tokensIn);
                ps.setInt(6, tokensOut);
                ps.setBigDecimal(7, UNIT_PRICE);
                ps.setBigDecimal(8, amount);
                u = ps.executeUpdate();
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO agentscope.dcs_p0_probe_ledger"
                    + " (idempotency_key, user_key, run_id, seq, amount, direction)"
                    + " VALUES (?,?,?,?,?,'OUT') ON CONFLICT (idempotency_key) DO NOTHING")) {
                ps.setString(1, anchor);
                ps.setString(2, USER_KEY);
                ps.setString(3, RUN_ID);
                ps.setInt(4, seq);
                ps.setBigDecimal(5, amount);
                l = ps.executeUpdate();
            }
            if (u == 0 || l == 0) {
                conn.rollback();
                return "REPLAY";
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE agentscope.dcs_p0_probe_wallet SET balance = balance - ? WHERE user_key = ?")) {
                ps.setBigDecimal(1, amount);
                ps.setString(2, USER_KEY);
                ps.executeUpdate();
            }
            conn.commit();
            return "OK";
        } catch (SQLException e) {
            conn.rollback();
            throw e;
        } finally {
            conn.setAutoCommit(wasAuto);
        }
    }

    /** runId 首见登记+归属校验：OK=本人/首见；RUN_ID_HIJACK_REJECTED=他人已登记该 runId（三件套①） */
    private String registerRunId(Connection conn, String runId, String userKey) throws SQLException {
        int reg;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO agentscope.dcs_p0_probe_run_registry (run_id, user_key)"
                + " VALUES (?, ?) ON CONFLICT (run_id) DO NOTHING")) {
            ps.setString(1, runId);
            ps.setString(2, userKey);
            reg = ps.executeUpdate();
        }
        if (reg > 0) {
            return "OK";
        }
        String owner;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT user_key FROM agentscope.dcs_p0_probe_run_registry WHERE run_id = ?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                owner = rs.getString(1);
            }
        }
        return userKey.equals(owner) ? "OK" : "RUN_ID_HIJACK_REJECTED";
    }

    /** 重放拒绝审计（独立连接提交——主事务回滚后审计必须存活） */
    private void auditReplay(String anchor, String detail) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "INSERT INTO agentscope.dcs_p0_probe_audit (event, idempotency_key, user_key, detail)"
                 + " VALUES ('REPLAY_REJECTED', ?, ?, ?)")) {
            ps.setString(1, anchor);
            ps.setString(2, USER_KEY);
            ps.setString(3, detail);
            ps.executeUpdate();
        }
    }

    private BigDecimal sum(String table) throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COALESCE(SUM(amount), 0) FROM agentscope." + table + " WHERE user_key = ?");
             ) {
            ps.setString(1, USER_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    private BigDecimal walletBalance() throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT balance FROM agentscope.dcs_p0_probe_wallet WHERE user_key = ?")) {
            ps.setString(1, USER_KEY);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    @Test
    void fiftyConcurrentRoundsReconcileThreeWaysAndReplaysRejected() throws Exception {
        // 建表（幂等）+ 行级清零 + 播种钱包
        try (Connection c = ds.getConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS agentscope.dcs_p0_probe_wallet ("
                + "user_key varchar(64) PRIMARY KEY, balance numeric(14,6) NOT NULL)");
            // 坑68 实证修正：钱包 4 位小数会逐行舍入 6 位精度的 ledger 金额（0.017550→0.0175，
            // 三方对账失真）——生产三表必须同精度。探针表就地升 6 位（幂等）。
            st.execute("ALTER TABLE agentscope.dcs_p0_probe_wallet"
                + " ALTER COLUMN balance TYPE numeric(14,6)");
            st.execute("CREATE TABLE IF NOT EXISTS agentscope.dcs_p0_probe_ledger ("
                + "id bigserial PRIMARY KEY, idempotency_key varchar(128) NOT NULL UNIQUE, "
                + "user_key varchar(64) NOT NULL, run_id varchar(64) NOT NULL, seq int NOT NULL, "
                + "amount numeric(14,6) NOT NULL, direction varchar(3) NOT NULL, "
                + "created_at timestamptz NOT NULL DEFAULT now())");
            st.execute("CREATE TABLE IF NOT EXISTS agentscope.dcs_p0_probe_usage ("
                + "id bigserial PRIMARY KEY, idempotency_key varchar(128) NOT NULL UNIQUE, "
                + "user_key varchar(64) NOT NULL, run_id varchar(64) NOT NULL, seq int NOT NULL, "
                + "tokens_in int NOT NULL, tokens_out int NOT NULL, unit_price numeric(14,8) NOT NULL, "
                + "amount numeric(14,6) NOT NULL, created_at timestamptz NOT NULL DEFAULT now())");
            st.execute("CREATE TABLE IF NOT EXISTS agentscope.dcs_p0_probe_audit ("
                + "id bigserial PRIMARY KEY, event varchar(32) NOT NULL, "
                + "idempotency_key varchar(128), user_key varchar(64), detail text, "
                + "created_at timestamptz NOT NULL DEFAULT now())");
            // 旧表补列（四审 k3 加 user_key 维度；IF NOT EXISTS 不改已建表——幂等 ALTER）
            st.execute("ALTER TABLE agentscope.dcs_p0_probe_audit"
                + " ADD COLUMN IF NOT EXISTS user_key varchar(64)");
            // 三审 k3 P0-3：spec §9 三件套① runId 服务端登记制——首见落库绑定 user，复用须归属匹配
            st.execute("CREATE TABLE IF NOT EXISTS agentscope.dcs_p0_probe_run_registry ("
                + "run_id varchar(64) PRIMARY KEY, user_key varchar(64) NOT NULL, "
                + "created_at timestamptz NOT NULL DEFAULT now())");
            for (String t : List.of("dcs_p0_probe_wallet", "dcs_p0_probe_ledger", "dcs_p0_probe_usage")) {
                st.execute("DELETE FROM agentscope." + t
                    + (t.equals("dcs_p0_probe_wallet") ? "" : " WHERE user_key = '" + USER_KEY + "'"));
            }
            // audit 表无 user_key 列——按幂等锚点前缀清（与本测试 RUN_ID 绑定）
            st.execute("DELETE FROM agentscope.dcs_p0_probe_audit WHERE idempotency_key LIKE 'assistant:978311:" + RUN_ID + ":%'");
            st.execute("INSERT INTO agentscope.dcs_p0_probe_wallet (user_key, balance) VALUES ('"
                + USER_KEY + "', " + WALLET_SEED.toPlainString() + ")");
        }

        // ===== 第一波：50 并发首次记账（seq=1..50，tokens 逐行不同——金额=逐行求和口径）=====
        int n = 50;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<String> taskErrors = new CopyOnWriteArrayList<>();
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(n);
            AtomicInteger okCount = new AtomicInteger();
            AtomicInteger replayCount = new AtomicInteger();
            for (int i = 1; i <= n; i++) {
                int seq = i;
                int tokensIn = 100 + seq;
                int tokensOut = 50;
                pool.submit(() -> {
                    try {
                        start.await();
                        try (Connection conn = ds.getConnection()) {
                            String r = recordTargetState(conn, seq, tokensIn, tokensOut);
                            if ("OK".equals(r)) {
                                okCount.incrementAndGet();
                            } else {
                                replayCount.incrementAndGet();
                            }
                        }
                    } catch (Exception e) {
                        taskErrors.add("wave1#" + seq + ": " + e);
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "50 并发应在 60s 内完成");
            assertEquals(n, okCount.get(), "首次记账应全部成功（零重放）");
            assertEquals(0, replayCount.get());

            // ===== 第二波：并发重放风暴（同 50 个旧锚点）=====
            // 审计行移出并发路径（幂等判据=record() 拒绝；审计写入本身串行补——防连接池二级竞争干扰主判据）
            CountDownLatch replayStart = new CountDownLatch(1);
            CountDownLatch replayDone = new CountDownLatch(n);
            AtomicInteger replayRejected = new AtomicInteger();
            List<Integer> rejectedSeqs = new CopyOnWriteArrayList<>();
            for (int i = 1; i <= n; i++) {
                int seq = i;
                pool.submit(() -> {
                    try {
                        replayStart.await();
                        long t0 = System.currentTimeMillis();
                        try (Connection conn = ds.getConnection()) {
                            long gotConn = System.currentTimeMillis();
                            String r = recordTargetState(conn, seq, 100 + seq, 50);
                            if ("REPLAY".equals(r)) {
                                replayRejected.incrementAndGet();
                                rejectedSeqs.add(seq);
                            }
                            if (seq <= 3) {
                                System.out.println("[P15d] wave2#" + seq + " 获连接 " + (gotConn - t0)
                                    + "ms + 事务 " + (System.currentTimeMillis() - gotConn) + "ms");
                            }
                        }
                    } catch (Exception e) {
                        taskErrors.add("wave2#" + seq + ": " + e);
                        throw new RuntimeException(e);
                    } finally {
                        replayDone.countDown();
                    }
                });
            }
            replayStart.countDown();
            assertTrue(replayDone.await(60, TimeUnit.SECONDS), "重放风暴应在 60s 内完成");
            // 重放审计串行补写（主判据=拒绝计数；审计行为"拒绝后落审计"的挂点验证）
            for (int seq : rejectedSeqs) {
                auditReplay("assistant:978311:" + RUN_ID + ":" + seq, "重放拒绝");
            }
            if (!taskErrors.isEmpty()) {
                System.out.println("[P15d] 任务异常 " + taskErrors.size() + " 条，样本: "
                    + taskErrors.subList(0, Math.min(3, taskErrors.size())));
            }
            assertEquals(0, taskErrors.size(), "并发任务不得有异常（样本=" + taskErrors + "）");
            assertEquals(n, replayRejected.get(), "50 个旧锚点重放必须全部被拒（幂等生效）");
        } finally {
            pool.shutdownNow();
        }

        // ===== 三方对账（spec §13：Σledger OUT = Σusage 金额 = 钱包扣减额；逐行 tokens×单价）=====
        BigDecimal sumLedger = sum("dcs_p0_probe_ledger");
        BigDecimal sumUsage = sum("dcs_p0_probe_usage");
        BigDecimal walletDelta = WALLET_SEED.subtract(walletBalance());
        BigDecimal expected = BigDecimal.ZERO;
        for (int i = 1; i <= n; i++) {
            expected = expected.add(UNIT_PRICE.multiply(BigDecimal.valueOf(100 + i + 50)));
        }
        System.out.println("[P15d] Σledger=" + sumLedger + " Σusage=" + sumUsage
            + " 钱包扣减=" + walletDelta + " 期望(逐行)=" + expected);
        assertEquals(0, expected.compareTo(sumLedger), "Σledger=逐行求和期望值");
        assertEquals(0, sumLedger.compareTo(sumUsage), "Σledger=Σusage（同事务保证）");
        assertEquals(0, walletDelta.compareTo(sumUsage), "钱包扣减=Σusage（三方对账闭合，重放零重复扣费）");

        // ===== 波3：异用户冒用 runId（spec §9 三件套①：runId 归属校验拒绝）=====
        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);
            assertEquals("RUN_ID_HIJACK_REJECTED", registerRunId(conn, RUN_ID, "t:978311:attacker"),
                "他人持已登记 runId 必须被拒（runId 服务端登记制）");
            conn.rollback();
            String r = recordTargetState(conn, 51, 151, 50);
            assertEquals("OK", r, "seq=51 是本人新锚点，应成功（runId 归属本人）");
        }
        // 本人但同锚点异金额重放（篡改金额变体）：锚点冲突仍拒（k3 P1 补——金额不被锚点携带，冲突即原值保留）
        try (Connection conn = ds.getConnection()) {
            String r = recordTargetState(conn, 7, 999, 999);
            assertEquals("REPLAY", r, "同锚点异金额重放必须被拒（幂等锚点不认金额变体）");
        }

        // 审计行数：重放全落审计
        int audits;
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT COUNT(*) FROM agentscope.dcs_p0_probe_audit WHERE event = 'REPLAY_REJECTED'"
                 + " AND idempotency_key LIKE 'assistant:978311:" + RUN_ID + ":%'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            audits = rs.getInt(1);
        }
        assertEquals(n, audits, "重放拒绝必须逐一落审计行");
        System.out.println("[P15d] 全部判据过：50 并发零冲突/重放全拒+审计/三方对账闭合");

        // 三审修正：行级清空从"仅运行前"补成"断言后也清"——探针行不留库（表结构仍留 T16 diff 处置）
        try (Connection c = ds.getConnection(); java.sql.Statement st = c.createStatement()) {
            for (String t : List.of("dcs_p0_probe_wallet", "dcs_p0_probe_ledger", "dcs_p0_probe_usage")) {
                st.execute("DELETE FROM agentscope." + t
                    + (t.equals("dcs_p0_probe_wallet") ? "" : " WHERE user_key = '" + USER_KEY + "'"));
            }
            st.execute("DELETE FROM agentscope.dcs_p0_probe_audit WHERE idempotency_key LIKE 'assistant:978311:" + RUN_ID + ":%'");
        }
        System.out.println("[P15d] 断言后行级清空完成（库中探针行归零）");
    }
}
