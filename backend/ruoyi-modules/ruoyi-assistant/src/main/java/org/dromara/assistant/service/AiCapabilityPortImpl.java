package org.dromara.assistant.service;

import lombok.RequiredArgsConstructor;
import org.dromara.assistant.core.AssistantThinkingTierHolder;
import org.dromara.assistant.core.SemanticThinkingParams;
import com.smartdock.ai.AiCapabilityPort;
import org.springframework.stereotype.Service;

/**
 * {@link AiCapabilityPort} 实现（业务反馈 #14 追加 Q6-A，2026-10-16）：
 * 复用助手侧能力目录 + 语义档翻译，为 ai-billing 后台链提供 reasoning_effort 解析。
 *
 * <p><b>SWITCH 模型诚实边界</b>：SemanticThinkingParams 对仅开关模型产出
 * thinking.type 通道（additionalBodyParam），而后台链是 Spring AI OpenAiChatOptions
 * （1.1.5 无 extra body/thinking 通道）——注入不进去。故本端口对 SWITCH 一律返回
 * null（不注入=沿用服务端默认），画像设置弹窗已注明"仅开关模型的思考参数暂不生效"。
 * TIERS 走 reasoning_effort 全支持；NONE 永不注入。</p>
 */
@Service
@RequiredArgsConstructor
public class AiCapabilityPortImpl implements AiCapabilityPort {

    private final CapabilityService capabilityService;

    @Override
    public String resolveBackendReasoningEffort(String tenantId, String provider, String modelName,
                                                String semanticTier) {
        if (semanticTier == null || semanticTier.isBlank()) {
            return null;
        }
        CapabilityService.CapabilityVo cap = capabilityService.matchCapability(tenantId, provider, modelName);
        // 存储是语义键大写(FAST/BALANCED/DEEP)，SemanticThinkingParams 归一入口收小写词汇
        String tier = switch (semanticTier.toUpperCase()) {
            case "FAST" -> AssistantThinkingTierHolder.TIER_FAST;
            case "DEEP" -> AssistantThinkingTierHolder.TIER_DEEP;
            default -> AssistantThinkingTierHolder.TIER_BALANCED;
        };
        SemanticThinkingParams.Params params = SemanticThinkingParams.build(cap, tier);
        // Params.thinkingType=SWITCH 通道, 后台链无通道注入不了——只回 reasoningEffort
        return params.reasoningEffort();
    }
}
