package org.dromara.test.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 批D D1 三方对账 E2E（2026-10-05，真模型 1 回合，admin@000000，节俭口径）:
 *
 * <p>判据(spec 批D 收口)：一轮真实对话后——
 * 钱包扣减额 = ledger.amount = ROUND(usage.cost_amount,4) 三方相等；
 * usage.caller=ASSISTANT_CHAT / billing_mode=OFFICIAL_TOKEN；
 * ledger.service_code=AI_ASSISTANT_CHAT / biz_type=DEDUCT；
 * 该钱包流水链零断裂(检查②同款 SQL)；
 * 同一用户二次请求准入闸放行(在册续期)。</p>
 */
@Tag("p0probe")
@SpringBootTest
class D1E2eReconcileTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "http://127.0.0.1:8080";
    private static final String CLIENT_ID = "e5cd7e4891bf95d1d19206ce24a7b32e";

    @Autowired
    DataSource ds;

    @Test
    void oneRealTurnThreeWayReconcile() throws Exception {
        String token = loginAdmin();
        BigDecimal walletBefore = walletBalance();
        // 水位锚: 本轮对话前已存在的最大单据/流水 ID(一轮对话可产生多笔模型调用计费, 对账按"本轮新增集合")
        Long usageWatermark = queryForLong(
            "SELECT COALESCE(MAX(usage_id),0) FROM dcs_ai_usage WHERE tenant_id='000000' AND caller='ASSISTANT_CHAT'");
        Long ledgerWatermark = queryForLong(
            "SELECT COALESCE(MAX(ledger_id),0) FROM dcs_tenant_wallet_ledger WHERE idempotency_key LIKE 'ai-assistant:000000:%'");

        // 第一轮: 闲聊问题(不触发工具, 最小 token 消耗)
        ChatResult r1 = chat(token, "d1-e2e-" + System.currentTimeMillis(), "用一句话介绍你自己");
        assertEquals(0, r1.errors.size(), "第一轮不应出错: " + r1.errors);
        assertTrue(r1.text.length() > 0, "应有文本回复");

        // 落账在 SSE 流收尾后提交(客户端收完事件≠已入账), 轮询水位到稳态(计数连续两次不变, dsflash P2-5:
        // "见一行就 break"会漏掉 ReAct 循环的迟到多笔, 两次独立快照闪断)
        Long newUsages = 0L;
        Long prev = -1L;
        int stable = 0;
        for (int i = 0; i < 40; i++) {
            Thread.sleep(500);
            newUsages = queryForLong(
                "SELECT COUNT(*) FROM dcs_ai_usage WHERE tenant_id='000000' AND caller='ASSISTANT_CHAT' AND usage_id>" + usageWatermark);
            if (newUsages.equals(prev) && newUsages > 0) {
                stable++;
                if (stable >= 3) {
                    break;
                }
            } else {
                stable = 0;
            }
            prev = newUsages;
        }
        assertTrue(newUsages != null && newUsages > 0, "应产生助手计费单据(SSE 收尾后 20s 稳态)");

        BigDecimal walletAfter = walletBalance();
        List<Map<String, Object>> usages = query(
            "SELECT usage_id, scene, cost_amount, wallet_ledger_id, caller, billing_mode, currency, success"
                + " FROM dcs_ai_usage WHERE tenant_id='000000' AND caller='ASSISTANT_CHAT' AND usage_id>" + usageWatermark);
        BigDecimal costSum = BigDecimal.ZERO;
        for (Map<String, Object> usage : usages) {
            assertEquals("OFFICIAL_TOKEN", usage.get("billing_mode"), "按官方 token 计费");
            assertEquals("1", String.valueOf(usage.get("success")), "调用成功");
            assertNotNull(usage.get("wallet_ledger_id"), "单据挂上钱包流水");
            assertTrue(String.valueOf(usage.get("scene")).startsWith("assist:chat:seq"), "scene 带服务端序号: " + usage.get("scene"));
            costSum = costSum.add(((BigDecimal) usage.get("cost_amount")).setScale(4, RoundingMode.HALF_UP));
        }

        List<Map<String, Object>> ledgers = query(
            "SELECT ledger_id, amount, service_code, biz_type, direction, biz_id, balance_before, balance_after"
                + " FROM dcs_tenant_wallet_ledger WHERE idempotency_key LIKE 'ai-assistant:000000:%'"
                + " AND ledger_id>" + ledgerWatermark + " ORDER BY ledger_id");
        assertEquals(usages.size(), ledgers.size(), "本轮单据与流水逐笔对应(ReAct 循环可多笔)");
        BigDecimal ledgerSum = BigDecimal.ZERO;
        List<String> bizIds = usages.stream().map(u -> String.valueOf(u.get("usage_id"))).toList();
        for (Map<String, Object> ledger : ledgers) {
            assertEquals("AI_ASSISTANT_CHAT", ledger.get("service_code"));
            assertEquals("DEDUCT", ledger.get("biz_type"));
            assertEquals("OUT", ledger.get("direction"));
            assertTrue(bizIds.contains(String.valueOf(ledger.get("biz_id"))), "流水 biz_id 指向本轮单据");
            ledgerSum = ledgerSum.add(((BigDecimal) ledger.get("amount")).setScale(4, RoundingMode.HALF_UP));
        }
        // 坑68: 逐笔同精度
        List<Map<String, Object>> joined = query(
            "SELECT ROUND(u.cost_amount,4) AS cost4, l.amount FROM dcs_ai_usage u"
                + " JOIN dcs_tenant_wallet_ledger l ON l.biz_id=u.usage_id"
                + " WHERE u.tenant_id='000000' AND u.caller='ASSISTANT_CHAT' AND u.usage_id>" + usageWatermark);
        for (Map<String, Object> row : joined) {
            assertEquals(0, ((BigDecimal) row.get("cost4")).compareTo((BigDecimal) row.get("amount")),
                "usage 成本(4位)=流水金额 逐笔: " + row);
        }

        BigDecimal walletDelta = walletBefore.subtract(walletAfter).setScale(4, RoundingMode.HALF_UP);
        assertEquals(0, costSum.compareTo(ledgerSum), "单据合计=流水合计: " + costSum + " vs " + ledgerSum);
        assertEquals(0, walletDelta.compareTo(ledgerSum), "钱包扣减=流水合计: " + walletDelta + " vs " + ledgerSum);
        assertTrue(walletDelta.compareTo(BigDecimal.ZERO) > 0, "本轮真实扣费(金额>0)");

        // 流水链零断裂(该钱包)
        Long breaks = queryForLong(
            "SELECT COUNT(*) FROM (SELECT balance_before,"
                + " LAG(balance_after) OVER (PARTITION BY wallet_id ORDER BY ledger_id) AS prev_after"
                + " FROM dcs_tenant_wallet_ledger WHERE del_flag='0' AND wallet_id=(SELECT wallet_id"
                + " FROM dcs_tenant_wallet WHERE tenant_id='000000' AND wallet_type='MAIN' AND del_flag='0')) t"
                + " WHERE prev_after IS NOT NULL AND balance_before <> prev_after");
        assertEquals(0L, breaks, "000000 钱包流水链零断裂");

        // 准入闸: 同用户第二轮放行(在册成员续期)
        ChatResult r2 = chat(token, "d1-e2e2-" + System.currentTimeMillis(), "好的，谢谢");
        assertEquals(0, r2.errors.size(), "在册用户第二轮应正常: " + r2.errors);
    }

    // ========== 配方(同 B3) ==========

    private String loginAdmin() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", "admin");
        body.put("password", "admin123");
        body.put("tenantId", "000000");
        body.put("clientId", CLIENT_ID);
        body.put("grantType", "password");
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + "/auth/login").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(MAPPER.writeValueAsBytes(body));
        }
        JsonNode r = MAPPER.readTree(conn.getInputStream());
        assertEquals(200, r.path("code").asInt(), "admin 登录应成功");
        return r.path("data").path("access_token").asText();
    }

    private ChatResult chat(String token, String threadId, String question) throws Exception {
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("id", "m-" + System.nanoTime());
        msg.put("role", "user");
        msg.put("content", question);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("threadId", threadId);
        body.put("runId", "run-" + System.nanoTime());
        body.put("messages", List.of(msg));
        body.put("state", new LinkedHashMap<>());
        body.put("forwardedProps", new LinkedHashMap<>());
        HttpURLConnection conn = (HttpURLConnection) new URL(BASE + "/agui/run/dcs-assistant").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + token);
        conn.setRequestProperty("clientid", CLIENT_ID);
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10_000);
        conn.setReadTimeout(180_000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(MAPPER.writeValueAsBytes(body));
        }
        ChatResult result = new ChatResult();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                JsonNode ev;
                try {
                    ev = MAPPER.readTree(line.substring(5).trim());
                } catch (Exception parseEx) {
                    continue;
                }
                switch (ev.path("type").asText("")) {
                    case "TEXT_MESSAGE_CONTENT" -> result.text.append(ev.path("delta").asText(""));
                    case "RUN_ERROR" -> result.errors.add(ev.toString());
                    default -> { }
                }
            }
        }
        return result;
    }

    private BigDecimal walletBalance() throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(
            "SELECT balance_amount FROM dcs_tenant_wallet WHERE tenant_id='000000'"
                + " AND wallet_type='MAIN' AND del_flag='0'"); ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getBigDecimal(1) : null;
        }
    }

    private List<Map<String, Object>> query(String sql) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            int cols = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= cols; i++) {
                    row.put(rs.getMetaData().getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
            return rows;
        }
    }

    private Long queryForLong(String sql) throws Exception {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : null;
        }
    }

    static class ChatResult {
        final StringBuilder text = new StringBuilder();
        final List<String> errors = new ArrayList<>();
    }
}
