package org.dromara.assistant.core;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 摘要专用模型包装（批A-2，2026-10-22 拍板 Q9-A 配套）：
 * 框架的 ConversationCompactor 以 {@code model.stream(summaryMsg, null, null)} 发起摘要调用
 * （2.0.4 字节码实证），CompactionConfig 没有 maxTokens/工具开关旋钮——这里在模型侧兜住两条：
 *
 * <ol>
 *   <li><b>输出上限 2 万</b>：强制把 maxTokens 盖成 {@link #SUMMARY_MAX_TOKENS}（其余选项透传），
 *       避免摘要调用继承主模型更大的输出额度、按 token 计费烧钱；</li>
 *   <li><b>禁调工具</b>：无论调用方传什么，tools 恒置 null——摘要只需读文本产出文本。</li>
 * </ol>
 *
 * <p>只给压缩中间件的摘要调用使用，不要包在主对话模型外面（会改变主链路的工具与额度）。</p>
 */
public final class CompactionSummaryModel implements Model {

    /** 摘要输出上限（对齐 ZCode 摘要调用 20000） */
    public static final int SUMMARY_MAX_TOKENS = 20_000;

    private final Model delegate;

    public CompactionSummaryModel(Model delegate) {
        this.delegate = delegate;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        // mergeOptions(a,b)：字段取 a 的非空值，a 为空再取 b —— a 放封顶值保证 wins
        GenerateOptions capped = GenerateOptions.mergeOptions(
            GenerateOptions.builder().maxTokens(SUMMARY_MAX_TOKENS).build(), options);
        return delegate.stream(messages, null, capped);
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public boolean supportsNativeStructuredOutput() {
        return delegate.supportsNativeStructuredOutput();
    }

    @Override
    public boolean supportsNativeStructuredOutputWithTools() {
        return delegate.supportsNativeStructuredOutputWithTools();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
