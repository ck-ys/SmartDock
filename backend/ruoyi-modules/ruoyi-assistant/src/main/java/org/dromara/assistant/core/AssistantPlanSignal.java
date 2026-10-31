package org.dromara.assistant.core;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 * 随口聊别的事，计划卡仍在屏幕上，此期间写操作必须继续被硬拒。清闸只有三个口子：
 * 前端点「直接执行/改计划/取消」后随请求上报 planDecision（resolver 清）、新的 submit_plan
 * 覆盖、以及 30 分钟 TTL 兜底（防用户关页面后写操作被永久锁死）。</p>
 */
@Component
public class AssistantPlanSignal {

    /** 计划载荷：title 缺省时前端显示「这个任务我打算分 N 步做」 */
    public record PlanPayload(String title, List<String> steps) {
    }

    /** 待确认态 TTL：用户放着不管也不会永久锁死写操作 */
    public static final long PENDING_TTL_MS = 30 * 60 * 1000L;

    /** 兜底清理上限：超限按插入序淘汰最旧（run 中断未收尾的残段防泄漏） */
    private static final int MAX_ENTRIES = 128;

    /** threadId → 计划 */
    private final Map<String, PlanPayload> signals;

    /** threadId → 待确认截止时刻（linked 保插入序，超限淘汰最旧） */
    private final Map<String, Long> pending;

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

    /** submit_plan 工具落信号（threadId 空=非会话态，丢弃）；同时置计划待确认态 */
    public void submit(String threadId, String title, List<String> steps) {
        if (threadId == null || threadId.isBlank() || steps == null || steps.isEmpty()) {
            return;
        }
        signals.put(threadId, new PlanPayload(
            title == null || title.isBlank() ? null : title,
            List.copyOf(steps)));
        pending.put(threadId, System.currentTimeMillis() + PENDING_TTL_MS);
    }

    /** 计划是否处于「已提交、未确认」态（题8 硬闸：此态下写类工具一律拒绝）；过期即放开 */
    public boolean isPending(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return false;
        }
        Long deadline = pending.get(threadId);
        if (deadline == null) {
            return false;
        }
        if (System.currentTimeMillis() >= deadline) {
            pending.remove(threadId);
            return false;
        }
        return true;
    }

    /** 用户确认/改计划/取消（前端上报 planDecision）或 TTL 到期后放开 */
    public void clearPending(String threadId) {
        if (threadId != null && !threadId.isBlank()) {
            pending.remove(threadId);
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
