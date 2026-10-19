package org.dromara.test.assistant;

import org.dromara.assistant.limiter.AssistantDailyQuota;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.redis.utils.RedisUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批G G5 日限额单测（2026-10-06，Q5/Q16）。回合闸 Lua 原子（限 N 过 N 拒第 N+1、
 * 补偿回退）；租户日预算=账本真值直查（当日 AI_ASSISTANT_CHAT 借方合计 ≥ 预算=拒）。
 */
@Tag("p0probe")
@SpringBootTest
class G5QuotaTest {

    private static final Long U = 910000000000301L;
    private static final String TENANT = "995311";
    private static final long FAKE_WALLET = 9199000000003999L;
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Autowired
    AssistantDailyQuota quota;
    @Autowired
    DataSource ds;

    @AfterEach
    void cleanup() throws Exception {
        var ks = RedisUtils.keys("t:" + TENANT + ":quota:turn:" + U + ":*");
        if (ks != null && !ks.isEmpty()) {
            RedisUtils.deleteObject(ks);
        }
        exec("DELETE FROM dcs_tenant_wallet_ledger WHERE wallet_id=" + FAKE_WALLET);
    }

    @Test
    void turnGateLimitAndRefund() {
        quota.tryTurn(TENANT, U, 3);
        quota.tryTurn(TENANT, U, 3);
        quota.tryTurn(TENANT, U, 3);
        ServiceException ex = assertThrows(ServiceException.class, () -> quota.tryTurn(TENANT, U, 3),
            "第 4 回合必须拒绝");
        assertTrue(ex.getMessage().contains("明日 0 点"), "话术含恢复时点: " + ex.getMessage());

        quota.refundTurn(TENANT, U);
        assertDoesNotThrow(() -> quota.tryTurn(TENANT, U, 3), "补偿后放行（被拒回合不算数）");
        assertEquals(3, quota.turnsUsed(TENANT, U), "INCR 计数=3 上限锚定");
    }

    @Test
    void zeroLimitMeansTenantKillSwitch() {
        ServiceException ex = assertThrows(ServiceException.class, () -> quota.tryTurn(TENANT, U, 0),
            "0=租户侧急停");
        assertTrue(ex.getMessage().contains("停用"));
    }

    @Test
    void budgetGateReadsLedgerTruth() throws Exception {
        String today = LocalDate.now(ZoneId.of("Asia/Shanghai")).format(DAY);
        // 今日扣费 100 元（伪钱包分区, 不碰真实流水链；amount 恒正+direction OUT 为账本真身口径）
        exec("INSERT INTO dcs_tenant_wallet_ledger (ledger_id, wallet_id, tenant_id, service_code, biz_type,"
            + " biz_id, direction, amount, currency, balance_before, balance_after, idempotency_key, del_flag, create_time)"
            + " VALUES (" + (System.nanoTime() % 100000000L + 2100000000000000000L) + ", " + FAKE_WALLET
            + ", '" + TENANT + "', 'AI_ASSISTANT_CHAT', 'DEDUCT', 1, 'OUT', 100, 'CNY', 100, 0,"
            + " 'g5-budget-" + today + "', '0', CURRENT_TIMESTAMP)");
        ServiceException over = assertThrows(ServiceException.class,
            () -> quota.checkBudget(TENANT, new BigDecimal("50")), "当日消费≥预算必须拒绝");
        assertTrue(over.getMessage().contains("预算"), "话术点名预算: " + over.getMessage());
        assertDoesNotThrow(() -> quota.checkBudget(TENANT, new BigDecimal("200")), "预算充裕放行");
        assertDoesNotThrow(() -> quota.checkBudget(TENANT, null), "无预算配置=不限");
    }

    private void exec(String sql) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
