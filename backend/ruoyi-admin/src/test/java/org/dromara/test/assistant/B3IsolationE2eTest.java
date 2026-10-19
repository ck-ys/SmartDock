package org.dromara.test.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.satoken.utils.LoginHelper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 隔离 E2E（批B Task B3，2026-10-04；真模型真 SSE，对账 spec §15.1/§15.2 可前置项）。
 *
 * <p>前提：本地 8080 运行含批B代码的 jar（免验证码）；dev 库两租户均有启用 CHAT 配置。
 * 判据：①跨租户双账号同问——工具结果 queriedAs 回显本租户、任务行 pg 逐行对账属本租户；
 * ②业务数据问题必须先调工具（B0 装配纪律）；
 * ③会话状态键前缀=t:{tenant}:{uid}（§15.2 四路对账之 state 键路）。
 * 钱包/usage 聚合对账=批D 前置项，师生 act 能力差异=批C 前置项，届时补验。</p>
 */
@Tag("p0probe")
@SpringBootTest
class B3IsolationE2eTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "http://127.0.0.1:8080";
    private static final String CLIENT_ID = "e5cd7e4891bf95d1d19206ce24a7b32e";
    private static final Long USER_978311 = 2104824918278434818L;

    @Autowired
    DataSource dataSource;

    /** admin 走真实 HTTP 登录（本地免验证码） */
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

    /** 978311 用户走 P06 进程内登录铸 token（dev Redis 共享，本地后端可验） */
    private String login978311() {
        return P06Login.mint("978311", USER_978311, "13800001111");
    }

    /** SSE 一轮对话：回收工具调用名/工具结果 JSON/文本答案 */
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
        conn.setReadTimeout(120_000);
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
                String type = ev.path("type").asText("");
                switch (type) {
                    case "TOOL_CALL_START" -> result.toolNames.add(ev.path("toolCallName").asText(""));
                    case "TOOL_CALL_RESULT" -> result.toolResults.add(ev.toString());
                    case "TEXT_MESSAGE_CONTENT" -> result.text.append(ev.path("delta").asText(""));
                    case "RUN_ERROR" -> result.errors.add(ev.toString());
                    default -> { }
                }
            }
        }
        return result;
    }

    /** 从工具结果里找第一个 query_my_missions 的 JSON（TOOL_CALL_RESULT 事件体内嵌） */
    private JsonNode firstMissionToolResult(ChatResult r) throws Exception {
        for (String raw : r.toolResults) {
            JsonNode node = MAPPER.readTree(raw);
            String text = node.toString();
            if (text.contains("query_my_missions") || text.contains("\"queriedAs\"")) {
                // 事件结构兼容两种形态：直接是结果 JSON 或嵌在字段里
                JsonNode found = deepFind(node, "queriedAs");
                if (found != null) {
                    return node;
                }
            }
        }
        return null;
    }

    private JsonNode deepFind(JsonNode node, String key) {
        if (node == null) {
            return null;
        }
        if (node.has(key)) {
            return node.get(key);
        }
        for (JsonNode child : node) {
            JsonNode hit = deepFind(child, key);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** 模型是否调工具有随机性：换新线程重试至多 3 次直到调了 query_my_missions */
    private ChatResult chatUntilMissionTool(String token, String threadPrefix, String question)
        throws Exception {
        ChatResult last = null;
        for (int i = 1; i <= 3; i++) {
            last = chat(token, threadPrefix + "-" + i, question);
            if (last.toolNames.stream().anyMatch(n -> n.contains("query_my_missions"))) {
                return last;
            }
            System.out.println("[b3retry] 第" + i + "轮未调工具 tools=" + last.toolNames
                + " text=" + last.text.substring(0, Math.min(80, last.text.length())));
        }
        return last;
    }

    @Test
    void crossTenantSameQuestionDifferentData() throws Exception {
        String question = "请先调用 query_my_missions 工具查询我的任务列表，"
            + "然后根据工具返回的数据列出前三个任务的编号和名称。";

        // admin@000000
        ChatResult adminChat = chatUntilMissionTool(loginAdmin(), "b3-admin-th", question);
        assertTrue(adminChat.toolNames.stream().anyMatch(n -> n.contains("query_my_missions")),
            "admin 业务数据问题必须先调 query_my_missions（B0 纪律），实际调用：" + adminChat.toolNames);
        assertTrue(adminChat.text.length() > 0, "admin 应有文本答案");
        assertEquals(0, adminChat.errors.size(), "admin 会话不应 RUN_ERROR");

        // 978311 用户（同问）
        ChatResult userChat = chatUntilMissionTool(login978311(), "b3-user-th", question);
        assertTrue(userChat.toolNames.stream().anyMatch(n -> n.contains("query_my_missions")),
            "978311 用户同问也必须调工具，实际调用：" + userChat.toolNames);

        // 工具结果身份回显 + 任务行租户对账（从答案与工具结果合并校验）
        verifyMissionsBelongToTenant(adminChat, "000000");
        verifyMissionsBelongToTenant(userChat, "978311");
    }

    /**
     * 从工具结果 JSON 校验：①queriedAs 身份回显=期望租户（必查）；
     * ②若含任务行（978311 用户可能合法地零任务），逐行 pg 对账属期望租户。
     */
    /** TOOL_CALL_RESULT 事件 → 解开双层编码后的工具返回文档（解不开返回外层事件） */
    private JsonNode decodeToolResult(String raw) throws Exception {
        JsonNode event = MAPPER.readTree(raw);
        JsonNode content = event.path("content");
        if (content.isTextual()) {
            String text = content.asText().trim();
            JsonNode parsed = tryParse(text);
            if (parsed == null && text.startsWith("\"") && text.endsWith("\"")) {
                JsonNode literal = MAPPER.readTree(text);
                if (literal.isTextual()) {
                    parsed = tryParse(literal.asText());
                }
            }
            if (parsed != null) {
                return parsed;
            }
        }
        return event;
    }

    private void verifyMissionsBelongToTenant(ChatResult chat, String tenantId) throws Exception {
        boolean echoedTenant = false;
        List<String> missionIds = new ArrayList<>();
        for (String raw : chat.toolResults) {
            JsonNode root = decodeToolResult(raw);
            JsonNode queriedAs = deepFind(root, "queriedAs");
            if (queriedAs != null && queriedAs.path("tenantId").asText().equals(tenantId)) {
                echoedTenant = true;
            }
            collectMissionIds(root, missionIds);
        }
        assertTrue(echoedTenant, "工具结果必须回显 queriedAs.tenantId=" + tenantId);
        if (missionIds.isEmpty()) {
            return;
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT tenant_id FROM dcs_mission WHERE mission_id = ?")) {
            for (String mid : missionIds) {
                ps.setLong(1, Long.parseLong(mid));
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "任务应存在：" + mid);
                    assertEquals(tenantId, rs.getString(1),
                        "越权红线：tenant=" + tenantId + " 的助手工具结果不得含他租户任务 " + mid);
                }
            }
        }
    }

    private JsonNode tryParse(String text) {
        String t = text.trim();
        if (!(t.startsWith("[") || t.startsWith("{"))) {
            return null;
        }
        try {
            return MAPPER.readTree(t);
        } catch (Exception e) {
            return null;
        }
    }

    private void collectMissionIds(JsonNode node, List<String> out) {
        if (node == null) {
            return;
        }
        if (node.isObject() && node.has("missionId")) {
            String mid = node.path("missionId").asText("");
            if (!mid.isEmpty() && mid.length() >= 15) {
                out.add(mid);
            }
        }
        if (node.isTextual()) {
            // TOOL_CALL_RESULT 的 content 是双层编码：值=带引号的 JSON 字符串字面量，
            // 先按字面量解一层得到内层 JSON 文本，再解析成文档遍历
            String text = node.asText().trim();
            JsonNode parsed = tryParse(text);
            if (parsed == null && text.startsWith("\"") && text.endsWith("\"")) {
                try {
                    JsonNode literal = MAPPER.readTree(text);
                    if (literal.isTextual()) {
                        parsed = tryParse(literal.asText());
                    }
                } catch (Exception ignored) {
                    // 非法字面量跳过
                }
            }
            if (parsed != null) {
                collectMissionIds(parsed, out);
            }
            return;
        }
        for (JsonNode child : node) {
            collectMissionIds(child, out);
        }
    }

    @Test
    void sessionsStateKeyCarriesTenantUserPrefix() throws Exception {
        // 触发两轮会话后（前一测试已写；本测试独立触发 978311 一轮保自足）
        chat(login978311(), "b3-state-th-1", "请用工具查我的待办，只要一句话总结。");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                 "SELECT count(*) FROM agentscope.agentscope_sessions WHERE session_id LIKE ?")) {
            ps.setString(1, "t:978311:" + USER_978311 + ":%");
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next() && rs.getLong(1) > 0,
                    "§15.2：978311 会话状态键必须带租户+用户前缀（实际 0 行）");
            }
        }
    }

    static class ChatResult {
        final List<String> toolNames = new ArrayList<>();
        final List<String> toolResults = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        final StringBuilder text = new StringBuilder();
    }
}
