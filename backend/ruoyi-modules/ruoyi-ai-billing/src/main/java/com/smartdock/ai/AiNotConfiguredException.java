package com.smartdock.ai;

import java.io.Serial;

/**
 * 指定租户未启用 AI 配置（{@link AiGateway#chatForTenant} 显式租户路径专用）。
 *
 * <p>与 HTTP 路径语义区分：HTTP 路径沿袭 IDcsAiConfigService.currentTenantEnabledConfig
 * 直接抛通用 ServiceException；MQ 路径历史上是"查无配置→静默降级"，本异常让调用方
 * （如 AiScoreEnhancer）能精确捕获降级并保留原有日志文案。</p>
 *
 * <p>注: 不继承 ServiceException——框架该类为 final(2026-09-24 实证), 本异常仅由
 * 调用方 catch 使用, 不经全局异常处理器对外。</p>
 *
 * @author smartdock
 */
public class AiNotConfiguredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AiNotConfiguredException(String tenantId) {
        super("租户 " + tenantId + " 未启用 AI 配置(CHAT), 请先在「AI 配置」中启用一个模型");
    }

    /** J 批一期: 单点解析器的人话报错透传（助手/MQ 路把 ServiceException 转译为本类型用） */
    public static AiNotConfiguredException of(String message) {
        return new AiNotConfiguredException(message, true);
    }

    private AiNotConfiguredException(String message, boolean rawMessage) {
        super(message);
    }
}
