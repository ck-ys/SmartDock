package org.dromara.assistant.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 计划卡信号仓（二站③ AU-51，2026-10-08）：submit_plan 工具把计划存进来，
 * {@link AssistantEventEnricher} 在同线程下一批事件前取走并转发 plan CUSTOM 帧
 * 给前端（TurnBlock/PlanCard 渲染三按钮卡）。模式照抄 {@link org.dromara.assistant.billing.AssistantCostLedger}。
 *
 * <p>键=threadId（工具侧拿不到 runId，但 ToolDeps 带 aguiThreadId；单飞行回合
 * 设计下同 thread 同时至多一个 run 在跑，无串扰）。run 中断未取走的残段由
 * 环形截断兜底防泄漏（同 AU-45 口径）。</p>
 *
 * <p><b>题8 案1（2026-10-31 三席回修）</b>：待确认态不再按回合边界清——用户不点按钮、
 * 随口聊别的事，计划卡仍在屏幕上，此期间写操作必须继续被硬拒。清闸有四个口子：
 * 前端点「直接执行/改计划/取消」后随请求上报 planDecision（resolver 清）、点「取消」
 * 当场打的轻量清闸端点（2026-10-31 ②，不必等下一条消息）、新的 submit_plan
 * 覆盖、以及 30 分钟 TTL 兜底（防用户关页面后写操作被永久锁死）。</p>
 *
 * <p><b>多实例批 1（2026-11-01）</b>：pending/decision 在 redis 模式读写 Redis；dual 模式
 * 写本机+Redis、读本机并比对（灰度验证）；local 模式完全走本机。Redis 读失败时
 * {@link #isPending(String)} 返回 true（fail-closed，宁可拒绝写操作也不放行）。</p>
 *
 * <p><b>多实例批 4</b>：每次 submit 生成 planId；确认/取消必须带同一 planId 才能原子消费。
 * 第二张旧卡/第二次抢点返回 false，由调用方转 409，不重复执行。</p>
 */
@Slf4j
@Component
public class AssistantPlanSignal {

    /** 计划载荷：planId 给前端旧卡精确拒绝用；title 缺省时前端显示「这个任务我打算分 N 步做」 */
    public record PlanPayload(String planId, String title, List<String> steps) {
    }

    /** 待确认态：planId + 截止时刻 + 提交人三元组 */
    public record PendingState(String planId, long deadline, String tenantId, Long userId) {
    }

    /** 计划状态查询结果（状态机：pending/confirmed/cancelled/adjusted/expired/none/unavailable） */
    public record PlanState(String status, String planId) {
    }

    /** 本机最近一次处理结果（local/dual 的 state 查询兜底）；public 供 Jackson 反序列化 */
    public record Decision(String planId, String status, long at) {
    }

    /** 待确认态 TTL：用户放着不管也不会永久锁死写操作 */
    public static final long PENDING_TTL_MS = 30 * 60 * 1000L;
    /** 处理结果 TTL：另一标签页 3 秒轮询足够看到；5 分钟后旧卡也不再需要提示 */
    private static final long DECISION_TTL_MS = 5 * 60 * 1000L;

    /** 兜底清理上限：超限按插入序淘汰最旧（run 中断未收尾的残段防泄漏） */
    private static final int MAX_ENTRIES = 128;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, PlanPayload> signals;
    private final Map<String, PendingState> pending;
    private final Map<String, Decision> decisions;
    private final Map<String, List<Map<String, Object>>> denied;

    private final AssistantSharedState shared;

    /** 纯单测/无 Spring 场景：全部走本机 */
    public AssistantPlanSignal() {
        this(null);
    }

    @Autowired
    public AssistantPlanSignal(AssistantSharedState shared) {
        this.shared = shared;
        this.signals = cappedMap();
        this.pending = cappedMap();
        this.decisions = cappedMap();
        this.denied = cappedMap();
    }

    private static <V> Map<String, V> cappedMap() {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > MAX_ENTRIES;
            }
        });
    }

    private boolean sharedWrites() {
        return shared != null && shared.redisEnabled();
    }

    private boolean sharedReads() {
        return shared != null && shared.redisReadPreferred();
    }

    private static String pendingKey(String threadId) {
        return AssistantSharedState.key("plan:pending:" + threadId);
    }

    private static String decisionKey(String threadId) {
        return AssistantSharedState.key("plan:decision:" + threadId);
    }

    private static String toJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("计划状态序列化失败", e);
        }
    }

    private static PendingState pendingFromJson(String json) {
        try {
            return MAPPER.readValue(json, PendingState.class);
        } catch (Exception e) {
            return null;
        }
    }

    private static Decision decisionFromJson(String json) {
        try {
            return MAPPER.readValue(json, Decision.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** submit_plan 工具落信号（threadId 空=非会话态，丢弃）；同时置计划待确认态并记 owner */
    public void submit(String threadId, String tenantId, Long userId, String title, List<String> steps) {
        submit(threadId, tenantId, userId, title, steps, PENDING_TTL_MS);
    }

    /** ttlMs 可注入版（单测传 0 构造已过期态验证 TTL 兜底）；正常路径一律走缺省重载 */
    public void submit(String threadId, String tenantId, Long userId, String title, List<String> steps, long ttlMs) {
        if (threadId == null || threadId.isBlank() || steps == null || steps.isEmpty()) {
            return;
        }
        String planId = UUID.randomUUID().toString();
        PlanPayload payload = new PlanPayload(
            planId,
            title == null || title.isBlank() ? null : title,
            List.copyOf(steps));
        PendingState st = new PendingState(planId, System.currentTimeMillis() + ttlMs, tenantId, userId);
        signals.put(threadId, payload);
        pending.put(threadId, st);
        if (sharedWrites()) {
            try {
                shared.set(pendingKey(threadId), toJson(st), Duration.ofMillis(ttlMs));
            } catch (Exception e) {
                // redis 模式：共享状态写不进去，计划闸无法跨实例执行，必须让本轮 submit 失败
                if (sharedReads()) {
                    signals.remove(threadId);
                    pending.remove(threadId);
                    throw new IllegalStateException("共享状态暂不可用，计划无法保存，请稍后重试", e);
                }
                log.warn("[plan-signal] dual 模式 Redis 写入失败（本机仍可用）thread={}: {}",
                    threadId, e.getMessage());
            }
        }
    }

    /** 计划是否处于「已提交、未确认」态（题8 硬闸：此态下写类工具一律拒绝）；过期即放开 */
    public boolean isPending(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return false;
        }
        if (sharedReads()) {
            try {
                String json = shared.get(pendingKey(threadId));
                if (json == null) {
                    return false;
                }
                PendingState st = pendingFromJson(json);
                if (st == null) {
                    return true; // 读不懂=按 pending 处理，宁可拒绝
                }
                if (System.currentTimeMillis() >= st.deadline()) {
                    // 比较删除：读与删之间若已有新 submit 覆盖，不能把新计划删掉
                    shared.compareAndDelete(pendingKey(threadId), json);
                    return false;
                }
                return true;
            } catch (Exception e) {
                // 读不到共享状态：fail-closed，不能把写操作放出去
                log.warn("[plan-signal] Redis 读取失败，按 pending 拒绝写操作 thread={}: {}",
                    threadId, e.getMessage());
                return true;
            }
        }
        PendingState st = pending.get(threadId);
        if (!valid(st)) {
            if (st != null) {
                pending.remove(threadId);
            }
            // dual 灰度：本机没有但 Redis 有（请求被 LB 甩到了另一台）——仍按 pending 拒绝，
            // 宁可多拒一次写操作，也不能因为本机 Map 空就 fail-open
            if (sharedWrites() && Boolean.TRUE.equals(remotePending(threadId))) {
                log.warn("[plan-signal] dual 差异：本机无 pending，Redis 有（按 pending 拒绝写操作）thread={}",
                    threadId);
                return true;
            }
            return false;
        }
        if (sharedWrites()) {
            compareDual(threadId, st);
        }
        return true;
    }

    /** dual 兜底：Redis 是否存在有效 pending；读失败按 true（fail-closed） */
    private Boolean remotePending(String threadId) {
        try {
            String json = shared.get(pendingKey(threadId));
            if (json == null) {
                return Boolean.FALSE;
            }
            PendingState st = pendingFromJson(json);
            if (st == null) {
                return Boolean.TRUE;
            }
            if (System.currentTimeMillis() >= st.deadline()) {
                shared.compareAndDelete(pendingKey(threadId), json);
                return Boolean.FALSE;
            }
            return Boolean.TRUE;
        } catch (Exception e) {
            log.warn("[plan-signal] dual 读取 Redis pending 失败（按 pending 处理）thread={}: {}",
                threadId, e.getMessage());
            return Boolean.TRUE;
        }
    }

    private static boolean valid(PendingState st) {
        return st != null && System.currentTimeMillis() < st.deadline();
    }

    /** dual 模式只做差异观测，不影响本机读结果 */
    private void compareDual(String threadId, PendingState local) {
        try {
            String json = shared.get(pendingKey(threadId));
            if (json == null) {
                log.warn("[plan-signal] dual 差异：本机 pending 存在，Redis 缺失 thread={}", threadId);
                return;
            }
            PendingState remote = pendingFromJson(json);
            if (remote == null || !Objects.equals(remote.planId(), local.planId())) {
                log.warn("[plan-signal] dual 差异：planId 本机={} Redis={} thread={}",
                    local.planId(), remote == null ? "null" : remote.planId(), threadId);
            }
        } catch (Exception e) {
            log.warn("[plan-signal] dual 比对失败 thread={}: {}", threadId, e.getMessage());
        }
    }

    /**
     * 用户确认/改计划/取消后放开。owner（tenantId+userId）不匹配=不动返回 false；
     * 无 pending 也返回 false（幂等 no-op，调用方不必区分）。
     */
    public boolean clearPending(String threadId, String tenantId, Long userId) {
        return decide(threadId, tenantId, userId, null, "cancelled");
    }

    /**
     * 带 planId 的原子确认/取消：旧卡/第二次抢点因 pending 已删或 planId 不匹配返回 false。
     * planId 为空=兼容旧前端：仍按 thread+owner 原子消费，第二次自然失败。
     */
    public boolean decide(String threadId, String tenantId, Long userId, String planId, String decision) {
        if (threadId == null || threadId.isBlank()) {
            return false;
        }
        if (sharedReads()) {
            return decideRemote(threadId, tenantId, userId, planId, decision);
        }
        PendingState local = pending.get(threadId);
        if (valid(local) && ownerMatches(local, tenantId, userId) && planMatches(local, planId)) {
            // 三审回修（glm53-P2-4）：local 模式没有 Redis 的 Lua 原子性，get→remove 之间
            // 两个标签页同毫秒抢点会双双成功。按 remove 返回的实例比对，只有真正摘到
            // 自己看到的那份 pending 才算消费成功（被并发替换/抢走则返回 false）。
            if (pending.remove(threadId) != local) {
                return false;
            }
            rememberDecision(threadId, local.planId(), decision);
            if (sharedWrites()) {
                try {
                    String json = shared.get(pendingKey(threadId));
                    PendingState remote = json == null ? null : pendingFromJson(json);
                    // 只删同一 planId 的远端 pending——dual 下另一台可能已写入更新的计划
                    if (remote != null && Objects.equals(remote.planId(), local.planId())) {
                        shared.compareAndDelete(pendingKey(threadId), json);
                    }
                } catch (Exception e) {
                    log.warn("[plan-signal] dual 模式 Redis 清理失败（本机已清）thread={}: {}",
                        threadId, e.getMessage());
                }
            }
            return true;
        }
        if (local != null && !valid(local)) {
            pending.remove(threadId);
        }
        // dual 灰度：本机没消费到，尝试 Redis（请求可能落到了另一台实例）
        if (sharedWrites() && decideRemote(threadId, tenantId, userId, planId, decision)) {
            log.warn("[plan-signal] dual 差异：本机未命中，Redis 消费成功 thread={}", threadId);
            return true;
        }
        return false;
    }

    /** 从 Redis 原子消费 pending（redis 模式主路径；dual 兜底路径） */
    private boolean decideRemote(String threadId, String tenantId, Long userId, String planId, String decision) {
        try {
            String json = shared.get(pendingKey(threadId));
            if (json == null) {
                return false;
            }
            PendingState st = pendingFromJson(json);
            if (st == null || !ownerMatches(st, tenantId, userId) || !planMatches(st, planId)) {
                return false;
            }
            if (!shared.compareAndDelete(pendingKey(threadId), json)) {
                return false;
            }
            pending.remove(threadId);
            rememberDecision(threadId, st.planId(), decision);
            return true;
        } catch (Exception e) {
            log.warn("[plan-signal] Redis 原子消费失败，按未处理返回 thread={}: {}",
                threadId, e.getMessage());
            return false;
        }
    }

    private static boolean ownerMatches(PendingState st, String tenantId, Long userId) {
        return Objects.equals(st.tenantId(), tenantId) && Objects.equals(st.userId(), userId);
    }

    private static boolean planMatches(PendingState st, String planId) {
        return planId == null || planId.isBlank() || Objects.equals(st.planId(), planId);
    }

    private void rememberDecision(String threadId, String planId, String status) {
        Decision d = new Decision(planId, status, System.currentTimeMillis());
        decisions.put(threadId, d);
        if (sharedWrites()) {
            try {
                shared.set(decisionKey(threadId), toJson(d), Duration.ofMillis(DECISION_TTL_MS));
            } catch (Exception e) {
                log.warn("[plan-signal] 计划处理结果写 Redis 失败 thread={}: {}", threadId, e.getMessage());
            }
        }
    }

    /** 计划状态查询（前端旧卡轮询用）：pending 优先，其次最近一次处理结果 */
    public PlanState state(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return new PlanState("none", null);
        }
        if (sharedReads()) {
            try {
                String pendingJson = shared.get(pendingKey(threadId));
                if (pendingJson != null) {
                    PendingState st = pendingFromJson(pendingJson);
                    if (st != null && System.currentTimeMillis() < st.deadline()) {
                        return new PlanState("pending", st.planId());
                    }
                    shared.compareAndDelete(pendingKey(threadId), pendingJson);
                    return new PlanState("expired", st == null ? null : st.planId());
                }
                String decisionJson = shared.get(decisionKey(threadId));
                Decision d = decisionJson == null ? null : decisionFromJson(decisionJson);
                return d == null ? new PlanState("none", null) : new PlanState(d.status(), d.planId());
            } catch (Exception e) {
                log.warn("[plan-signal] 计划状态查询失败 thread={}: {}", threadId, e.getMessage());
                return new PlanState("unavailable", null);
            }
        }
        PendingState st = pending.get(threadId);
        if (valid(st)) {
            return new PlanState("pending", st.planId());
        }
        // dual 灰度：本机没有有效 pending 时，远端可能是另一台实例写的更新计划/处理结果
        PlanState remote = sharedWrites() ? remoteState(threadId) : null;
        if (remote != null && !"unavailable".equals(remote.status())) {
            boolean localExpired = st != null;
            if (!(localExpired && "none".equals(remote.status()))) {
                return remote;
            }
        }
        if (st != null) {
            pending.remove(threadId);
            return new PlanState("expired", st.planId());
        }
        Decision d = decisions.get(threadId);
        return d == null ? new PlanState("none", null) : new PlanState(d.status(), d.planId());
    }

    /** dual 兜底：Redis 中的计划状态；两边都没有返回 null；读失败返回 unavailable */
    private PlanState remoteState(String threadId) {
        try {
            String pendingJson = shared.get(pendingKey(threadId));
            if (pendingJson != null) {
                PendingState st = pendingFromJson(pendingJson);
                if (st != null && System.currentTimeMillis() < st.deadline()) {
                    return new PlanState("pending", st.planId());
                }
                shared.compareAndDelete(pendingKey(threadId), pendingJson);
                return new PlanState("expired", st == null ? null : st.planId());
            }
            String decisionJson = shared.get(decisionKey(threadId));
            Decision d = decisionJson == null ? null : decisionFromJson(decisionJson);
            return d == null ? new PlanState("none", null) : new PlanState(d.status(), d.planId());
        } catch (Exception e) {
            log.warn("[plan-signal] dual 读取 Redis 计划状态失败 thread={}: {}", threadId, e.getMessage());
            return new PlanState("unavailable", null);
        }
    }

    /** 硬闸拒绝回执入队（enricher 取走转帧，前端据此把工具步骤标成「已拒绝」） */
    public void submitDenied(String threadId, Map<String, Object> payload) {
        if (threadId == null || threadId.isBlank() || payload == null) {
            return;
        }
        synchronized (denied) {
            denied.computeIfAbsent(threadId, k -> new ArrayList<>()).add(payload);
        }
    }

    /** enricher 取走并清除该线程全部拒绝回执 */
    public List<Map<String, Object>> takeDenied(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        List<Map<String, Object>> list = denied.remove(threadId);
        return list == null || list.isEmpty() ? null : list;
    }

    /** enricher 取走并清除；无记录返回 null */
    public PlanPayload take(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        return signals.remove(threadId);
    }
}
