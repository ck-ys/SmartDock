package org.dromara.assistant.core;

import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.mapper.DcsMcpCallLogMapper;
import org.dromara.assistant.domain.DcsMcpCallLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * MCP Elicitation 桥（G3-A1，2026-10-21）：外部工具在执行中反问（server 发
 * elicitation/create）→ 本桥挂起等用户在前端反问卡作答 → 回填三态
 * ACCEPT/DECLINE/CANCEL 给 server，tools/call 继续跑完，run 全程 RUNNING 不中断。
 *
 * <p>设计口径（调研 .workshop/plans 归档 + dcs #3100/#3104 对照）：</p>
 * <ul>
 *   <li><b>不复用 interrupt/resume</b>：elicitation 发生在工具执行中，挂起的是
 *       server 侧在飞请求，run 收尾即死；与权限闸（执行前 interrupt + resume 续命）
 *       机制不契合。本桥=「run 内挂起」，与 AGUI resume 契约零接触。</li>
 *   <li><b>时间预算</b>：答题超时必须 &lt; AssistantRunRecorder 纯心跳 10 分钟收割线
 *       （默认 300s 留一半余量）；且 McpClientBuilder 的 requestTimeout 覆盖
 *       tools/call 全程含答题等待，McpPilotService 对注册了 elicitation 的
 *       STDIO client 已同步调大，两侧配置同源（本类的 elicitTimeoutSeconds()）。</li>
 *   <li><b>审计</b>：复用 dcs_mcp_call_log，动作 ELICIT_ASK / ELICIT_ACCEPT /
 *       ELICIT_DECLINE / ELICIT_CANCEL / ELICIT_TIMEOUT（action 列 varchar(20) 实查放得下，
 *       无 DDL）。ASk 在 handler 入口记，终态在 handle 出口统一记（单一出口防重复）。</li>
 *   <li><b>懒清扫</b>：超时兜底不引入 @Scheduled——handle/answer/take 入口顺手扫过期
 *       pending 补 complete(CANCEL)，审计仍由对应 handle 出口记 ELICIT_TIMEOUT。</li>
 *   <li><b>卡不可恢复的已知取舍</b>：前端刷新丢卡（H1 只恢复权限 HITL 卡），兜底=超时
 *       CANCEL + 审计留痕；用户点「停止生成」取消在飞 callTool 后 future 由超时清扫回收。</li>
 * </ul>
 */
@Slf4j
@Component
public class AssistantElicitationBridge {

    /** 答题等待上限（秒）；必须 < 600s 纯心跳收割线且留余量 */
    @Value("${smartdock.mcp.elicit-timeout-seconds:300}")
    private int elicitTimeoutSeconds;

    /** pending 上限兜底：超限拒绝新反问（CANCEL 立返）防极端泄漏 */
    private static final int MAX_PENDING = 64;

    /** 信号仓上限：照 AssistantPlanSignal 兜底口径（run 中断未取走的卡防泄漏） */
    private static final int MAX_SIGNALS = 128;

    private final DcsMcpCallLogMapper callLogMapper;

    /** elicitationId → 挂起中的反问 */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    /** threadId → 待出网的反问卡信号（enricher 取走转发 CUSTOM 'elicitation' 帧） */
    private final Map<String, List<Map<String, Object>>> signals =
        Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<Map<String, Object>>> eldest) {
                return size() > MAX_SIGNALS;
            }
        });

    /** 挂起条目：future 完成即出结果；settledBy 区分用户作答与超时清扫（审计口径） */
    static final class Pending {
        final String id;
        final String serverName;
        final String tenantId;
        final Long userId;
        final String threadId;
        final String message;
        final long expiresAtMs;
        final CompletableFuture<McpSchema.ElicitResult> future = new CompletableFuture<>();
        volatile String settledBy;
        Pending(String id, String serverName, String tenantId, Long userId,
                String threadId, String message, long expiresAtMs) {
            this.id = id;
            this.serverName = serverName;
            this.tenantId = tenantId;
            this.userId = userId;
            this.threadId = threadId;
            this.message = message;
            this.expiresAtMs = expiresAtMs;
        }
    }

    public AssistantElicitationBridge(DcsMcpCallLogMapper callLogMapper) {
        this.callLogMapper = callLogMapper;
    }

    /** McpPilotService 建 STDIO client 时引用：requestTimeout = 本值 + 30s 往返余量 */
    public int elicitTimeoutSeconds() {
        return Math.max(60, elicitTimeoutSeconds);
    }

    /**
     * syncElicitation handler 入口（阻塞在 MCP 入站线程——试点只对 STDIO 行开启，
     * 答题由 HTTP 端点线程 complete future，不依赖入站，无死锁）。
     */
    public McpSchema.ElicitResult handle(String serverName, String tenantId, Long userId,
                                         String threadId, McpSchema.ElicitRequest req) {
        sweepExpired();
        if (threadId == null || threadId.isBlank() || req == null) {
            // 非会话态（理论不可达：attachEnabled 恒带 thread）——立拒不挂起
            return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.CANCEL, Map.of(), null);
        }
        if (pending.size() >= MAX_PENDING) {
            log.warn("[Elicit] pending 满（{}）拒绝新反问 server={}", MAX_PENDING, serverName);
            return new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.CANCEL, Map.of(), null);
        }
        String id = UUID.randomUUID().toString();
        long expiresAtMs = System.currentTimeMillis() + elicitTimeoutSeconds() * 1000L;
        Pending p = new Pending(id, serverName, tenantId, userId, threadId,
            req.message() == null ? "" : req.message(), expiresAtMs);
        pending.put(id, p);
        audit(serverName, tenantId, userId, null, "ELICIT_ASK",
            truncate(req.message() == null ? "" : req.message()));
        submitSignal(threadId, cardPayload(id, serverName, req.message(), req.requestedSchema(), expiresAtMs));
        McpSchema.ElicitResult result;
        try {
            result = p.future.get(elicitTimeoutSeconds() + 5L, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 等待中断/超时——统一按 CANCEL 兜底（server 收到"用户未作答"）
            if (p.future.complete(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.CANCEL, Map.of(), null))) {
                p.settledBy = "timeout";
            }
            result = p.future.join();
        }
        // 单一出口记终态：settledBy=timeout→ELICIT_TIMEOUT；否则按 action
        String action = String.valueOf(result.action());
        boolean timeout = "timeout".equals(p.settledBy);
        audit(serverName, tenantId, userId, null, timeout ? "ELICIT_TIMEOUT" : "ELICIT_" + action,
            "id=" + id + (timeout ? " 超时未作答" : " 用户已作答"));
        return result;
    }

    /**
     * 答题端点入口：归属校验（tenant/user/thread 三段与挂起快照比对，防跨租户回填）
     * → complete future。幂等：已答/不存在给人话错误。
     */
    public String answer(String tenantId, Long userId, String threadId, String id,
                         String action, Map<String, Object> content) {
        sweepExpired();
        Pending p = id == null ? null : pending.remove(id);
        if (p == null) {
            return "该反问已失效（可能已作答或已超时关闭）";
        }
        if (!p.tenantId.equals(tenantId) || !p.userId.equals(userId)
            || (threadId != null && !p.threadId.equals(threadId))) {
            // 归属不符：不能 complete（防止跨租户替答），恢复挂起条目等真主人
            pending.put(id, p);
            return "该反问不属于当前会话，无法作答";
        }
        McpSchema.ElicitResult r = toResult(action, content);
        if (r == null) {
            pending.put(id, p);
            return "action 必须是 accept / decline / cancel 之一";
        }
        p.settledBy = "answer";
        p.future.complete(r);
        return null;
    }

    /** enricher 取走并清除该线程所有待出网卡信号 */
    public List<Map<String, Object>> take(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        List<Map<String, Object>> list = signals.remove(threadId);
        return list == null || list.isEmpty() ? null : list;
    }

    /** answer 三态映射（纯函数，单测直测）：null=非法 action */
    public static McpSchema.ElicitResult toResult(String action, Map<String, Object> content) {
        McpSchema.ElicitResult.Action a;
        if ("accept".equalsIgnoreCase(action)) {
            a = McpSchema.ElicitResult.Action.ACCEPT;
        } else if ("decline".equalsIgnoreCase(action)) {
            a = McpSchema.ElicitResult.Action.DECLINE;
        } else if ("cancel".equalsIgnoreCase(action)) {
            a = McpSchema.ElicitResult.Action.CANCEL;
        } else {
            return null;
        }
        Map<String, Object> c = content == null ? Map.of() : content;
        return new McpSchema.ElicitResult(a, c, null);
    }

    /** ElicitRequest → 前端卡载荷（纯函数，单测直测） */
    public static Map<String, Object> cardPayload(String id, String serverName, String message,
                                                  Map<String, Object> requestedSchema, long expiresAtMs) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        v.put("serverName", serverName == null ? "" : serverName);
        v.put("message", message == null ? "" : message);
        v.put("requestedSchema", requestedSchema == null ? Map.of() : requestedSchema);
        v.put("expiresAt", expiresAtMs);
        return v;
    }

    private void submitSignal(String threadId, Map<String, Object> payload) {
        synchronized (signals) {
            signals.computeIfAbsent(threadId, k -> new ArrayList<>()).add(payload);
        }
    }

    /** 懒清扫：过期未答 → complete(CANCEL)（handle 出口统一记 ELICIT_TIMEOUT） */
    private void sweepExpired() {
        long now = System.currentTimeMillis();
        for (Pending p : pending.values()) {
            if (p.expiresAtMs <= now && !p.future.isDone()) {
                p.settledBy = "timeout";
                p.future.complete(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.CANCEL, Map.of(), null));
            }
        }
    }

    /** 审计（与 McpPilotService.audit 同口径；跨包复用不可见，独立小实现） */
    private void audit(String serverName, String tenantId, Long userId, String toolName,
                       String action, String errorMsg) {
        try {
            DcsMcpCallLog l = new DcsMcpCallLog();
            l.setTenantId(tenantId);
            l.setUserId(userId);
            l.setServerName(serverName);
            l.setToolName(toolName);
            l.setAction(action);
            l.setStatus("1");
            l.setErrorMsg(errorMsg);
            l.setCreateTime(new java.util.Date());
            callLogMapper.insert(l);
        } catch (Exception e) {
            log.warn("[Elicit] 审计写入失败（不连坐）：{}", e.getMessage());
        }
    }

    private static String truncate(String msg) {
        return msg == null ? "" : (msg.length() > 480 ? msg.substring(0, 480) : msg);
    }
}
