package org.dromara.test.assistant;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import org.dromara.assistant.core.AssistantThinkingTierHolder;
import org.dromara.assistant.core.ThinkingTierModelDecorator;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AU-03 思考档后端接线单测（纯逻辑，不起 Spring）。
 *
 * <p>链路口径（拍板 Q3=C）：前端 forwardedProps.thinkTier(fast/balanced/deep) →
 * Holder 会话级暂存 → 装饰器 stream 时注入 reasoning_effort（fast=low / deep=high /
 * balanced=不追加）。档位优先于配置行默认（mergeOptions 首参非空字段胜）。</p>
 */
@Tag("p0probe")
class Au03ThinkingTierTest {

    /** 捕获装饰后 options 的桩模型 */
    private static class CapturingModel implements Model {
        final AtomicReference<GenerateOptions> captured = new AtomicReference<>();

        @Override
        public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
            captured.set(options);
            return Flux.empty();
        }

        @Override
        public String getModelName() {
            return "stub-model";
        }

        @Override
        public int getContextWindowSize() {
            return 128000;
        }
    }

    @Test
    void holderNormalizeBadValuesFallToBalanced() {
        assertEquals("fast", AssistantThinkingTierHolder.normalize("FAST"));
        assertEquals("deep", AssistantThinkingTierHolder.normalize(" deep "));
        assertEquals("balanced", AssistantThinkingTierHolder.normalize("medium"));
        assertEquals("balanced", AssistantThinkingTierHolder.normalize(null));
        assertEquals("balanced", AssistantThinkingTierHolder.normalize(42));
    }

    @Test
    void fastTierInjectsLowEffort() {
        CapturingModel stub = new CapturingModel();
        ThinkingTierModelDecorator decorator = new ThinkingTierModelDecorator(stub, "th-au03");
        AssistantThinkingTierHolder.set("th-au03", "fast");
        decorator.stream(List.of(), List.of(), GenerateOptions.builder().temperature(0.3).build());
        assertEquals("low", stub.captured.get().getReasoningEffort());
        // 档位只动 effort——原 options 其余字段必须原样保留
        assertEquals(0.3, stub.captured.get().getTemperature());
    }

    @Test
    void deepTierInjectsHighEffort() {
        CapturingModel stub = new CapturingModel();
        // 独立 threadId：Holder 是进程级静态表，别染上其它用例的档位残留
        new ThinkingTierModelDecorator(stub, "th-au03-deep").stream(List.of(), List.of(), null);
        assertNull(stub.captured.get(), "默认档(未 set 过)不追加参数=原样透传 null");
        AssistantThinkingTierHolder.set("th-au03-deep", "deep");
        new ThinkingTierModelDecorator(stub, "th-au03-deep").stream(List.of(), List.of(), null);
        assertEquals("high", stub.captured.get().getReasoningEffort());
    }

    @Test
    void balancedTierPassesThroughUntouched() {
        CapturingModel stub = new CapturingModel();
        AssistantThinkingTierHolder.set("th-au03", "balanced");
        GenerateOptions original = GenerateOptions.builder().maxTokens(2048).build();
        new ThinkingTierModelDecorator(stub, "th-au03").stream(List.of(), List.of(), original);
        assertNull(stub.captured.get().getReasoningEffort(), "balanced 不注入 effort");
        assertEquals(2048, stub.captured.get().getMaxTokens());
    }

    @Test
    void nullThreadIdAlwaysBalanced() {
        CapturingModel stub = new CapturingModel();
        AssistantThinkingTierHolder.set("other-thread", "deep");
        new ThinkingTierModelDecorator(stub, null).stream(List.of(), List.of(), null);
        assertNull(stub.captured.get(), "无 threadId(单测/内部调用路径)不注入");
    }

    @Test
    void tierOverridesExistingEffort() {
        CapturingModel stub = new CapturingModel();
        AssistantThinkingTierHolder.set("th-au03", "deep");
        new ThinkingTierModelDecorator(stub, "th-au03").stream(List.of(), List.of(),
            GenerateOptions.builder().reasoningEffort("low").build());
        assertEquals("high", stub.captured.get().getReasoningEffort(),
            "用户当次切档胜过既有 effort(mergeOptions 首参优先)");
    }
}
