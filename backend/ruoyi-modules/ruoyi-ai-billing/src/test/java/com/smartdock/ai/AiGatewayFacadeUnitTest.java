package com.smartdock.ai;

import org.dromara.common.core.exception.ServiceException;
import com.smartdock.ai.AiGateway.AiChatCommand;
import com.smartdock.ai.AiGateway.AiChatResult;
import com.smartdock.ai.AiGateway.Caller;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.mapper.AssistantBackendDefaultMapper;
import com.smartdock.service.IDcsAiConfigService;
import com.smartdock.service.support.DcsAiChatClientFactory;
import com.smartdock.service.support.DcsAiChatClientFactory.CallOverrides;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * AI 服务门面单测(2026-09-24 拍板 Q3:B, 批B; J 批一期 2026-10-08 改版):
 * ① chat 委托契约(HTTP 路径: 配置走 service, 字段逐字传工厂, 回执映射元数据);
 * ② chatForTenant 委托单点解析器——无配置时 ServiceException 转译 AiNotConfiguredException
 *   (MQ 路径, AiScoreEnhancer 按该类型降级);
 * ③ embed 走 EMBEDDING 配置并携带 caller(计费归属行为锁)。
 *
 * <p>J 批一期起 MQ 直查 mapper 已删, 两路径统一走单点解析器
 * (查询形状锁随迁至 DcsAiConfigQueryShapeUnitTest, 本类不再锁形状)。
 * 批1015c Q1 起 CHAT 两路径改走 resolveBackendConfig（后台默认槽→单点解析器，
 * 槽逻辑在 service 实测, 本类只锁门面委托契约）；EMBEDDING 仍 currentTenantEnabledConfig。</p>
 *
 * @author smartdock
 */
@ExtendWith(MockitoExtension.class)
class AiGatewayFacadeUnitTest {

    @Mock
    private IDcsAiConfigService aiConfigService;
    @Mock
    private DcsAiChatClientFactory factory;
    @Mock
    private AssistantBackendDefaultMapper backendDefaultMapper;
    @Mock
    private ObjectProvider<AiCapabilityPort> capabilityPort;

    private AiGatewayImpl gateway() {
        return new AiGatewayImpl(aiConfigService, factory, backendDefaultMapper, capabilityPort);
    }

    private DcsAiConfig config(String tenantId, String provider, String model) {
        DcsAiConfig c = new DcsAiConfig();
        c.setConfigId(1L);
        c.setTenantId(tenantId);
        c.setProvider(provider);
        c.setModelName(model);
        c.setUseType("CHAT");
        c.setEnabled("1");
        return c;
    }

    @Test
    @Tag("dev")
    void chatDelegatesVerbatimAndMapsResultMeta() {
        DcsAiConfig cfg = config("000000", "deepseek", "deepseek-chat");
        when(aiConfigService.resolveBackendConfig()).thenReturn(cfg);
        when(factory.call(cfg, "写一首诗", true, "SCORE_RULE_RECOMMEND", null)).thenReturn("{\"poem\":\"...\"}");

        AiChatResult result = gateway().chat(new AiChatCommand("写一首诗", true, Caller.SCORE_RULE_RECOMMEND));

        assertThat(result.content()).isEqualTo("{\"poem\":\"...\"}");
        assertThat(result.provider()).isEqualTo("deepseek");
        assertThat(result.modelName()).isEqualTo("deepseek-chat");
        assertThat(result.tenantId()).isEqualTo("000000");
        assertThat(result.elapsedMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @Tag("dev")
    void chatPropagatesNotConfiguredFromService() {
        // HTTP 路径原语义: 配置服务直接抛, 门面不吞不改
        when(aiConfigService.resolveBackendConfig())
            .thenThrow(new ServiceException("租户 000000 未启用该用途(CHAT)的 AI 模型配置"));

        assertThatThrownBy(() -> gateway().chat(new AiChatCommand("p", false, Caller.PROFILE_DIAGNOSE)))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("未启用该用途(CHAT)");
    }

    @Test
    @Tag("dev")
    void chatForTenantThrowsAiNotConfiguredWhenAbsent() {
        // MQ 路径: 单点解析器抛 ServiceException → 门面转译 AiNotConfiguredException(调用方据此降级)。
        // glm53 七审 P1-2 后消息透传解析器人话(含租户与用途), 运行期故障不再误入此分支
        when(aiConfigService.resolveBackendConfig("900001"))
            .thenThrow(new ServiceException("租户 900001 未启用该用途(CHAT)的 AI 模型配置"));

        assertThatThrownBy(() -> gateway().chatForTenant("900001", new AiChatCommand("p", true, Caller.SCORE_ENHANCE)))
            .isInstanceOf(AiNotConfiguredException.class)
            .hasMessageContaining("900001")
            .hasMessageContaining("未启用该用途(CHAT)");
    }

    @Test
    @Tag("dev")
    void chatForTenantResolvesViaSinglePointResolverAndDelegates() {
        DcsAiConfig cfg = config("900001", "openai", "gpt-4o-mini");
        when(aiConfigService.resolveBackendConfig("900001")).thenReturn(cfg);
        when(factory.call(cfg, "算分", true, "SCORE_ENHANCE", null)).thenReturn("ok");

        AiChatResult result = gateway().chatForTenant("900001", new AiChatCommand("算分", true, Caller.SCORE_ENHANCE));

        assertThat(result.content()).isEqualTo("ok");
        assertThat(result.modelName()).isEqualTo("gpt-4o-mini");
        assertThat(result.tenantId()).isEqualTo("900001");
    }

    @Test
    @Tag("dev")
    void slotParamsInjectWhenConfigMatchesSlot() {
        // Q6-A: 槽指向==解析到的模型 → tier 经端口翻译成 reasoning_effort, maxTokens 直传
        DcsAiConfig cfg = config("000000", "deepseek", "deepseek-v4-pro");
        cfg.setConfigId(42L);
        when(aiConfigService.resolveBackendConfig()).thenReturn(cfg);
        when(backendDefaultMapper.findBackendCallParams("000000"))
            .thenReturn(new AssistantBackendDefaultMapper.BackendCallParams(42L, "FAST", 2048));
        AiCapabilityPort port = (t, p, m, tier) -> "FAST".equals(tier) ? "low" : null;
        when(capabilityPort.getIfAvailable()).thenReturn(port);
        when(factory.call(org.mockito.ArgumentMatchers.eq(cfg), org.mockito.ArgumentMatchers.eq("诊断"),
            org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq("PROFILE_DIAGNOSE"),
            org.mockito.ArgumentMatchers.eq(new CallOverrides(2048, "low")))).thenReturn("诊断结果");

        AiChatResult result = gateway().chat(new AiChatCommand("诊断", false, Caller.PROFILE_DIAGNOSE));

        assertThat(result.content()).isEqualTo("诊断结果");
    }

    @Test
    @Tag("dev")
    void slotParamsIgnoredWhenConfigFallsBackToStar() {
        // Q6-A: 槽指向≠实际解析到的模型(槽失效回退★) → 不吃旧槽参数, 覆盖=null=存量行为
        DcsAiConfig cfg = config("000000", "openai", "gpt-4o-mini");
        cfg.setConfigId(7L);
        when(aiConfigService.resolveBackendConfig("000000")).thenReturn(cfg);
        when(backendDefaultMapper.findBackendCallParams("000000"))
            .thenReturn(new AssistantBackendDefaultMapper.BackendCallParams(42L, "DEEP", 4096));
        when(factory.call(cfg, "p", true, "SCORE_ENHANCE", null)).thenReturn("ok");

        AiChatResult result = gateway().chatForTenant("000000", new AiChatCommand("p", true, Caller.SCORE_ENHANCE));

        assertThat(result.content()).isEqualTo("ok");
    }

    @Test
    @Tag("dev")
    void embedUsesEmbeddingConfigAndCarriesCaller() {
        // caller 行为锁: 门面强制携带 PROFILE_EMBEDDING(与旧三参重载的默认值一致, 计费口径不变)
        DcsAiConfig cfg = config("000000", "openai", "text-embedding-3-small");
        when(aiConfigService.currentTenantEnabledConfig("EMBEDDING")).thenReturn(cfg);
        float[] vector = new float[]{0.1f, 0.2f};
        when(factory.callEmbedding(cfg, "画像文本", 1024, "PROFILE_EMBEDDING")).thenReturn(vector);

        float[] out = gateway().embed("画像文本", 1024, Caller.PROFILE_EMBEDDING);

        assertThat(out).isSameAs(vector);
    }
}
