package org.dromara.assistant.core;

import org.springframework.stereotype.Component;

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
 */
@Component
public class AssistantPlanSignal {

    /** 计划载荷：title 缺省时前端显示「这个任务我打算分 N 步做」 */
    public record PlanPayload(String title, List<String> steps) {
    }

    /** threadId → 计划 */
    private final Map<String, PlanPayload> signals;

    /** 兜底清理上限：超限按插入序淘汰最旧（run 中断未收尾的残段防泄漏） */
    private static final int MAX_ENTRIES = 128;

    public AssistantPlanSignal() {
        this.signals = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, PlanPayload> eldest) {
                return size() > MAX_ENTRIES;
            }
        });
    }

    /** submit_plan 工具落信号（threadId 空=非会话态，丢弃） */
    public void submit(String threadId, String title, List<String> steps) {
        if (threadId == null || threadId.isBlank() || steps == null || steps.isEmpty()) {
            return;
        }
        signals.put(threadId, new PlanPayload(
            title == null || title.isBlank() ? null : title,
            List.copyOf(steps)));
    }

    /** enricher 取走并清除；无记录返回 null */
    public PlanPayload take(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        return signals.remove(threadId);
    }
}
