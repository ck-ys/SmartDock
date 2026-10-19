package org.dromara.test.assistant;

import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.Model;
import org.dromara.assistant.billing.BillingModelDecorator;
import org.dromara.common.core.exception.ServiceException;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.domain.bo.WalletOperationBo;
import com.smartdock.service.support.AiBillingService;
import com.smartdock.service.support.AiTokenUsageSnapshot;
import com.smartdock.service.support.TenantWalletService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批D D1 计费五件套单测(2026-10-05, 真 PG/真服务链路, 零模型成本):
 *
 * <p>D1-③ 条件扣减并发移植验证 + D1-④ 同锚点并发双击/顺序重放/篡改 + 欠费不透支
 * + D1-① 装饰器估算兜底/前置余额闸。判据全部落在数据库实际行(数值断言具体值)。</p>
 *
 * <p>口径: 测试租户 987311(钱包 getOrCreate 自动赠 10 元体验金), 期望单笔成本动态取自
 * 价格行(快照 1000 in + 100 out × v4-flash 当前 CNY 价; 2026-10-06 官方峰段价 2.00/0.04/8.00,
 * 断言不硬编码目录价, 改价不破测试)。@AfterAll 按实扣总额
 * manualRecharge 回填, 钱包净额零漂移(流水链完整)。</p>
 */
@Tag("p0probe")
@SpringBootTest
class D1BillingTest {

    private static final String TENANT = "987311";
    /** 期望单笔成本(懒加载, 从 v4-flash 当前 CNY 价格行动态计算) */
    private BigDecimal costPerCall;
    private static final String RUN_KEY = "d1-" + System.currentTimeMillis();
    /** 实扣总额(回填用) */
    private static final AtomicReference<BigDecimal> CONSUMED = new AtomicReference<>(BigDecimal.ZERO);

    @Autowired
    TenantWalletService walletService;
    @Autowired
    AiBillingService billingService;
    @Autowired
    com.smartdock.service.IDcsAiModelPriceService priceService;
    @Autowired
    DataSource ds;

    /** 每测试后回填实扣(dsflash P2-5: 断言中断不再把漂移留在 dev 钱包) */
    @org.junit.jupiter.api.AfterEach
    void restoreAfterEach() throws Exception {
        restoreConsumed();
    }

    // ========== 辅助 ==========

    private DcsAiConfig chatConfig(Long configId) {
        DcsAiConfig cfg = new DcsAiConfig();
        cfg.setConfigId(configId);
        cfg.setTenantId(TENANT);
        cfg.setProvider("DEEPSEEK");
        cfg.setModelName("deepseek-v4-flash");
        cfg.setBillingMode("OFFICIAL_TOKEN");
        cfg.setServiceFeeRate(BigDecimal.ZERO);
        return cfg;
    }

    /** 快照 1000 in + 100 out 按当前 v4-flash CNY 价折算的期望成本(round4, 与坑68单次舍入口径一致) */
    private BigDecimal costPerCall() throws Exception {
        if (costPerCall == null) {
            List<Map<String, Object>> rows = query(
                "SELECT input_price_per_million, output_price_per_million FROM dcs_ai_model_price"
                    + " WHERE model_name='deepseek-v4-flash' AND currency='CNY' AND enabled='1' AND del_flag='0'");
            assertEquals(1, rows.size(), "v4-flash CNY 价格行应恰一条");
            Map<String, Object> row = rows.get(0);
            costPerCall = ((BigDecimal) row.get("input_price_per_million")).multiply(new BigDecimal("0.001"))
                .add(((BigDecimal) row.get("output_price_per_million")).multiply(new BigDecimal("0.0001")))
                .setScale(4, RoundingMode.HALF_UP);
        }
        return costPerCall;
    }

    private Long walletId() throws Exception {
        return queryForLong("SELECT wallet_id FROM dcs_tenant_wallet WHERE tenant_id=? AND wallet_type='MAIN' AND del_flag='0'", TENANT);
    }

    private BigDecimal balance() throws Exception {
        walletService.getOrCreateMainWallet(TENANT);
        return queryForDecimal("SELECT balance_amount FROM dcs_tenant_wallet WHERE wallet_id=?", walletId());
    }

    private Long queryForLong(String sql, Object... params) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private BigDecimal queryForDecimal(String sql, Object... params) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getBigDecimal(1) : null;
            }
        }
    }

    private List<Map<String, Object>> query(String sql, Object... params) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                int cols = rs.getMetaData().getColumnCount();
                while (rs.next()) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    for (int i = 1; i <= cols; i++) {
                        row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                    }
                    rows.add(row);
                }
                return rows;
            }
        }
    }

    private void bind(PreparedStatement ps, Object... params) throws Exception {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private static final java.util.concurrent.atomic.AtomicInteger RESTORE_SEQ = new java.util.concurrent.atomic.AtomicInteger();

    private void restoreConsumed() throws Exception {
        BigDecimal total = CONSUMED.get();
        if (total.compareTo(BigDecimal.ZERO) > 0) {
            WalletOperationBo bo = new WalletOperationBo();
            bo.setAmount(total);
            bo.setReason("D1 单测实扣回填(净额零漂移)");
            bo.setIdempotencyKey("d1-restore:" + RUN_KEY + ":" + RESTORE_SEQ.incrementAndGet());
            walletService.manualRecharge(walletId(), bo);
            CONSUMED.set(BigDecimal.ZERO);
        }
    }

    // ========== D1-③+④: 并发扣减/同键竞态/重放篡改/欠费 ==========

    /**
     * 50 并发不同键: 全部成功, 钱包扣减=50×0.0013 分毫不差, 流水/单据各 50 条,
     * 逐行 ledger.amount = ROUND(usage.cost,4)(坑68 同精度), 该钱包流水链零断裂。
     */
    @Test
    void fiftyConcurrentDistinctKeysExact() throws Exception {
        BigDecimal before = balance();
        int threads = 50;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger failures = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int seq = i;
            pool.submit(() -> {
                try {
                    start.await();
                    billingService.recordTransactional(chatConfig(9100000000003L), "ASSISTANT_CHAT",
                        RUN_KEY + ":conc:" + seq, new AiTokenUsageSnapshot(1000, 0, 100, 1100),
                        5L, true, null, 1L, RUN_KEY + ":c" + seq);
                } catch (Throwable t) {
                    failures.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS), "并发扣减应在时限内完成");
        pool.shutdown();
        assertEquals(0, failures.get(), "50 笔并发全部成功(异常数)");
        CONSUMED.accumulateAndGet(costPerCall().multiply(BigDecimal.valueOf(threads)), BigDecimal::add);

        BigDecimal after = balance();
        assertEquals(before.subtract(costPerCall().multiply(BigDecimal.valueOf(threads))).setScale(4, RoundingMode.HALF_UP),
            after.setScale(4, RoundingMode.HALF_UP), "钱包扣减=50×单笔期望值 精确值");
        Long ledgerCount = queryForLong(
            "SELECT COUNT(*) FROM dcs_tenant_wallet_ledger WHERE idempotency_key LIKE ?", RUN_KEY + ":c%");
        Long usageCount = queryForLong(
            "SELECT COUNT(*) FROM dcs_ai_usage WHERE scene LIKE ? AND caller='ASSISTANT_CHAT'", RUN_KEY + ":conc:%");
        assertEquals(50L, ledgerCount, "流水恰 50 条");
        assertEquals(50L, usageCount, "单据恰 50 条且全部挂上流水");
        // 坑68: 逐行同精度(round4 == ledger.amount)
        List<Map<String, Object>> rows = query(
            "SELECT u.usage_id, ROUND(u.cost_amount,4) AS cost4, l.amount FROM dcs_ai_usage u"
                + " JOIN dcs_tenant_wallet_ledger l ON l.biz_id=u.usage_id WHERE u.scene LIKE ?", RUN_KEY + ":conc:%");
        assertEquals(50, rows.size());
        for (Map<String, Object> row : rows) {
            assertEquals(0, ((BigDecimal) row.get("cost4")).compareTo((BigDecimal) row.get("amount")),
                "usage 四舍五入值与流水金额逐行相等: " + row);
        }
        // 流水链连续(同钱包 LAG 邻接)
        Long chainBreaks = queryForLong(
            "SELECT COUNT(*) FROM (SELECT balance_before,"
                + " LAG(balance_after) OVER (PARTITION BY wallet_id ORDER BY ledger_id) AS prev_after"
                + " FROM dcs_tenant_wallet_ledger WHERE del_flag='0' AND wallet_id=?) t"
                + " WHERE prev_after IS NOT NULL AND balance_before <> prev_after", walletId());
        assertEquals(0L, chainBreaks, "条件扣减路径流水链零断裂");
    }

    /**
     * 同锚点并发双击(P16 E 未测形态): 20 线程同幂等键——恰 1 条流水/1 条单据/一笔扣减;
     * 失败方唯一索引冲突整事务回滚(扣减一并回退), 不透支不重复。
     */
    @Test
    void sameKeyConcurrentDoubleClickSingleDeduct() throws Exception {
        BigDecimal before = balance();
        String key = RUN_KEY + ":double";
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(20);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger ok = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    billingService.recordTransactional(chatConfig(9100000000003L), "ASSISTANT_CHAT",
                        RUN_KEY + ":double", new AiTokenUsageSnapshot(1000, 0, 100, 1100),
                        5L, true, null, 1L, key);
                    ok.incrementAndGet();
                } catch (Throwable ignored) {
                    // 竞态败者: 唯一索引冲突回滚
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(120, TimeUnit.SECONDS));
        pool.shutdown();
        assertTrue(ok.get() >= 1, "至少一个成功落账");
        BigDecimal after = balance();
        assertEquals(before.subtract(costPerCall()).setScale(4, RoundingMode.HALF_UP), after.setScale(4, RoundingMode.HALF_UP),
            "双击只扣一笔单笔期望值");
        Long ledgerCount = queryForLong(
            "SELECT COUNT(*) FROM dcs_tenant_wallet_ledger WHERE idempotency_key=?", key);
        Long usageCount = queryForLong(
            "SELECT COUNT(*) FROM dcs_ai_usage WHERE scene=? AND caller='ASSISTANT_CHAT'", RUN_KEY + ":double");
        assertEquals(1L, ledgerCount, "同键并发只产生一条流水");
        assertEquals(1L, usageCount, "同键并发只产生一条单据(败者整事务回滚)");
        CONSUMED.accumulateAndGet(costPerCall(), BigDecimal::add);
    }

    /**
     * 顺序重放同键: 返回原单据 ID, 不再插 usage/ledger/不动余额——防对账④误报的头部短路。
     * 篡改(同键换更大用量): 幂等键优先, 篡改无效。
     */
    @Test
    void sequentialReplayAndTamperRejected() throws Exception {
        BigDecimal before = balance();
        String key = RUN_KEY + ":replay";
        Long first = billingService.recordTransactional(chatConfig(9100000000003L), "ASSISTANT_CHAT",
            RUN_KEY + ":replay", new AiTokenUsageSnapshot(1000, 0, 100, 1100), 5L, true, null, 1L, key);
        assertNotNull(first, "首次落账返回单据 ID");
        Long replayed = billingService.recordTransactional(chatConfig(9100000000003L), "ASSISTANT_CHAT",
            RUN_KEY + ":replay", new AiTokenUsageSnapshot(1000, 0, 100, 1100), 5L, true, null, 1L, key);
        assertEquals(first, replayed, "重放返回原单据 ID");
        // 篡改: 同键换 100 倍用量
        Long tampered = billingService.recordTransactional(chatConfig(9100000000003L), "ASSISTANT_CHAT",
            RUN_KEY + ":replay", new AiTokenUsageSnapshot(100000, 0, 10000, 110000), 5L, true, null, 1L, key);
        assertEquals(first, tampered, "篡改请求仍被幂等键短路");
        assertEquals(before.subtract(costPerCall()).setScale(4, RoundingMode.HALF_UP),
            balance().setScale(4, RoundingMode.HALF_UP), "重放/篡改不动余额");
        Long usageCount = queryForLong(
            "SELECT COUNT(*) FROM dcs_ai_usage WHERE scene=? AND caller='ASSISTANT_CHAT'", RUN_KEY + ":replay");
        assertEquals(1L, usageCount, "重放不重复插单");
        CONSUMED.accumulateAndGet(costPerCall(), BigDecimal::add);
    }

    /**
     * 欠费不透支: 单笔成本远超余额 → 条件更新 affected=0 → usage 留痕打免扣标记,
     * 钱包不动、无流水(检查④对这类行不误报)。
     */
    @Test
    void insufficientBalanceNoOverdraft() throws Exception {
        BigDecimal before = balance();
        String key = RUN_KEY + ":poor";
        Long usageId = billingService.recordTransactional(chatConfig(9100000000003L), "ASSISTANT_CHAT",
            "d1:poor", new AiTokenUsageSnapshot(100_000_000, 0, 100_000_000, 200_000_000),
            5L, true, null, 1L, key);
        assertNotNull(usageId);
        List<Map<String, Object>> rows = query(
            "SELECT wallet_ledger_id, pricing_snapshot FROM dcs_ai_usage WHERE usage_id=?", usageId);
        assertEquals(1, rows.size());
        assertNull(rows.get(0).get("wallet_ledger_id"), "欠费单不挂流水");
        assertTrue(String.valueOf(rows.get(0).get("pricing_snapshot")).contains("\"walletDeducted\":false"),
            "打免扣标记(对账④识别): " + rows.get(0).get("pricing_snapshot"));
        Long ledgerCount = queryForLong(
            "SELECT COUNT(*) FROM dcs_tenant_wallet_ledger WHERE idempotency_key=?", key);
        assertEquals(0L, ledgerCount, "无流水");
        assertEquals(before.setScale(4, RoundingMode.HALF_UP), balance().setScale(4, RoundingMode.HALF_UP),
            "欠费拦截不动余额(条件更新原子性)");
    }

    // ========== D1-①: 装饰器(桩模型, 零真实模型成本) ==========

    /**
     * usage 缺失三件套: 桩模型不回传 usage → 装饰器按字符估算(prompt≥1/completion≥256 兜底),
     * scene 打 est 标记, 正常落账扣费。
     */
    @Test
    void decoratorEstimatesWhenUsageMissing() throws Exception {
        BigDecimal before = balance();
        DcsAiConfig cfg = chatConfig(System.nanoTime()); // 每轮唯一 configId → 幂等键不撞历史轮
        AtomicInteger delegateCalls = new AtomicInteger();
        Model stub = new Model() {
            @Override
            public reactor.core.publisher.Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                                                                     List<io.agentscope.core.model.ToolSchema> tools,
                                                                     io.agentscope.core.model.GenerateOptions options) {
                delegateCalls.incrementAndGet();
                return reactor.core.publisher.Flux.just(new ChatResponse("r1",
                    List.of(TextBlock.builder().text("这是用于估算的回复文本").build()),
                    null, java.util.Map.of(), "stop"));
            }

            @Override
            public String getModelName() {
                return "stub-model";
            }

            @Override
            public int getContextWindowSize() {
                return 128000;
            }
        };
        BillingModelDecorator decorator = new BillingModelDecorator(stub, cfg, 1L, billingService, walletService,
            priceService, new org.dromara.assistant.billing.AssistantCostLedger());
        assertNotNull(decorator.stream(List.of(), List.of(), null).blockLast(java.time.Duration.ofSeconds(30)));
        Thread.sleep(500); // doFinally 同线程落账后再断言
        assertEquals(1, delegateCalls.get());
        List<Map<String, Object>> rows = query(
            "SELECT prompt_tokens, completion_tokens, cost_amount, wallet_ledger_id, scene"
                + " FROM dcs_ai_usage WHERE config_id=? AND caller='ASSISTANT_CHAT'", cfg.getConfigId());
        assertEquals(1, rows.size(), "估算兜底也必须落一条单据(防免费用)");
        assertTrue(((Number) rows.get(0).get("prompt_tokens")).intValue() >= 1, "估算 prompt≥1");
        assertTrue(((Number) rows.get(0).get("completion_tokens")).intValue() >= 256, "估算 completion 兜底下限 256");
        assertNotNull(rows.get(0).get("wallet_ledger_id"), "估算计费真实扣款");
        BigDecimal ledgerAmount = queryForDecimal(
            "SELECT amount FROM dcs_tenant_wallet_ledger WHERE idempotency_key LIKE ?",
            "ai-assistant:" + TENANT + ":" + cfg.getConfigId() + ":%:1");
        assertNotNull(ledgerAmount, "装饰器幂等键落流水");
        assertEquals(((BigDecimal) rows.get(0).get("cost_amount")).setScale(4, RoundingMode.HALF_UP),
            ledgerAmount.setScale(4, RoundingMode.HALF_UP), "估算金额同精度入账");
        CONSUMED.accumulateAndGet(ledgerAmount, BigDecimal::add);
    }

    /**
     * 对账④ SQL 级断言(glm53 终审 P1 补课): 免扣标记行(insufficient/非本币)不得计入 deduct_missing,
     * LIKE 裸串等值匹配/丢逗号坏 JSON 回归在此立刻现形。
     */
    @Test
    void reconcileCheckFourNoFalseAlarm() throws Exception {
        List<Map<String, Object>> rows = query(
            "SELECT u.tenant_id,"
                + " COUNT(*) FILTER (WHERE u.wallet_ledger_id IS NULL"
                + "   AND COALESCE(u.pricing_snapshot, '') NOT LIKE '%\"walletDeducted\":false%'"
                + "   AND ROUND(u.cost_amount, 4) > 0) AS deduct_missing"
                + " FROM dcs_ai_usage u WHERE u.caller='ASSISTANT_CHAT' AND u.success='1'"
                + " GROUP BY u.tenant_id");
        for (Map<String, Object> row : rows) {
            assertEquals(0L, ((Number) row.get("deduct_missing")).longValue(),
                "租户 " + row.get("tenant_id") + " 检查④不得误报漏扣(免扣标记行应被识别)");
        }
    }

    /**
     * AU-04 地板价分流: 上游故障回合(流错误 failed=true)不得抬全租户地板价——
     * 旧逻辑只判「本笔没扣到钱」, 模型商一次 5xx 就惩罚全租户 7 天; 真欠费
     * (正常完成但余额扣不动)照抬。测试后清地板键(TTL 7 天, 残留会拦 dev 租户正常回合)。
     */
    @Test
    void au04FloorOnlyForRealDebt() throws Exception {
        java.util.List<String> stale = clearFloorKeys();
        org.junit.jupiter.api.Assumptions.assumeTrue(stale.isEmpty(), "前置清残留后应无地板键");

        // —— 场景A: 上游流错误(模拟模型商 5xx/断流) ——
        DcsAiConfig cfgA = chatConfig(System.nanoTime());
        Model errStub = new Model() {
            @Override
            public reactor.core.publisher.Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                                                                     List<io.agentscope.core.model.ToolSchema> tools,
                                                                     io.agentscope.core.model.GenerateOptions options) {
                return reactor.core.publisher.Flux.error(new RuntimeException("upstream 5xx"));
            }

            @Override
            public String getModelName() {
                return "stub-model";
            }

            @Override
            public int getContextWindowSize() {
                return 128000;
            }
        };
        BillingModelDecorator decA = new BillingModelDecorator(errStub, cfgA, 1L, billingService, walletService,
            priceService, new org.dromara.assistant.billing.AssistantCostLedger());
        try {
            decA.stream(List.of(), List.of(), null).blockLast(java.time.Duration.ofSeconds(30));
        } catch (RuntimeException expected) {
            /* 流错误即场景A本身 */
        }
        Thread.sleep(600); // doFinally 落账后再断言
        assertTrue(floorKeysOfTenant().isEmpty(), "上游故障回合不得抬全租户地板价: " + floorKeysOfTenant());

        // —— 场景B: 正常完成但天价账单扣不动(真欠费) → 照抬 ——
        DcsAiConfig cfgB = chatConfig(System.nanoTime());
        Model okStub = new Model() {
            @Override
            public reactor.core.publisher.Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                                                                     List<io.agentscope.core.model.ToolSchema> tools,
                                                                     io.agentscope.core.model.GenerateOptions options) {
                return reactor.core.publisher.Flux.just(new ChatResponse("r1",
                    List.of(TextBlock.builder().text("收到").build()),
                    new ChatUsage(100_000_000, 100_000_000, 0.0), java.util.Map.of(), "stop"));
            }

            @Override
            public String getModelName() {
                return "stub-model";
            }

            @Override
            public int getContextWindowSize() {
                return 128000;
            }
        };
        BillingModelDecorator decB = new BillingModelDecorator(okStub, cfgB, 1L, billingService, walletService,
            priceService, new org.dromara.assistant.billing.AssistantCostLedger());
        assertNotNull(decB.stream(List.of(), List.of(), null).blockLast(java.time.Duration.ofSeconds(30)));
        Thread.sleep(600);
        java.util.List<String> raised = floorKeysOfTenant();
        assertEquals(1, raised.size(), "真欠费回合应抬地板价");
        // 万一余额真扣动了(地板不抬, 测试会红), 把流水计入回填防漂移
        BigDecimal ledgerAmount = queryForDecimal(
            "SELECT amount FROM dcs_tenant_wallet_ledger WHERE idempotency_key LIKE ?",
            "ai-assistant:" + TENANT + ":" + cfgB.getConfigId() + ":%:1");
        if (ledgerAmount != null) {
            CONSUMED.accumulateAndGet(ledgerAmount, BigDecimal::add);
        }
        clearFloorKeys();
    }

    /** 扫真身(多租户 Redis 前缀宇宙): 地板键可能带任意前缀, 按 pattern 扫再按租户过滤 */
    private java.util.List<String> floorKeysOfTenant() {
        java.util.List<String> keys = new ArrayList<>();
        org.dromara.common.redis.utils.RedisUtils.getClient().getKeys()
            .getKeysStreamByPattern("*dcs:assistant:billing:floor*")
            .forEach(k -> {
                if (k.contains(TENANT)) {
                    keys.add(k);
                }
            });
        return keys;
    }

    private java.util.List<String> clearFloorKeys() {
        java.util.List<String> keys = floorKeysOfTenant();
        keys.forEach(k -> org.dromara.common.redis.utils.RedisUtils.getClient().getKeys().delete(k));
        return keys;
    }

    /**
     * 前置余额闸: 无钱包租户(可用 0)→ 不调模型不落账, Flux.error 友好话术。
     */
    @Test
    void preGateBlocksBeforeModelCall() throws Exception {
        DcsAiConfig cfg = chatConfig(System.nanoTime());
        cfg.setTenantId("999999"); // 无钱包租户
        AtomicInteger delegateCalls = new AtomicInteger();
        Model stub = new Model() {
            @Override
            public reactor.core.publisher.Flux<ChatResponse> stream(List<io.agentscope.core.message.Msg> messages,
                                                                     List<io.agentscope.core.model.ToolSchema> tools,
                                                                     io.agentscope.core.model.GenerateOptions options) {
                delegateCalls.incrementAndGet();
                return reactor.core.publisher.Flux.empty();
            }

            @Override
            public String getModelName() {
                return "stub-model";
            }

            @Override
            public int getContextWindowSize() {
                return 128000;
            }
        };
        BillingModelDecorator decorator = new BillingModelDecorator(stub, cfg, 1L, billingService, walletService,
            priceService, new org.dromara.assistant.billing.AssistantCostLedger());
        ServiceException ex = assertThrows(ServiceException.class,
            () -> decorator.stream(List.of(), List.of(), null).blockFirst(java.time.Duration.ofSeconds(10)));
        assertTrue(ex.getMessage().contains("额度不足"), "友好话术: " + ex.getMessage());
        assertEquals(0, delegateCalls.get(), "拦截发生在模型调用前(零成本)");
        Long usageCount = queryForLong(
            "SELECT COUNT(*) FROM dcs_ai_usage WHERE tenant_id='999999' AND caller='ASSISTANT_CHAT'");
        assertEquals(0L, usageCount, "拦截不产生任何计费单据");
    }
}
