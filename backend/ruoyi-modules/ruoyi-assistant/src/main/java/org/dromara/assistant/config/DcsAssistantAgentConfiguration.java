package org.dromara.assistant.config;

import io.agentscope.spring.boot.agui.common.AguiAgentRegistryCustomizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.core.AssistantAgentFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DCS 工作助手装配（批A Task A2 改版，2026-10-03）。
 *
 * <p>注册形态：@Bean AguiAgentRegistryCustomizer + registerFactory("dcs-assistant",
 * factory::createFromLogin)——官方扩展点，starter 经 ObjectProvider 收集所有 Customizer
 * 在 registry 就绪后回调。惰性工厂取代启动期单例注册（T12 时期形态）：</p>
 * <ul>
 *   <li>agent 在首个请求线程按登录人租户创建（dcs_ai_config 按租户解析，多租户各配各的）；</li>
 *   <li>每次新会话（userId+threadId）新建实例，配合 AssistantRuntimeContextResolver 的
 *       租户作用域 userId 实现会话隔离；</li>
 *   <li>未配模型的租户在首次请求时收到明确报错（AiNotConfiguredException），
 *       不再是启动期静默跳过。</li>
 * </ul>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DcsAssistantAgentConfiguration {

    private final AssistantAgentFactory agentFactory;

    @Bean
    public AguiAgentRegistryCustomizer dcsAssistantAgentRegistryCustomizer() {
        return registry -> {
            registry.registerFactory(AssistantAgentFactory.AGENT_ID, agentFactory::createFromLogin);
            log.info("[dcs-assistant] agent 工厂已注册（按登录租户惰性创建），端点 POST /agui/run/{}",
                AssistantAgentFactory.AGENT_ID);
        };
    }
}
