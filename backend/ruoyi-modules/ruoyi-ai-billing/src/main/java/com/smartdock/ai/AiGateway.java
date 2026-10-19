package com.smartdock.ai;

/**
 * AI 服务门面（2026-09-24 拍板 Q3:B，二期 AiGateway 一期落地）。
 *
 * <p>dcs 等消费模块<b>只认本窗口</b>，不直接注入 DcsAiChatClientFactory/IDcsAiConfigService 等
 * ai-billing 内部零件——车间内部（模型组装/重试/计费/扣钱包）随便改，消费方零感知。
 * 当前客户 dcs 一家；第二个消费模块出现时按真实需求扩展窗口形状。</p>
 *
 * <p><b>线程语义</b>：{@link #chat} / {@link #embed} 走登录上下文取当前租户配置（HTTP 线程）；
 * {@link #chatForTenant} 显式传租户号，供 MQ 等无 Sa-Token 上下文线程使用。</p>
 *
 * <p><b>计费归属</b>：caller 统一取 {@link Caller} 枚举，所有调用强制携带（枚举化杜绝将来
 * 字符串手误或漏传；与旧实现的 caller 串逐字一致，计费口径不变）；计费 serviceCode 映射维持既有口径。</p>
 *
 * @author smartdock
 */
public interface AiGateway {

    /**
     * 当前租户对话调用（HTTP 线程）。配置解析走 resolveBackendConfig（批1015c Q1）：
     * 后台默认槽优先，槽未设/失效回退接口默认（★→最近可用）。
     *
     * @throws org.dromara.common.core.exception.ServiceException 当前租户未启用 CHAT 配置时
     *         （沿袭 IDcsAiConfigService.resolveEnabledConfig 原语义, 直接传播）
     */
    AiChatResult chat(AiChatCommand command);

    /**
     * 指定租户对话调用（MQ 等无登录上下文线程, TenantHelper.ignore 直查配置表）。
     *
     * @throws AiNotConfiguredException 该租户未启用 CHAT 配置时（调用方自行决定降级/上抛）
     */
    AiChatResult chatForTenant(String tenantId, AiChatCommand command);

    /**
     * 当前租户向量嵌入调用（HTTP 线程, EMBEDDING 配置）。
     *
     * @throws org.dromara.common.core.exception.ServiceException 当前租户未启用 EMBEDDING 配置时
     */
    float[] embed(String text, Integer dimensions, Caller caller);

    /** AI 调用方身份（计费归属/用量台账 caller 列, 枚举化杜绝字符串手误与漏传） */
    enum Caller {
        /** 画像打分增强(AiScoreEnhancer) */
        SCORE_ENHANCE,
        /** 评分规则 AI 推荐(ScoreRuleRecommender) */
        SCORE_RULE_RECOMMEND,
        /** 画像 AI 诊断(ProfileAiDiagnoseService) */
        PROFILE_DIAGNOSE,
        /** 画像向量嵌入(ProfileEmbeddingService) */
        PROFILE_EMBEDDING,
        /** 表单 AI 字段生成(DcsAiFieldServiceImpl) */
        FIELD_GENERATE
    }

    /**
     * 对话调用指令。
     *
     * @param prompt    提示词
     * @param forceJson 是否强制 JSON 输出（reasoner 模型的跳过逻辑在工厂内部处理）
     * @param caller    调用方身份（计费归属）
     */
    record AiChatCommand(String prompt, boolean forceJson, Caller caller) {
    }

    /**
     * 对话调用回执（调用方按需取用：诊断落库要 tenantId/modelName，打分结果要 modelName）。
     *
     * @param content   模型回复原文
     * @param provider  供应商（如 openai/deepseek）
     * @param modelName 模型名（落库/展示用）
     * @param tenantId  配置所属租户
     * @param elapsedMs 门面内部计时（替代调用方自设计时样板）
     */
    record AiChatResult(String content, String provider, String modelName, String tenantId, long elapsedMs) {
    }
}
