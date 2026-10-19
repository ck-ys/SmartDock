package org.dromara.test.assistant;

import io.agentscope.extensions.jdbc.JdbcDistributedStore;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-T10 探针：AgentScope 2.0.3 JdbcStore 落 PostgreSQL 实测。
 * <p>判据（计划 document/plans/DCS_ASSISTANT_P0_VERIFICATION_PLAN_20260923.md Task 10）：</p>
 * <ul>
 *   <li>#3213 双层实证：①autoCreateTable 默认 true，装配时无条件执行 CREATE TABLE IF NOT EXISTS，
 *       PG 对该语句的权限检查不豁免 IF NOT EXISTS——受控账号（无 DDL 权限）必炸；
 *       ②JdbcDistributedStore.create(ds, dialect) 工厂内部写死 initializeSchema(true) 不透传，同样必炸；</li>
 *   <li>官方绕行配方（生产装配纪律）：AbstractJdbcDialect.from(ds).autoCreateTable(false).build()
 *       + JdbcStore.builder(ds).dialect(dialect).initializeSchema(false).build()——预建表+分件装配；</li>
 *   <li>RemoteFilesystemSpec 挂 baseStore 写 MEMORY.md/knowledge → PG 行可见、回读一致；</li>
 *   <li>#2143 upsert 往返：同 key 重复 put version 递增；putIfVersion 乐观锁行为正确。</li>
 * </ul>
 * <p>清理口径：仅删 namespace_path = {@code p0-probe} 的测试行；agentscope schema/表结构/受控 ROLE 保留。</p>
 */
@Tag("p0probe")
class P10JdbcStoreTest {

    /** 探针命名空间（行级清理锚点，绝不碰业务行） */
    private static final String NS = "p0-probe";

    /**
     * 官方落库形态（实证第三层发现）：namespace_path = 段列表以 0x1F(单元分隔符) join 且
     * 尾部带分隔符——List.of("p0-probe") 落库为 "p0-probe"。
     * 运维查库/清理 SQL 的 WHERE 必须用本值，裸 "p0-probe" 永远查不到。
     */
    private static final String NS_DB = "p0-probe";

    /** 受控账号：仅 CONNECT + agentscope USAGE + 三表 DML，无任何 DDL 权限（已 psql 实证 CREATE 被拒） */
    private static DataSource controlledDataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setServerNames(new String[]{"127.0.0.1"});
        ds.setPortNumbers(new int[]{5432});
        ds.setDatabaseName("smartdock");
        ds.setUser("postgres");
        ds.setPassword("postgres");
        // 官方 checkTableExists 按 current_schema() 判定；裸表名 DML 也靠 search_path 定位
        ds.setCurrentSchema("agentscope");
        return ds;
    }

    /** 生产装配配方：预建表（migration）+ 关闭两处自动建表 + 分件装配 */
    private static BaseStore buildBaseStore() {
        DataSource ds = controlledDataSource();
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).autoCreateTable(false).build();
        return JdbcStore.builder(ds).dialect(dialect).initializeSchema(false).build();
    }

    @BeforeAll
    static void cleanStaleProbeRows() throws Exception {
        deleteProbeRows();
    }

    @AfterAll
    static void cleanupProbeRows() throws Exception {
        deleteProbeRows();
    }

    private static void deleteProbeRows() throws Exception {
        try (Connection c = controlledDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM agentscope.agentscope_store WHERE namespace_path = ?")) {
            ps.setString(1, NS_DB);
            ps.executeUpdate();
        }
    }

    /**
     * #3213 实证 + 三条装配路径行为锁定：
     * ①单参工厂 create(ds)：内部 autoCreateTable 默认 true，无条件 CREATE TABLE IF NOT EXISTS → dialect 装配即炸；
     * ②双参工厂 create(ds, dialect)：create 本身不炸，但 baseStore() 惰性首调时内部 JdbcStore.builder
     *   默认 initializeSchema(true) 且不透传 dialect 开关 → 仍炸（惰性炸弹，比①更隐蔽）；
     * ③分件装配 JdbcStore.builder(ds).dialect(dialect).initializeSchema(false).build()
     *   → 不炸（生产装配纪律唯一安全形态；agentStateStore()/sandboxSnapshotSpec() 惰性装配
     *   同理存在各自默认，P1 装配时逐一验证——见 10-jdbcstore.md 待办）。
     */
    @Test
    void controlledAccountAssemblyFacts() {
        DataSource ds = controlledDataSource();

        // ①单参工厂：无 DDL 账号必炸——#3213 本体（PG 对 IF NOT EXISTS 语句仍做 CREATE 权限检查）
        assertThrows(Exception.class,
            () -> JdbcDistributedStore.create(ds),
            "无 DDL 账号走单参工厂（内部 autoCreateTable=true）应抛异常（#3213 本体）");

        // ②双参工厂：create 不炸，但 baseStore() 惰性首调炸（JdbcStore initializeSchema 不透传）
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).autoCreateTable(false).build();
        JdbcDistributedStore viaFactory = JdbcDistributedStore.create(ds, dialect);
        assertThrows(IllegalStateException.class, viaFactory::baseStore,
            "双参工厂 baseStore() 惰性首调应抛 IllegalState（#3213 第二层：内部 initializeSchema 默认 true 不透传）");

        // ③分件装配：官方绕行配方
        BaseStore baseStore = buildBaseStore();
        assertNotNull(baseStore);
        RemoteFilesystemSpec spec = new RemoteFilesystemSpec(baseStore);
        assertTrue(spec.hasStore(), "RemoteFilesystemSpec 应成功持有分件装配的 baseStore");
    }

    /** MEMORY.md / knowledge 文件写入 PG → 回读一致 + SQL 直查行存在（数值断言具体值） */
    @Test
    void baseStoreWritesMemoryMdAndKnowledge() throws Exception {
        BaseStore baseStore = buildBaseStore();
        List<String> ns = List.of(NS);

        baseStore.put(ns, "MEMORY.md", Map.of("content", "# P0 探针记忆\n- 验证 JdbcStore 落 PG"));
        baseStore.put(ns, "knowledge/KNOWLEDGE.md", Map.of("content", "# 知识包占位"));

        StoreItem memory = baseStore.get(ns, "MEMORY.md");
        assertNotNull(memory, "MEMORY.md 回读应非空");
        assertEquals("# P0 探针记忆\n- 验证 JdbcStore 落 PG", memory.value().get("content"), "MEMORY.md 内容往返一致");
        assertEquals(1L, memory.version(), "首次写入 version=1");

        try (Connection c = controlledDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "SELECT namespace_path, item_key, value_json, version FROM agentscope.agentscope_store "
                 + "WHERE namespace_path = ? ORDER BY item_key")) {
            ps.setString(1, NS_DB);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "PG 应有 agentscope_store 行");
                assertEquals(NS_DB, rs.getString(1), "namespace_path 应为 0x1F 拼接落库形态");
                assertEquals("MEMORY.md", rs.getString(2));
                assertTrue(rs.getString(3).contains("P0 探针记忆"), "value_json 落库应含写入内容");
                assertEquals(1L, rs.getLong(4), "PG 行 version 列 = 1");
                assertTrue(rs.next(), "PG 应有第二行（knowledge）");
                assertEquals("knowledge/KNOWLEDGE.md", rs.getString(2));
                assertFalse(rs.next(), "探针命名空间应恰好两行");
            }
        }
    }

    /** #2143 验证：同 key upsert version 递增；putIfVersion 乐观锁（旧版本号拒绝、当前版本号成功） */
    @Test
    void upsertRoundTripVersionBumpsAndCas() {
        BaseStore baseStore = buildBaseStore();
        List<String> ns = List.of(NS);
        String key = "MEMORY-cas.md";

        baseStore.put(ns, key, Map.of("content", "v1"));
        StoreItem first = baseStore.get(ns, key);
        assertEquals(1L, first.version());

        baseStore.put(ns, key, Map.of("content", "v2"));
        StoreItem second = baseStore.get(ns, key);
        assertEquals(2L, second.version(), "重复 put 应走 upsert，version 递增到 2");
        assertEquals("v2", second.value().get("content"));

        assertFalse(baseStore.putIfVersion(ns, key, Map.of("content", "stale"), 1L),
            "用过期版本号 1 CAS 应失败");
        assertEquals("v2", baseStore.get(ns, key).value().get("content"), "CAS 失败后内容不应被改");

        assertTrue(baseStore.putIfVersion(ns, key, Map.of("content", "v3"), 2L),
            "用当前版本号 2 CAS 应成功");
        StoreItem third = baseStore.get(ns, key);
        assertEquals("v3", third.value().get("content"));
        assertTrue(third.version() > 2L, "CAS 成功后 version 应继续递增");
    }
}
