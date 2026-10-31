package org.dromara.assistant.core;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型降级提示信号仓（题5 案1，2026-10-31 拍板）。
 *
 * <p>用户自选的模型失效/被关自选/不在白名单时，{@code AssistantModelFactory} 会静默
 * 回退默认链——以前只有一行 warn 日志，用户只看到「模型怎么变了」。本信号按 threadId
 * 暂存一条人话提示，{@link AssistantEventEnricher} 在同线程下一批事件前取走并转发
 * {@code model_degrade} CUSTOM 帧，前端弹一条系统提示（模式照抄 {@link AssistantPlanSignal}）。</p>
 */
@Component
public class AssistantModelDegradeSignal {

    /** 兜底清理上限：超限淘汰最旧（run 中断未取走的残段防泄漏） */
    private static final int MAX_ENTRIES = 128;

    private final Map<String, String> notices =
        Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                return size() > MAX_ENTRIES;
            }
        });

    /** 建会话时落一条提示（threadId 空=非会话态，丢弃） */
    public void submit(String threadId, String message) {
        if (threadId == null || threadId.isBlank() || message == null || message.isBlank()) {
            return;
        }
        notices.put(threadId, message);
    }

    /** enricher 取走并清除；无记录返回 null */
    public String take(String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        return notices.remove(threadId);
    }
}
