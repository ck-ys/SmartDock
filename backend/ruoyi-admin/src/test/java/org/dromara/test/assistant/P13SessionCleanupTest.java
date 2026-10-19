package org.dromara.test.assistant;

import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import io.agentscope.extensions.redis.state.jedis.JedisAgentStateStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import redis.clients.jedis.JedisPool;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-T13 探针：会话清理 job 骨架（实测⑬，R-07 补排，零模型成本）。
 * <p>判据（计划 Task 13）：</p>
 * <ul>
 *   <li>Step1：AgentStateStore 建假会话 N 条 → delete(userId, sessionId)
 *       → Redis 键与用户会话索引 Set 清空断言（官方 O(1) 删除语义：单会话删 session 键+索引项，
 *       不做全库 SCAN）；</li>
 *   <li>对照：JdbcAgentStateStore.delete 同语义（PG 行删除，session_id=userId:sessionId 拼接形态）——
 *       P1 清理 job 的 PG 过期驱动遍历删除路径可行性；</li>
 *   <li>**坑64 实测固化**：RedissonAgentStateStore（extensions-redis）与项目基线 Redisson 3.52.0
 *       二进制不兼容（编译目标 4.2.0，RScript.ReturnType API 断裂→NoSuchFieldError）——
 *       坑60 排除 4.2.0 后该变体不可用；P1 已选 JdbcStore→PG 不受影响，Redis 路须 Jedis/Lettuce 变体。</li>
 * </ul>
 * <p>清理口径：Redis 探针键一律 t:978311:p13: 前缀（db0）；PG 仅删 t:978311:p13 前缀行；测后全部清零。</p>
 */
@Tag("p0probe")
class P13SessionCleanupTest {

    private static final String USER = "t:978311:p13";
    record ProbeState(String content) implements State {}

    private static JedisPool pool;
    private static JedisAgentStateStore store;

    @BeforeAll
    static void setUp() {
        // dev Redis（application-dev.yml：127.0.0.1:6379，无密码）；探针用 db0 + 独立前缀（只清理自有前缀，不动业务键）
        pool = new JedisPool("127.0.0.1", 6379);
        store = JedisAgentStateStore.builder().keyPrefix("t:978311:p13:").jedisPool(pool).build();
        // 清历史残留（只命中本探针前缀）
        store.clearAllSessions().block(java.time.Duration.ofSeconds(10));
    }

    @AfterAll
    static void tearDown() {
        List<String> residue = new ArrayList<>();
        try {
            store.clearAllSessions().block(java.time.Duration.ofSeconds(10));
            try (var jedis = pool.getResource()) {
                residue.addAll(jedis.keys("t:978311:p13:*"));
            }
        } finally {
            store.close();
            pool.close();
        }
        assertEquals(0, residue.size(), "测后 t:978311:p13: 前缀键必须零残留（实扫=" + residue + "）");
    }

    private static DataSource controlledDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[]{"127.0.0.1"});
        ds.setPortNumbers(new int[]{5432});
        ds.setDatabaseName("smartdock");
        ds.setUser("postgres");
        ds.setPassword("postgres");
        ds.setCurrentSchema("agentscope");
        return ds;
    }

    /** Step1：Redis 路（Jedis 变体）——建 3 假会话 → 逐条 delete → 索引/键全清（O(1) 语义实证） */
    @Test
    void redisSessionDeleteClearsKeyAndIndex() {
        for (int i = 1; i <= 3; i++) {
            store.save(USER, "sess-" + i, "k", new ProbeState("v" + i));
        }
        Set<String> sessions = store.listSessionIds(USER);
        System.out.println("[P13] 建会话后索引 = " + sessions);
        assertEquals(Set.of("sess-1", "sess-2", "sess-3"), sessions, "索引 Set 应含全部 3 会话");

        // 删除单会话：索引项移除 + 会话键不可读 + 其余会话不受影响
        store.delete(USER, "sess-2");
        Set<String> after = store.listSessionIds(USER);
        assertEquals(Set.of("sess-1", "sess-3"), after, "delete 后索引应精确移除 sess-2");
        VersionedState<ProbeState> gone = store.getVersioned(USER, "sess-2", "k", ProbeState.class);
        assertTrue(gone == null || !gone.isPresent(), "已删会话数据不可读");
        assertEquals("v1", store.getVersioned(USER, "sess-1", "k", ProbeState.class).value().content(),
            "其余会话数据不受删除影响");

        // 模拟清理 job 收尾：遍历剩余会话逐一删除 → 索引空 + 模式扫描零残留
        for (String s : new ArrayList<>(store.listSessionIds(USER))) {
            store.delete(USER, s);
        }
        assertTrue(store.listSessionIds(USER).isEmpty(), "全部删除后索引 Set 应为空");
        try (var jedis = pool.getResource()) {
            List<String> residue = new ArrayList<>(jedis.keys("t:978311:p13:*"));
            System.out.println("[P13] 模式扫描残留 = " + residue);
            assertTrue(residue.isEmpty(), "t:978311:p13:* 模式扫描应零残留（会话键+索引键全清）");
        }
    }

    /** 坑64 固化：Redisson 变体在基线 Redisson 3.52.0 下二进制不兼容（登记项，P1 用 JdbcStore 不受影响） */
    @Test
    void redissonVariantIncompatibleWithBaseline() {
        org.redisson.config.Config cfg = new org.redisson.config.Config();
        cfg.useSingleServer().setAddress("redis://127.0.0.1:6379").setDatabase(0);
        org.redisson.api.RedissonClient rc = org.redisson.Redisson.create(cfg);
        try {
            io.agentscope.extensions.redis.state.redisson.RedissonAgentStateStore rs =
                io.agentscope.extensions.redis.state.redisson.RedissonAgentStateStore.builder()
                    .redissonClient(rc).keyPrefix("t:978311:p13:rs:").build();
            // 预期 NoSuchFieldError：extensions-redis 按 Redisson 4.2.0 编译（坑60 排除后走基线 3.52.0）
            assertThrows(NoSuchFieldError.class,
                () -> rs.save(USER, "s", "k", new ProbeState("v")),
                "坑64：RedissonAgentStateStore 在基线 Redisson 3.52.0 下应二进制断裂"
                    + "（P1 选型=JdbcStore→PG；Redis 路须 Jedis/Lettuce 变体）");
        } finally {
            rc.shutdown();
        }
    }

    /** 对照：JDBC 路——delete(userId, sessionId) 后 PG 行删除（session_id=userId:sessionId 拼接） */
    @Test
    void jdbcSessionDeleteRemovesRow() throws Exception {
        DataSource ds = controlledDataSource();
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).autoCreateTable(false).build();
        JdbcAgentStateStore jdbcStore = new JdbcAgentStateStore(ds, dialect, false);
        try {
            jdbcStore.save(USER, "jdbc-1", "k", new ProbeState("v1"));
            String composite = USER + ":jdbc-1";
            assertTrue(rowExists(composite), "保存后 PG 应有行（session_id=" + composite + "）");

            jdbcStore.delete(USER, "jdbc-1");
            assertFalse(rowExists(composite), "delete 后 PG 行应删除（P1 过期驱动清理的删除原语可用）");
        } finally {
            try (Connection c = ds.getConnection();
                 PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM agentscope.agentscope_sessions WHERE session_id LIKE 't:978311:p13:%'")) {
                ps.executeUpdate();
            }
        }
    }

    private static boolean rowExists(String compositeSessionId) throws Exception {
        try (Connection c = controlledDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT count(*) FROM agentscope.agentscope_sessions WHERE session_id = ?")) {
            ps.setString(1, compositeSessionId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }
}
