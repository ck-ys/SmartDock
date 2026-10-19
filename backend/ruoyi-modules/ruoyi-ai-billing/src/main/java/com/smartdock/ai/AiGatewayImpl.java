package com.smartdock.ai;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.mapper.AssistantBackendDefaultMapper;
import com.smartdock.service.IDcsAiConfigService;
import com.smartdock.service.support.DcsAiChatClientFactory;
import com.smartdock.service.support.DcsAiChatClientFactory.CallOverrides;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * AI 服务门面实现（2026-09-24 拍板 Q3:B；J 批一期 2026-10-08 两路解析统一走单点解析器）。
 *
 * <p>唯一允许注入 {@link DcsAiChatClientFactory} 的业务入口（车间内部化）；
 * 计费/重试/扣钱包链路全部留在工厂内不动，门面只做：配置解析 + 委托 + 元数据回执。</p>
 *
 * <p>配置解析（业务反馈批1015c Q1 拍板A）：后台链（本门面全部调用方=画像诊断/字段生成/
 * 评分/规则推荐）走 {@link IDcsAiConfigService#resolveBackendConfig}（后台默认槽
 * →接口默认★→最近可用，槽失效静默回退，存量租户零迁移兼容）。
 * HTTP 路透传 ServiceException（原语义，调用方按该类型降级）；MQ 显式租户路转译
 * {@link AiNotConfiguredException}（AiScoreEnhancer 捕获降级）。</p>
 *
 * <p>业务反馈 #14 追加 Q6-A（2026-10-16）：后台槽调用参数（思考档/最大输出）在配置解析
 * 后从 dcs_assistant_model_setting 槽行读取（跨模块只读 mapper），思考档经
 * {@link AiCapabilityPort}（assistant 模块实现）翻译成 reasoning_effort 后与最大输出
 * 一并作为 {@link CallOverrides} 传入工厂——槽参数只在实际解析到的模型 == 槽指向时
 * 生效（槽失效回退 ★ 的行不吃旧槽参数）。槽参数缺省（空槽/无行/无端口实现）时行为
 * 与改造前完全一致（沿用 dcs_ai_config 行的 max_tokens/reasoning_effort）。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiGatewayImpl implements AiGateway {

    private static final String USE_TYPE_EMBEDDING = "EMBEDDING";

    /** AI 配置服务(单点解析器入口) */
    private final IDcsAiConfigService aiConfigService;
    /** AI 客户端工厂(模型调用/重试/计费/扣钱包, 仅本类注入) */
    private final DcsAiChatClientFactory factory;
    /** 后台槽参数只读(dcs_assistant_model_setting, 跨模块直查) */
    private final AssistantBackendDefaultMapper backendDefaultMapper;
    /** 模型能力端口(assistant 模块实现; 缺实现=不注入思考参数, billing 单测上下文防线) */
    private final ObjectProvider<AiCapabilityPort> capabilityPort;

    @Override
    public AiChatResult chat(AiChatCommand command) {
        // 无配置时 service 抛 ServiceException(原语义直接传播, 各调用方行为不变)
        DcsAiConfig config = aiConfigService.resolveBackendConfig();
        return doChat(config, command);
    }

    @Override
    public AiChatResult chatForTenant(String tenantId, AiChatCommand command) {
        // J 批一期(2026-10-08)：统一走单点解析器（含 CNY 闸——MQ 路同闸拒美元模型）；
        // AiScoreEnhancer 按本异常类型降级，ServiceException 转译保留捕获语义。
        // glm53 七审 P1-2：catch 只包解析一行——doChat 运行期故障（余额不足/超时/空返回
        // 也抛 ServiceException）不得误标"配置不可用"，否则真实根因从降级日志里消失
        final DcsAiConfig config;
        try {
            config = aiConfigService.resolveBackendConfig(tenantId);
        } catch (org.dromara.common.core.exception.ServiceException e) {
            throw AiNotConfiguredException.of(e.getMessage());
        }
        return doChat(config, command);
    }

    @Override
    public float[] embed(String text, Integer dimensions, Caller caller) {
        DcsAiConfig config = aiConfigService.currentTenantEnabledConfig(USE_TYPE_EMBEDDING);
        return factory.callEmbedding(config, text, dimensions, caller.name());
    }

    /** 委托工厂调用并组装元数据回执(门面内部计时, 调用方免样板; Q6-A 槽参数在此注入)。 */
    private AiChatResult doChat(DcsAiConfig config, AiChatCommand command) {
        long start = System.currentTimeMillis();
        String content = factory.call(config, command.prompt(), command.forceJson(),
            command.caller().name(), resolveSlotOverrides(config));
        return new AiChatResult(content, config.getProvider(), config.getModelName(),
            config.getTenantId(), System.currentTimeMillis() - start);
    }

    /**
     * 后台槽参数 → 工厂覆盖(Q6-A)。仅当"解析到的模型就是槽指向"时应用:
     * 槽失效回退(★→最近可用)的行不吃旧槽参数, 防换模型后旧档位错配。
     * 任一子步骤异常都降级为"不覆盖"——参数是锦上添花, 不能反噬调用主链。
     */
    private CallOverrides resolveSlotOverrides(DcsAiConfig config) {
        try {
            AssistantBackendDefaultMapper.BackendCallParams params =
                backendDefaultMapper.findBackendCallParams(config.getTenantId());
            if (params == null
                || params.backendConfigId() == null
                || !params.backendConfigId().equals(config.getConfigId())) {
                return null;
            }
            boolean tierEnabled = StrUtil.isNotBlank(params.thinkingTier());
            boolean maxTokensPresent = params.maxTokens() != null;
            if (!tierEnabled && !maxTokensPresent) {
                return null;
            }
            String reasoningEffort = null;
            if (tierEnabled) {
                AiCapabilityPort port = capabilityPort.getIfAvailable();
                if (port != null) {
                    // null=该档不注入(BALANCED/NONE/SWITCH 无通道)——空串显式清空,
                    // 覆盖 dcs_ai_config 行可能配置的 reasoning_effort(槽语义优先)
                    String resolved = port.resolveBackendReasoningEffort(
                        config.getTenantId(), config.getProvider(), config.getModelName(),
                        params.thinkingTier());
                    reasoningEffort = resolved == null ? "" : resolved;
                }
            }
            return new CallOverrides(params.maxTokens(), reasoningEffort);
        } catch (Exception e) {
            log.warn("[AiGateway] 后台槽参数解析失败按不覆盖处理 tenant={}: {}",
                config.getTenantId(), e.getMessage());
            return null;
        }
    }
}
