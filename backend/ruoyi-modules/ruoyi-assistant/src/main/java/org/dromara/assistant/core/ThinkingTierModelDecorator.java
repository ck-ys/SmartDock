package org.dromara.assistant.core;

import io.agentscope.core.message.Msg;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.service.CapabilityService.CapabilityVo;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * 思考档位模型装饰器（AU-03 → 批2 双层档位，2026-10-09）。
 *
 * <p>档位经 {@link AssistantThinkingTierHolder} 按 threadId 读取（fast/balanced/deep=用户
 * 唯一语义词汇）；{@link SemanticThinkingParams} 按能力目录翻译成当前模型原生参数：
 * TIERS（DS/GLM）→ reasoning_effort=low/high/max；SWITCH（LongCat/M3）→ 请求体
 * thinking.type=enabled·disabled·adaptive；NONE → 永不发。balanced 恒不注入（服务端
 * 默认档）。GLM5.3 红线由映射表结构保证：其 FAST→low，不可能产出 disabled（官方 400）。
 * 能力目录行在 Agent 装配期解析一次注入（每模型一次，非每请求查库）。</p>
 */
@Slf4j
public class ThinkingTierModelDecorator implements Model {

    private final Model delegate;
    private final String threadId;
    /** 当前模型能力（null=未解析，按不注入处理，行为同 balanced） */
    private final CapabilityVo capability;

    public ThinkingTierModelDecorator(Model delegate, String threadId) {
        this(delegate, threadId, null);
    }

    public ThinkingTierModelDecorator(Model delegate, String threadId, CapabilityVo capability) {
        this.delegate = delegate;
        this.threadId = threadId;
        this.capability = capability;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        String tier = AssistantThinkingTierHolder.get(threadId);
        SemanticThinkingParams.Params params = SemanticThinkingParams.build(capability, tier);
        if (params.isEmpty()) {
            return delegate.stream(messages, tools, options);
        }
        GenerateOptions.Builder inject = GenerateOptions.builder();
        if (params.reasoningEffort() != null) {
            inject.reasoningEffort(params.reasoningEffort());
        }
        if (params.thinkingType() != null) {
            inject.additionalBodyParam("thinking", Map.of("type", params.thinkingType()));
        }
        // mergeOptions(a,b)=a 的非空字段优先：档位置前=用户当次选择胜过请求级默认
        GenerateOptions merged = GenerateOptions.mergeOptions(inject.build(), options);
        // 仅需注入的档位才打（balanced/不支持的模型不刷日志）；E2E 靠这行核实参数链路到达
        log.info("[AssistantThinkingTier] thread={} tier={} kind={} native={}",
            threadId, tier, capability == null ? "NONE" : capability.getTierKind(), params);
        return delegate.stream(messages, tools, merged);
    }

    @Override
    public String getModelName() {
        return delegate.getModelName();
    }

    @Override
    public int getContextWindowSize() {
        return delegate.getContextWindowSize();
    }
}
