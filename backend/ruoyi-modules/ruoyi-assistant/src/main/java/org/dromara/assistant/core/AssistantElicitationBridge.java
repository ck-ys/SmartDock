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
 *   <li><b>懒清扫</b>：超时兜底不引入 @Scheduled——handle/answer 入口顺手扫过期
 *       pending 补 complete(CANCEL) 即移除，审计仍由对应 handle 出口记 ELICIT_TIMEOUT。</li>
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

    /** pending 上限兜底：超限拒绝新反问（CANCEL 立返）防极端泄漏。
     *  三审回修（dspro P2-1）：64→8——每个未答反问占 2 个 boundedElastic worker
     *  （callTool+handler），8 核机默认池 10×CPU，8 个并发挂起已 16 worker；asyncElicitation
     *  非阻塞改造登记 B+1 后可放宽 */
    private static final int MAX_PENDING = 8;

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

    /** 挂起条目：future 完成即出结果；settledBy 区分用户作答与超时清扫（审计口径）。
     *  三审回修：expiresAtMs 可变（审计落库后才定值，DB 耗时不吃答题预算） */
    static final class Pending {
        final String id;
        final String serverName;
        final String tenantId;
        final Long userId;
        final String threadId;
        final String message;
        volatile long expiresAtMs;
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

    /** McpPilotService 建 STDIO client 时引用：requestTimeout = 本值 + 工具预算余量。
     *  三审回修（双席共识 P2）：上限 540——elicit+5(handler)+30(request) 必须 < 600s
     *  纯心跳收割线（AssistantRunRecorder.PURE_HEARTBEAT_MS），超限配置直接钳到 540 */
    public int elicitTimeoutSeconds() {
        return Math.min(540, Math.max(60, elicitTimeoutSeconds));
    }

    /**
     * syncElicitation handler 入口（MCP SDK 把 sync 包到 boundedElastic worker——
     * 三审回修勘误：不阻塞 STDIO 入站线程，但每个未答反问占 2 个 worker，故 MAX_PENDING=8）。
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
        Pending p = new Pending(id, serverName, tenantId, userId, threadId,
            req.message() == null ? "" : req.message(), 0L);
        pending.put(id, p);
        audit(serverName, tenantId, userId, null, "ELICIT_ASK",
            truncate(req.message() == null ? "" : req.message()));
        // 三审回修（dsflash P2-14）：expiresAt 在审计之后才定——审计落库耗时不吃用户的答题预算
        long expiresAtMs = System.currentTimeMillis() + elicitTimeoutSeconds() * 1000L;
        p.expiresAtMs = expiresAtMs;
        submitSignal(threadId, cardPayload(id, serverName, req.message(), req.requestedSchema(),
            expiresAtMs - System.currentTimeMillis()));
        McpSchema.ElicitResult result;
        try {
            result = p.future.get(elicitTimeoutSeconds() + 5L, TimeUnit.SECONDS);
        } catch (Exception e) {
            // 等待中断/超时——统一按 CANCEL 兜底（server 收到"用户未作答"）
            synchronized (p) {
                if (p.future.complete(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.CANCEL, Map.of(), null))) {
                    p.settledBy = "timeout";
                }
            }
            result = p.future.join();
        } finally {
            // 三审回修（双席共识 P1）：超时/未答路径也必须移除——原实现只 answer() 删，
            // 64 次未答即打满 MAX_PENDING 后反问功能永久关闭（只有重启能恢复）
            pending.remove(id, p);
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
     * 三审回修（双席共识 P1）：以 complete() 的返回值定胜负——超时清扫已 CANCEL 后到达的
     * answer 必须报"已失效"而不是假成功；settledBy 只在真正 complete 赢家时写（审计不串台）。
     */
    public String answer(String tenantId, Long userId, String threadId, String id,
                         String action, Map<String, Object> content) {
        sweepExpired();
        Pending p = id == null ? null : pending.get(id);
        if (p == null) {
            return "该反问已失效（可能已作答或已超时关闭）";
        }
        if (!p.tenantId.equals(tenantId) || !p.userId.equals(userId)
            || (threadId != null && !p.threadId.equals(threadId))) {
            return "该反问不属于当前会话，无法作答";
        }
        McpSchema.ElicitResult r = toResult(action, content);
        if (r == null) {
            return "action 必须是 accept / decline / cancel 之一";
        }
        synchronized (p) {
            if (p.future.isDone()) {
                // 超时清扫/其它线程已定终态——如实告知而非假成功（三审回修 P1）
                return "该反问已失效（可能已作答或已超时关闭）";
            }
            p.settledBy = "answer";
            p.future.complete(r);
        }
        pending.remove(id, p);
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

    /** ElicitRequest → 前端卡载荷（纯函数，单测直测）。
     *  三审回修（dspro P2-6）：下发 remainingMs 相对时长而非绝对时间戳——客户端时钟
     *  与服务端 wall clock 偏差用户会看到"一出现就超时/一直不收卡"，前端以
     *  Date.now()+remainingMs 计算本地到期时刻 */
    public static Map<String, Object> cardPayload(String id, String serverName, String message,
                                                  Map<String, Object> requestedSchema, long remainingMs) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", id);
        v.put("serverName", serverName == null ? "" : serverName);
        v.put("message", message == null ? "" : message);
        v.put("requestedSchema", requestedSchema == null ? Map.of() : requestedSchema);
        v.put("remainingMs", Math.max(0L, remainingMs));
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
            if (p.expiresAtMs > 0 && p.expiresAtMs <= now && !p.future.isDone()) {
                synchronized (p) {
                    if (p.future.complete(new McpSchema.ElicitResult(McpSchema.ElicitResult.Action.CANCEL, Map.of(), null))) {
                        p.settledBy = "timeout";
                    }
                }
                // 三审回修（双席共识 P1）：完成即移除——防 64 次未答打满 MAX_PENDING 永久关闭
                pending.remove(p.id, p);
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
