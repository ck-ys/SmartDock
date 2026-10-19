package org.dromara.assistant.core;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TODO 清单信号仓（ZCode 对齐 Q2，批A 2026-10-10）：todo_write 工具把整表替换后的
 * 清单存进来，{@link AssistantEventEnricher} 在同线程下一批事件前取走并转发 todo
 * CUSTOM 帧给前端（StatPanel 渲染任务清单分区）。模式照抄 {@link AssistantPlanSignal}。
 *
 * <p>键=threadId（工具侧拿不到 runId，但 ToolDeps 带 aguiThreadId；单飞行回合设计下
 * 同 thread 同时至多一个 run 在跑，无串扰）。整表替换语义=同回合多次 todo_write 只需
 * 转发最新一版，先到的被 put 覆盖天然合并。持久化真源在会话行 todo_items 列，
 * 本仓只管回合进行中的实时推送；run 中断未取走的残段由环形截断兜底防泄漏。</p>
 */
@Component
public class AssistantTodoSignal {

    /** 一条任务（ZCode TodoItem 同构；status=pending/in_progress/completed，priority=high/medium/low） */
    public record TodoItem(String content, String status, String priority) {
    }

    /** threadId → 最新清单 */
    private final Map<String, List<TodoItem>> signals;

    /** 兜底清理上限：超限按插入序淘汰最旧（run 中断未收尾的残段防泄漏） */
    private static final int MAX_ENTRIES = 128;

    public AssistantTodoSignal() {
        this.signals = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, List<TodoItem>> eldest) {
                return size() > MAX_ENTRIES;
            }
        });
    }

    /** todo_write 工具落信号（threadId 空或清单空=非会话态，丢弃；整表替换） */
    public void submit(String threadId, List<TodoItem> items) {
        if (threadId == null || threadId.isBlank() || items == null || items.isEmpty()) {
            return;
        }
        signals.put(threadId, List.copyOf(items));
    }

    /** enricher 取走并清除；无记录返回 null */
    public List<TodoItem> take(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        return signals.remove(threadId);
    }
}
