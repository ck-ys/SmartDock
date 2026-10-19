package com.smartdock.ai;

/**
 * 模型能力目录端口（业务反馈 #14 追加 Q6-A，2026-10-16）。
 *
 * <p>能力目录与语义档翻译（CapabilityService + SemanticThinkingParams）住在
 * ruoyi-assistant 模块；ai-billing 的 {@link AiGatewayImpl} 在 pom 依赖方向上不能
 * 反向 import assistant（assistant → dcs + ai-billing 单向）。本接口由 billing 定义、
 * assistant 实现（AiCapabilityPortImpl，同一 Spring 上下文运行期装配）——经典六边形
 * 端口，零逻辑复制。</p>
 *
 * <p>billing 单测上下文无 assistant 模块时实现缺失，网关侧用 ObjectProvider 注入并
 * 把"无实现"降级为"不注入"（参数缺省=现状行为，不炸）。</p>
 */
public interface AiCapabilityPort {

    /**
     * 语义思考档 → 当前模型的 reasoning_effort 原生值。
     *
     * @param tenantId     租户（能力目录有租户 MANUAL 覆盖行）
     * @param provider     厂商（dcs_ai_config.provider）
     * @param modelName    模型名
     * @param semanticTier 语义键 FAST/BALANCED/DEEP（存储原值）
     * @return 原生 reasoning_effort 值；null=该档无注入参数（BALANCED 档/能力 NONE/
     *         SWITCH 型模型——Spring AI 无 thinking.type 通道）。注意消费方约定（见
     *         AiGatewayImpl.resolveSlotOverrides）：槽已选档时 null 被翻译成空串=
     *         显式清空 dcs_ai_config 行的 reasoning_effort（槽语义优先，画像链思考
     *         参数完全由槽档决定，不回退行配置）
     */
    String resolveBackendReasoningEffort(String tenantId, String provider, String modelName, String semanticTier);
}
