package org.dromara.assistant.core;

import io.agentscope.core.agui.adapter.strategy.AgentEventConverter;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.event.ToolProgressEvent;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.TextBlock;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 工具进度转换器（ckys3 解锁板 题1 案1，2026-11-12）。
 *
 * <p><b>为什么需要它</b>：框架 AGUI 桥的内置 ToolResultEventConverter 对
 * ToolProgressEvent 的默认策略是「丢弃」（AG-UI 协议无进度通道，注释明说可用自定义
 * AgentEventConverter 覆盖）。starter 按 ObjectProvider 收集本组件并在内置转换器之后
 * 注册——同事件类型的自定义实现覆盖默认丢弃，进度帧得以出网。</p>
 *
 * <p><b>口径</b>：进度不占用 AG-UI 的 TOOL_CALL_CHUNK（那是参数增量通道，RunRecorder
 * 会把它并进 argsText），走仓库既有 CUSTOM 帧惯例（与 cost_meta/plan/todo 同路），
 * name=tool_progress。只转 TextBlock 进度；非文本块（图表/数据块）静默跳过——进度行
 * 是一行小字，装不下富内容。</p>
 *
 * <p><b>重放口径（三审席2 注记修正）</b>：DB 历史回放不重放 CUSTOM 帧（RunRecorder
 * 落库时全忽略），但断连「续看」走 hub 保留窗全量重放 CUSTOM——刷新后可能重挂一帧
 * 过期进度小字，前端 run 状态守卫会随下一帧覆盖，无害但非「零重放」。</p>
 */
@Component
public class AssistantToolProgressConverter implements AgentEventConverter {

    /** 前端分支键（useAssistant CUSTOM 分发同名字符串匹配） */
    public static final String FRAME_NAME = "tool_progress";

    @Override
    public Set<Class<? extends io.agentscope.core.event.AgentEvent>> eventTypes() {
        return Set.of(ToolProgressEvent.class);
    }

    @Override
    public void convert(io.agentscope.core.event.AgentEvent event, AguiStreamContext context) {
        if (!(event instanceof ToolProgressEvent progress)) {
            return;
        }
        String text = plainText(progress.getContent());
        if (text == null || text.isBlank()) {
            return;
        }
        Map<String, Object> value = Map.of(
            "toolCallId", nullToEmpty(progress.getToolCallId()),
            "toolName", nullToEmpty(progress.getToolCallName()),
            "text", text);
        context.emit(new AguiEvent.Custom(
            context.getThreadId(), context.getRunId(), FRAME_NAME, value));
    }

    /** ContentBlock → 一行文本；非 TextBlock 返回 null（进度行只承载文字） */
    private static String plainText(ContentBlock block) {
        if (block instanceof TextBlock text) {
            return text.getText();
        }
        return null;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
