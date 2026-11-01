package org.dromara.assistant.core;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
 * <p><b>owner 三元组（2026-10-31 ②）</b>：pending 记提交人（tenantId+userId，租户=登录
 * 快照，与 PlanTools 的 {@code ToolIdentity.from→user.getTenantId()} 同源）。清闸只认
 * owner 本人——同租户异主/他租户同 threadId 一律不动（纵深防御：上游归属校验已挡，
 * 这里再兜一层，防未来新调用点绕过校验误清他人闸）。</p>
 */
@Component
public class AssistantPlanSignal {

    /** 计划载荷：title 缺省时前端显示「这个任务我打算分 N 步做」 */
    public record PlanPayload(String title, List<String> steps) {
    }

    /** 待确认态 TTL：用户放着不管也不会永久锁死写操作 */
    public static final long PENDING_TTL_MS = 30 * 60 * 1000L;

    /**
     * 待确认态：截止时刻 + 提交人三元组（2026-10-31 ②）。tenantId 必须是登录快照
     * （与 PlanTools 的 ToolIdentity 同源）——超管切租户后动态租户 ≠ 快照，owner
     * 比对认快照，清闸路（端点/resolver）都用快照才能对得上。
     */
    public record PendingState(long deadline, String tenantId, Long userId) {
    }

    /** 兜底清理上限：超限按插入序淘汰最旧（run 中断未收尾的残段防泄漏） */
    private static final int MAX_ENTRIES = 128;

    /** threadId → 计划 */
    private final Map<String, PlanPayload> signals;

    /** threadId → 待确认态（owner 三元组随行；linked 保插入序，超限淘汰最旧） */
    private final Map<String, PendingState> pending;

    /** threadId → 待出网的拒绝回执（enricher 取走转 CUSTOM 'tool_denied'）；
     *  中间件自产的 ToolResult 事件框架不发布（三席实证），拒绝可见性只能走本通道 */
    private final Map<String, List<Map<String, Object>>> denied;

    public AssistantPlanSignal() {
        this.signals = cappedMap();
        this.pending = cappedMap();
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

    /** submit_plan 工具落信号（threadId 空=非会话态，丢弃）；同时置计划待确认态并记 owner */
    public void submit(String threadId, String tenantId, Long userId, String title, List<String> steps) {
        submit(threadId, tenantId, userId, title, steps, PENDING_TTL_MS);
    }

    /** ttlMs 可注入版（单测传 0 构造已过期态验证 TTL 兜底）；正常路径一律走缺省重载 */
    public void submit(String threadId, String tenantId, Long userId, String title, List<String> steps, long ttlMs) {
        if (threadId == null || threadId.isBlank() || steps == null || steps.isEmpty()) {
            return;
        }
        signals.put(threadId, new PlanPayload(
            title == null || title.isBlank() ? null : title,
            List.copyOf(steps)));
        pending.put(threadId, new PendingState(System.currentTimeMillis() + ttlMs, tenantId, userId));
    }

    /** 计划是否处于「已提交、未确认」态（题8 硬闸：此态下写类工具一律拒绝）；过期即放开 */
    public boolean isPending(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return false;
        }
        PendingState st = pending.get(threadId);
        if (st == null) {
            return false;
        }
        if (System.currentTimeMillis() >= st.deadline()) {
            pending.remove(threadId);
            return false;
        }
        return true;
    }

    /**
     * 用户确认/改计划/取消（前端上报 planDecision / 点取消当场打端点）后放开。
     * owner（tenantId+userId）不匹配=不动返回 false——闸只能由提交人本人清；
     * 无 pending 也返回 false（幂等 no-op，调用方不必区分）。
     */
    public boolean clearPending(String threadId, String tenantId, Long userId) {
        if (threadId == null || threadId.isBlank()) {
            return false;
        }
        PendingState st = pending.get(threadId);
        if (st == null
            || !Objects.equals(st.tenantId(), tenantId)
            || !Objects.equals(st.userId(), userId)) {
            return false;
        }
        pending.remove(threadId);
        return true;
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
