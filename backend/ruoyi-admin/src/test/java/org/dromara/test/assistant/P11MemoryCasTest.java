package org.dromara.test.assistant;

import io.agentscope.core.state.State;
import io.agentscope.core.state.VersionedState;
import io.agentscope.extensions.jdbc.dialect.AbstractJdbcDialect;
import io.agentscope.extensions.jdbc.state.JdbcAgentStateStore;
import io.agentscope.extensions.jdbc.store.JdbcStore;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-T11 探针：记忆并发 CAS（实测⑪，本地不花模型钱）。
 * <p>判据（计划 Task 11）：</p>
 * <ul>
 *   <li>Step1：同 (userId,sessionId) 两线程并发 saveIfVersion → 断言恰好一个成功
 *       （计划预期 ConcurrentSessionModificationException 或 boolean false——实测记录真实语义，
 *       关键红线=非静默覆盖：失败方不得把数据写进去）；</li>
 *   <li>Step2：双线程并发 BaseStore.putIfVersion（MEMORY.md 同 key 同版本）→ 恰一 true 一 false，
 *       落库内容=成功方（非静默覆盖/非交错损坏）。</li>
 * </ul>
 * <p>清理口径：仅删 session_id/namespace 以 {@code p0-probe} 为前缀的测试行；schema/表结构保留。</p>
 */
@Tag("p0probe")
class P11MemoryCasTest {

    private static final String NS = "p0-probe";
    /** 官方落库形态：namespace_path 带尾分隔符 0x1F（T10 实证） */
    private static final String NS_DB = "p0-probe\u001F";

    /** 轻量自定义 State（State 为标记接口，Jackson 序列化 record） */
    record ProbeState(String content) implements State {}

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

    /** T10 沉淀装配配方：预建表 + 双开关关闭（三参构造的 boolean=false 即 initializeSchema(false)） */
    private static JdbcAgentStateStore buildStateStore() {
        DataSource ds = controlledDataSource();
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).autoCreateTable(false).build();
        return new JdbcAgentStateStore(ds, dialect, false);
    }

    @BeforeAll
    static void cleanStale() throws Exception {
        deleteProbeRows();
    }

    @AfterAll
    static void cleanup() throws Exception {
        deleteProbeRows();
    }

    private static void deleteProbeRows() throws Exception {
        try (Connection c = controlledDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM agentscope.agentscope_sessions WHERE session_id LIKE 't:978311:p0:%'")) {
            // 框架落库 session_id = "userId:sessionId" 拼接（T2 实证），userId 本测试固定 t:978311:p0
            ps.executeUpdate();
        }
        try (Connection c = controlledDataSource().getConnection();
             PreparedStatement ps = c.prepareStatement(
                 "DELETE FROM agentscope.agentscope_store WHERE namespace_path = ?")) {
            ps.setString(1, NS_DB);
            ps.executeUpdate();
        }
    }

    /**
     * Step1：同 (userId,sessionId) 两线程并发 saveIfVersion（同 expectedVersion）。
     * 红线：恰好一个成功；失败方不得写入自己的数据（非静默覆盖）。
     */
    @Test
    void concurrentSaveIfVersionSingleWinner() throws Exception {
        JdbcAgentStateStore store = buildStateStore();
        String userId = "t:978311:p0", sessionId = "p0-probe-cas-" + System.currentTimeMillis();
        String key = "memory";

        // 预置 v1，取回真实版本号
        store.save(userId, sessionId, key, new ProbeState("v1"));
        VersionedState<ProbeState> vs = store.getVersioned(userId, sessionId, key, ProbeState.class);
        assertTrue(vs != null && vs.isPresent() && vs.version() >= 1, "预置后应可读回版本");
        long v1 = vs.version();

        // 两线程同 expectedVersion=v1 并发抢写（CountDownLatch 对齐起跑；池 try-with-resources 防泄漏）
        CountDownLatch start = new CountDownLatch(1);
        List<Object> outcomes = new CopyOnWriteArrayList<>();   // Long=saveIfVersion 新版本 / Throwable=异常
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String payload = "writer-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        // JdbcAgentStateStore 覆写返回 long：成功=新版本号(>v1)，失败语义实测记录
                        long ret = store.saveIfVersion(userId, sessionId, key, new ProbeState(payload), v1);
                        outcomes.add(ret);
                    } catch (Throwable t) {
                        outcomes.add(t);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        System.out.println("[P11] saveIfVersion 并发结果(v1=" + v1 + ") = " + outcomes.stream()
            .map(o -> o instanceof Long l ? "newVer=" + l : "EX:" + o.getClass().getSimpleName())
            .toList());

        // 语义记录：计划预期 ConcurrentSessionModificationException；JdbcAgentStateStore 实际返回 long
        // （成功=新版本号，失败=非推进值）——两者都算"非静默覆盖"，赢者必须恰好 1 个
        long wins = outcomes.stream().filter(o -> o instanceof Long l && l > v1).count();
        assertEquals(1, wins, "并发 CAS 必须恰好一个成功（结果=" + outcomes + "）");

        // 落库真相 = 赢者的 payload，且版本推进（非静默覆盖验证；三审修：去 startsWith 冗余断言）
        VersionedState<ProbeState> after = store.getVersioned(userId, sessionId, key, ProbeState.class);
        String winner = after.value().content();
        assertTrue(after.version() > v1, "版本应推进（v1=" + v1 + " → " + after.version() + "）");
        assertTrue(winner.equals("writer-0") || winner.equals("writer-1"),
            "落库必须是两位写者之一的完整值（非交错损坏）：" + winner);
    }

    /**
     * Step2：双线程并发 BaseStore.putIfVersion（MEMORY.md 同 key 同 expectedVersion）。
     * 红线：恰一 true 一 false；DB 行内容=成功方完整 JSON；version 恰 +1。
     */
    @Test
    void concurrentPutIfVersionMemoryMd() throws Exception {
        DataSource ds = controlledDataSource();
        AbstractJdbcDialect dialect = AbstractJdbcDialect.from(ds).autoCreateTable(false).build();
        BaseStore baseStore = JdbcStore.builder(ds).dialect(dialect).initializeSchema(false).build();
        List<String> ns = List.of(NS);
        String key = "MEMORY.md";

        baseStore.put(ns, key, Map.of("content", "base"));
        long v1 = baseStore.get(ns, key).version();

        CountDownLatch start = new CountDownLatch(1);
        List<Object> outcomes = new CopyOnWriteArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                String payload = "memory-" + i;
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                        boolean ok = baseStore.putIfVersion(ns, key, Map.of("content", payload), v1);
                        outcomes.add(ok);
                    } catch (Throwable t) {
                        outcomes.add(t);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(30, TimeUnit.SECONDS);
            }
        }

        System.out.println("[P11] putIfVersion 并发结果 = " + outcomes.stream()
            .map(o -> o instanceof Boolean b ? "ok=" + b : "EX:" + o.getClass().getSimpleName())
            .toList());

        long wins = outcomes.stream().filter(o -> Boolean.TRUE.equals(o)).count();
        assertEquals(1, wins, "并发 putIfVersion 必须恰好一个成功（结果=" + outcomes + "）");

        var after = baseStore.get(ns, key);
        String content = (String) after.value().get("content");
        assertTrue(content.equals("memory-0") || content.equals("memory-1"),
            "落库必须是成功方完整内容（非静默覆盖/交错）：" + content);
        assertEquals(v1 + 1, after.version(), "version 恰好 +1");
    }
}
