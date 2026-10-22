package org.dromara.assistant.core;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 摘要专用模型包装单测（批A-2，2026-10-22 拍板 Q9-A 配套）：
 * 摘要调用必须封顶 2 万 token 且不带工具；其余选项透传。
 */
@Tag("dev")
class CompactionSummaryModelTest {

    /** 捕获实际下发给底层模型的入参 */
    private static final class CapturingModel implements Model {
        List<ToolSchema> tools;
        GenerateOptions options;
        String modelName = "fake";

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            this.tools = tools;
            this.options = options;
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return modelName;
        }

        @Override
        public int getContextWindowSize() {
            return 128_000;
        }
    }

    @Test
    void forcesSummaryCapAndDropsTools() {
        CapturingModel delegate = new CapturingModel();
        // 二审回修：必须带工具进、断言空出——传 null 进断 null 是空转（框架只可能带工具调摘要）
        new CompactionSummaryModel(delegate).stream(List.of(), List.of(), null);

        assertThat(delegate.tools).isNull();
        assertThat(delegate.options).isNotNull();
        assertThat(delegate.options.getMaxTokens()).isEqualTo(CompactionSummaryModel.SUMMARY_MAX_TOKENS);
    }

    @Test
    void keepsOtherOptionsButOverridesMaxTokens() {
        CapturingModel delegate = new CapturingModel();
        GenerateOptions incoming = GenerateOptions.builder().maxTokens(64_000).temperature(0.3).build();
        new CompactionSummaryModel(delegate).stream(List.of(), null, incoming);

        // 封顶值赢过调用方传入的大额度
        assertThat(delegate.options.getMaxTokens()).isEqualTo(20_000);
        // 其余选项透传
        assertThat(delegate.options.getTemperature()).isEqualTo(0.3);
    }

    @Test
    void delegatesIdentity() {
        CapturingModel delegate = new CapturingModel();
        delegate.modelName = "deepseek-chat";
        CompactionSummaryModel wrapped = new CompactionSummaryModel(delegate);

        assertThat(wrapped.getModelName()).isEqualTo("deepseek-chat");
        assertThat(wrapped.getContextWindowSize()).isEqualTo(128_000);
    }
}
