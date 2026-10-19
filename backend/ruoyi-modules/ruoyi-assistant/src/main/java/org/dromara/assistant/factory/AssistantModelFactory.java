package org.dromara.assistant.factory;

import cn.hutool.crypto.digest.DigestUtil;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.transport.HttpTransport;
import io.agentscope.core.model.transport.HttpTransportFactory;
import io.agentscope.core.model.transport.OkHttpTransport;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.smartdock.ai.AiNotConfiguredException;
import com.smartdock.domain.DcsAiConfig;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 助手模型工厂（批A Task A1，P1 计划 2026-10-03）。
 *
 * <p>职责：按租户解析 dcs_ai_config 启用行并构建 AgentScope OpenAI 兼容流式模型。
 * 配置行四个参数逐项透传（前置#11 兜底件）：temperature/maxTokens 走 {@link GenerateOptions}，
 * contextWindow 走 {@code contextWindowSize}（越界保护用），apiKey 经 MyBatis 加密插件查出即解密。</p>
 *
 * <p>口径纪律（J 批一期 2026-10-08；批3 Q11 扩链）：配置解析委托
 * {@code IDcsAiConfigService} 单点族（resolveConfigById / resolveEnabledConfig，CNY 闸同源），
 * 本类不再自带查询——此前 configId 升序与单发路 updateTime 倒序各查各的，同租户多条启用时两路选不同行。
 * 批3 起优先级链=助手默认槽→接口默认★→最近可用行（详见 {@link #resolveConfig}）。
 * 无可用行抛 {@link AiNotConfiguredException} 禁静默回退平台侧
 * （多租户隔离红线：租户没开助手就不能用，不能偷偷用平台的配置和钱包）。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantModelFactory {

    /** contextWindow 列缺省时的兜底值（与 DDL 默认一致） */
    private static final int DEFAULT_CONTEXT_WINDOW = 128000;

    /** OPENCODE Go 网关官方要求的自报客户端标识（docs/go：勿用通用 SDK 名） */
    private static final String SELF_UA = "dcs-assistant/1.0";

    /**
     * OPENCODE 底层传输层共享单例（三审 P2-1）：每 (user,thread) 建 agent 各 new 一个 OkHttpTransport
     * 会累积连接池/dispatcher 线程且框架不调 close——与 AgentScope 默认链的共享 JdkHttpTransport
     * 同款取舍，装饰器只加头不独占 client。
     */
    private static final HttpTransport OPENCODE_DELEGATE = new OkHttpTransport();

    /**
     * goal 加固 H2：按 (厂商形态,超时秒) 缓存的共享 transport——timeoutSeconds 终于生效。
     * 缓存而非每次新建：与 OPENCODE_DELEGATE 同款防泄漏取舍（键空间=不同超时值个数，量级个位）；
     * 全部注册进 HttpTransportFactory 关停托管。null 超时=不进本表，走框架默认链（5min 族）零变化。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, HttpTransport> TIMEOUT_TRANSPORTS =
        new java.util.concurrent.ConcurrentHashMap<>();

    static {
        // 登记进框架托管清单: JVM 关闭时 HttpTransportFactory.shutdown() 统一优雅关闭共享 client
        // (dspro 三审 P1 残留)——不 register 则关停时进行中的流硬断
        HttpTransportFactory.register(OPENCODE_DELEGATE);
    }

    /**
     * goal 加固 H2：把 dcs_ai_config.timeout_seconds 映射到 transport 超时族。
     * 语义对齐探测端（整请求超时）：connect 取 min(30,t) 防长超时配置把建连也拖长，
     * read/response/streamIdle=t（流式档=帧间停滞上限，深思考模型推理增量持续到达即不断）。
     * 下限 5s 对齐探测端 Math.max(5,t)，防误配 1s 把正常请求秒断。
     */
    private static HttpTransport transportWithTimeout(String provider, Integer timeoutSeconds) {
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            return null;
        }
        int t = Math.max(5, timeoutSeconds);
        boolean oc = "OPENCODE".equalsIgnoreCase(provider);
        return TIMEOUT_TRANSPORTS.computeIfAbsent((oc ? "ok:" : "jdk:") + t, k -> {
            io.agentscope.core.model.transport.HttpTransportConfig config =
                io.agentscope.core.model.transport.HttpTransportConfig.builder()
                    .connectTimeout(java.time.Duration.ofSeconds(Math.min(30, t)))
                    .readTimeout(java.time.Duration.ofSeconds(t))
                    .responseTimeout(java.time.Duration.ofSeconds(t))
                    .streamIdleTimeout(java.time.Duration.ofSeconds(t))
                    .build();
            HttpTransport raw = oc
                ? OkHttpTransport.builder().config(config).build()
                : io.agentscope.core.model.transport.JdkHttpTransport.builder().config(config).build();
            HttpTransportFactory.register(raw);
            log.info("[ModelFactory] 超时化 transport 就绪 provider 超时={}s", t);
            return raw;
        });
    }

    /** J 批一期：配置解析委托单点解析器（本类不再自带查询+CNY 闸） */
    private final com.smartdock.service.IDcsAiConfigService aiConfigService;
    /** 批3 Q11：助手默认槽（AI 助手管理页设置，优先于接口默认星标） */
    private final org.dromara.assistant.service.AssistantModelSettingService modelSettingService;

    /**
     * 按租户解析生效 CHAT 配置行（批3 Q11 优先级链）：
     * 助手默认槽（dcs_assistant_model_setting 指向行，须仍可用+过 CNY 闸）
     * → 接口默认解析器 {@code resolveEnabledConfig}（★默认行→最近可用行+同源 CNY 闸）。
     *
     * <p>助手默认槽失效（指向行被停用/删除/价格被改非 CNY）=静默回退接口默认并 warn 留痕
     * （Review Focus ④：优先级链空槽不抛错——管理员改配置的瞬间不能打断成员正在发的消息）。
     * 链尾仍无可用行才抛 {@link AiNotConfiguredException}（禁静默回退平台侧，多租户隔离红线）。</p>
     *
     * @param tenantId 租户编号
     * @return 生效配置行
     * @throws AiNotConfiguredException 全链无可用配置或 CNY 闸不过
     */
    public DcsAiConfig resolveConfig(String tenantId) {
        Long settingConfigId = modelSettingService.findAssistantDefaultConfigId(tenantId);
        if (settingConfigId != null) {
            try {
                return aiConfigService.resolveConfigById(tenantId, settingConfigId);
            } catch (org.dromara.common.core.exception.ServiceException e) {
                log.warn("[ModelFactory] 助手默认模型失效, 回退接口默认 tenant={} configId={}: {}",
                    tenantId, settingConfigId, e.getMessage());
            }
        }
        try {
            return aiConfigService.resolveEnabledConfig(tenantId, "CHAT");
        } catch (org.dromara.common.core.exception.ServiceException e) {
            // 助手契约保留 AiNotConfiguredException（AGUI 层按此转人话报错，禁静默回退平台侧）
            throw AiNotConfiguredException.of(e.getMessage());
        }
    }

    /**
     * 批7 Q5/Q12-④ 优先级链第一段：用户会话选择（Composer 模型选择器经
     * forwardedProps.modelConfigId 上行，{@code AssistantModelChoiceHolder} 会话级暂存）。
     *
     * <p>双闸裁决：①allowUserSwitch 关=忽略用户声明（防绕过开关点名任意模型）；
     * ②resolveConfigById 复核租户归属/可用/CHAT/CNY 闸——失效（停用/下架）静默回退默认链
     * 并 warn 留痕（管理员改配置瞬间不打断成员会话，与助手默认槽同款回退语义）。
     * 业务批4 Q3 增第三闸：switch_scope 白名单非空且不含 picked=忽略（API 直调绕过
     * 前端选择器同样拦，前端 model-choice options 与本闸同源同宽）。</p>
     */
    /**
     * 业务反馈批1015b Q2：侧车"轻快模型"——优先取该租户最便宜的启用 CHAT 行（倍率=档位信号：
     * 免费/flash 档天生轻快），任何失败回退默认链。追问是 3 条短 JSON 的小任务，不该跟主对话
     * 抢同一个默认模型（默认模型忙/限流时两败俱伤=追问失败主因）。
     */
    public DcsAiConfig resolveLightweightConfig(String tenantId) {
        try {
            DcsAiConfig cheap = aiConfigService.resolveCheapestEnabledConfig(tenantId, "CHAT");
            if (cheap != null) {
                return cheap;
            }
        } catch (Exception e) {
            log.debug("[ModelFactory] 轻快模型解析失败, 回退默认链 tenant={}: {}", tenantId, e.getMessage());
        }
        return resolveConfig(tenantId);
    }

    public DcsAiConfig resolveConfigWithUserChoice(String tenantId, String threadId) {
        if (threadId != null) {
            Long picked = org.dromara.assistant.core.AssistantModelChoiceHolder.get(threadId);
            if (picked != null) {
                if (!modelSettingService.allowUserSwitch(tenantId)) {
                    log.warn("[ModelFactory] 自选开关已关, 忽略用户声明模型 tenant={} thread={} configId={}",
                        tenantId, threadId, picked);
                } else {
                    java.util.Set<Long> scopeIds = modelSettingService.switchScopeIds(tenantId);
                    if (!scopeIds.isEmpty() && !scopeIds.contains(picked)) {
                        log.warn("[ModelFactory] 用户声明模型不在自选范围(switch_scope), 回退默认链 tenant={} thread={} configId={}",
                            tenantId, threadId, picked);
                    } else {
                        try {
                            return aiConfigService.resolveConfigById(tenantId, picked);
                        } catch (org.dromara.common.core.exception.ServiceException e) {
                            log.warn("[ModelFactory] 用户所选模型失效, 回退默认链 tenant={} thread={} configId={}: {}",
                                tenantId, threadId, picked, e.getMessage());
                        }
                    }
                }
            }
        }
        return resolveConfig(tenantId);
    }

    /**
     * 按配置行构建流式模型（P06 实证配方：stream(true)+DeepSeekFormatter=AGUI 层逐 delta 下发）。
     *
     * @param cfg 启用配置行
     * @return OpenAI 兼容流式模型
     */
    public Model buildModel(DcsAiConfig cfg) {
        return buildModel(cfg, null);
    }

    /**
     * 按配置行构建流式模型（带会话标识）。
     *
     * @param cfg      启用配置行
     * @param threadId 会话线程 ID（OPENCODE Go 网关的 x-opencode-session 用——每会话稳定
     *                 利于服务端路由与 prompt 缓存；其它厂商忽略该参数）
     */
    public Model buildModel(DcsAiConfig cfg, String threadId) {
        GenerateOptions.Builder options = GenerateOptions.builder();
        if (cfg.getTemperature() != null) {
            options.temperature(cfg.getTemperature());
        }
        if (cfg.getMaxTokens() != null) {
            options.maxTokens(cfg.getMaxTokens());
        }
        io.agentscope.extensions.model.openai.OpenAIChatModel.Builder builder = OpenAIChatModel.builder()
            .baseUrl(cfg.getBaseUrl())
            .apiKey(cfg.getApiKey())
            .modelName(cfg.getModelName())
            .stream(true)
            .generateOptions(options.build())
            .contextWindowSize(cfg.getContextWindow() != null ? cfg.getContextWindow() : DEFAULT_CONTEXT_WINDOW)
            .formatter(new DeepSeekFormatter());
        // goal 加固 H2：timeoutSeconds 透传 transport（此前恒走框架默认 5min 族，管理员配置无效）
        HttpTransport timeoutTransport = transportWithTimeout(cfg.getProvider(), cfg.getTimeoutSeconds());
        // OPENCODE Go 网关官方要求自报 UA + x-opencode-session（缺 UA 会被要求补,缺 session 头
        // 报 MissingSessionID 无法路由,2026-10-08 实测）；其它厂商不包装零影响
        if ("OPENCODE".equalsIgnoreCase(cfg.getProvider())) {
            builder.httpTransport(new HeaderDecoratedTransport(
                timeoutTransport != null ? timeoutTransport : OPENCODE_DELEGATE,
                Map.of("User-Agent", SELF_UA, "x-opencode-session", gatewaySessionOf(cfg, threadId))));
        } else if (timeoutTransport != null) {
            builder.httpTransport(timeoutTransport);
        }
        return builder.build();
    }

    /**
     * OPENCODE 会话头值：租户命名空间 + threadId 短哈希。
     *
     * <p>不直拼 threadId（dspro 三审 P2）：threadId 客户端可控且服务端无字符集校验——
     * 中文等非 ASCII 值会被 OkHttp Headers.checkValue 直接拒（该会话必炸且报错不指根因）、
     * 超长值被网关 4xx；哈希后恒为定长 ASCII，且租户前缀防跨租户共享网关会话路由/prompt 缓存。
     * 哈希对同一 thread 恒等——「每会话稳定」的网关路由语义不受影响。</p>
     */
    private static String gatewaySessionOf(DcsAiConfig cfg, String threadId) {
        String raw = threadId == null || threadId.isBlank() ? "sessionless" : threadId;
        return "dcs-" + cfg.getTenantId() + "-" + DigestUtil.sha256Hex(raw).substring(0, 24);
    }
}
