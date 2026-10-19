package org.dromara.assistant.core;

import org.dromara.assistant.service.CapabilityService.CapabilityVo;

/**
 * 语义思考档 → 原生思考参数（批2，2026-10-09，Q13-A 双层档位）。
 *
 * <p>语义档即 {@link AssistantThinkingTierHolder} 的 fast/balanced/deep（用户唯一词汇，
 * 跨模型不变）；本件按能力目录把语义档翻译成当前模型的原生参数：
 * TIERS → reasoning_effort（DS low/high/max、GLM low/high/max）；SWITCH → thinking.type
 * （LongCat enabled/disabled、M3 adaptive/disabled）；NONE → 永不发参数。</p>
 *
 * <p><b>BALANCED 恒不注入</b>（交服务端默认档）——与既有口径「balanced=不追加参数」一致；
 * GLM5.3 红线：其 semantic_map 只含 low/high/max，FAST 落 low，结构上不可能产出 disabled
 * （官方传 disabled 直接 400）。语义映射缺键时保守【不注入】（交服务端默认档；
批8-3 回修：注释原写「取中位档/首档」与实现不符——实现即不注入，更保守，以实现为准）。</p>
 */
public final class SemanticThinkingParams {

    private SemanticThinkingParams() {
    }

    /** 单次请求的思考注入物：两通道至多各一，null=不注入 */
    public record Params(String reasoningEffort, String thinkingType) {
        public boolean isEmpty() {
            return reasoningEffort == null && thinkingType == null;
        }
    }

    /** 按能力目录翻译语义档（capability 为 null 按 NONE 处理=永不注入） */
    public static Params build(CapabilityVo capability, String tier) {
        if (capability == null) {
            return new Params(null, null);
        }
        String key = semanticKey(tier);
        // BALANCED = 服务端默认档：不注入（含能力 NONE 与兜底行——兜底 FAST/DEEP 才发开关）
        if ("BALANCED".equals(key)) {
            return new Params(null, null);
        }
        String nativeValue = capability.getSemanticMap().get(key);
        if (nativeValue == null) {
            return new Params(null, null);
        }
        return switch (capability.getTierKind()) {
            // 可调档：走 reasoning_effort（OpenAI 兼容面，DS/GLM 官方枚举）
            case "TIERS" -> new Params(nativeValue, null);
            // 仅开关：走请求体 thinking.type（LongCat enabled/disabled、M3 adaptive/disabled）
            case "SWITCH" -> new Params(null, nativeValue);
            // 不支持思考：任何语义档都不发参数
            default -> new Params(null, null);
        };
    }

    /** tier 值归一（holder fast/balanced/deep → 语义键；未知值按 BALANCED 保守） */
    public static String semanticKey(String tier) {
        if (AssistantThinkingTierHolder.TIER_FAST.equals(tier)) {
            return "FAST";
        }
        if (AssistantThinkingTierHolder.TIER_DEEP.equals(tier)) {
            return "DEEP";
        }
        return "BALANCED";
    }
}
