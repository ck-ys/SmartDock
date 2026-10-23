package com.smartdock.service.support;

import cn.hutool.core.util.StrUtil;
import io.micrometer.observation.ObservationRegistry;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.domain.vo.FallbackDecision;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.openai.api.ResponseFormat;
import org.springframework.ai.document.Document;
import org.springframework.http.HttpHeaders;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.http.client.JdkClientHttpRequestFactory;

import java.time.Duration;

/**
 * Spring AI OpenAI-compatible ChatModel 工厂（车间, 2026-09-24 起内部化）。
 *
 * <p><b>唯一业务入口是 {@code com.smartdock.ai.AiGateway}</b>——业务模块(dcs 等)禁止直接
 * 注入本类; 配置解析/调用方身份(caller)归门面, 模型组装/重试/计费/扣钱包归本类。
 * 模块内部协作(如配置连通性探测)不受限。</p>
 *
 * @author smartdock
 */
@Component
@RequiredArgsConstructor
public class DcsAiChatClientFactory {

    /** 巡检探活 caller（AI 缺口批4 Q9）：钱包豁免 + 不落台账 + 读超时上界三重特殊口径 */
    private static final String PATROL_CALLER = "PATROL";

    /** 巡检探活读超时（秒）：RestClient 默认无读超时，半死连接会把 04:00 巡检永久挂住 */
    private static final long PATROL_READ_TIMEOUT_SECONDS = 30L;

    /** Spring Retry 模板(模型调用重试) */
    private final RetryTemplate retryTemplate;
    /** AI 计费服务(记录每次调用消耗) */
    private final AiBillingService billingService;
    /** 租户钱包服务(余额预检与扣费) */
    private final TenantWalletService tenantWalletService;

    /**
     * 调用 AI 模型(强制 JSON 输出, 默认 caller=AI_CALL)。
     */
    public String call(DcsAiConfig config, String promptText) {
        return call(config, promptText, true, "AI_CALL");
    }

    /**
     * 调用 AI 模型(可选强制 JSON, 默认 caller=AI_CALL)。
     */
    public String call(DcsAiConfig config, String promptText, boolean forceJson) {
        return call(config, promptText, forceJson, "AI_CALL");
    }

    /**
     * 调用 AI 模型(可选 JSON 强制 / 自然语言), 并记录计费。
     *
     * @param config      AI 配置(已含明文 apiKey, 由 @EncryptField 解密)
     * @param promptText  完整 prompt
     * @param forceJson   true=强制 JSON 输出(原行为, AI 字段生成用); false=自然语言(画像诊断摘要用)
     * @param caller      调用方标识(PROFILE_DIAGNOSE/FIELD_GENERATE/SCORE_ENHANCE/TEST_CONNECTION/SPEED_PROBE)
     */
    public String call(DcsAiConfig config, String promptText, boolean forceJson, String caller) {
        return call(config, promptText, forceJson, caller, null);
    }

    /**
     * 调 AI 模型(可选 JSON 强制 / 自然语言 / 槽参数覆盖), 并记录计费。
     *
     * @param config      AI 配置(已含明文 apiKey, 由 @EncryptField 解密)
     * @param promptText  完整 prompt
     * @param forceJson   true=强制 JSON 输出(原行为, AI 字段生成用); false=自然语言(画像诊断摘要用)
     * @param caller      调用方标识(PROFILE_DIAGNOSE/FIELD_GENERATE/SCORE_ENHANCE/TEST_CONNECTION/SPEED_PROBE)
     * @param overrides   后台槽调用参数覆盖(业务反馈 #14 追加 Q6-A, 2026-10-16); null=完全沿用
     *                    config 行值(存量行为)。字段语义: maxTokens null=沿用/0=显式不限制/>0=覆盖;
     *                    reasoningEffort null=沿用/空串=显式清空(不发思考参数)/非空=覆盖
     */
    public String call(DcsAiConfig config, String promptText, boolean forceJson, String caller,
                       CallOverrides overrides) {
        // Q5-A：对话主链只有 Chat Completions 一种协议；别的格式只支持连通测试/请求预览，
        // 这里明确拒绝而不是把 Anthropic/Responses 请求体错拼成 chat/completions。
        if (com.smartdock.enums.DcsAiApiFormat.of(config.getApiFormat())
            != com.smartdock.enums.DcsAiApiFormat.CHAT_COMPLETIONS) {
            throw new ServiceException("当前接口格式为 "
                + com.smartdock.enums.DcsAiApiFormat.of(config.getApiFormat()).getLabel()
                + "，对话主链仅支持 Chat Completions；请改用兼容端点，或仅用该格式做连通测试");
        }
        // 钱包余额预检: 管理探测类调用跳过(不应被钱包阻塞)
        if (!isWalletExempt(caller)) {
            String serviceCode = mapCallerToServiceCode(caller);
            FallbackDecision decision = tenantWalletService.checkSufficientOrFallback(
                config.getTenantId(), serviceCode, null);
            if (decision.isAlgorithmFallback()) {
                throw new AiWalletFallbackException(decision.getMessage());
            }
            if (decision.isBlock()) {
                throw new ServiceException(decision.getMessage());
            }
            // PASS / ALLOW_FREE → 继续
        }
        // 注意: 配置里的 baseUrl 已含版本路径(/v1 或 /paas/v4 等, 与 Hutool 探测端约定一致)。
        // Spring AI 的 OpenAiApi 默认 completionsPath 是 /v1/chat/completions, 会再补一层 /v1,
        // 导致 .../v1/v1/chat/completions -> 404。这里显式覆盖为 /chat/completions, 让最终 URL = baseUrl + /chat/completions。
        // 注: OpenAiApi.Builder 不支持 readTimeout(Spring AI 1.1.5), 超时由底层 RestClient 默认控制。
        OpenAiApi.Builder apiBuilder = apiBuilderWithGatewayHeaders(config)
            .completionsPath("/chat/completions");
        // dspro P1-2 回修（AI 缺口批4）：巡检探活设读超时上界——RestClient 默认无读超时，
        // 供应商半死连接会把 04:00 巡检线程永久挂住（@Lock4j watchdog 续期 → 锁永持 → 巡检停摆）。
        // 仅 PATROL 生效不动业务链路；OPENCODE 网关本地且其 builder 已带会话头拦截器，跳过避免覆盖。
        if (PATROL_CALLER.equals(caller) && !"OPENCODE".equalsIgnoreCase(config.getProvider())) {
            JdkClientHttpRequestFactory patrolFactory = new JdkClientHttpRequestFactory();
            patrolFactory.setReadTimeout(Duration.ofSeconds(PATROL_READ_TIMEOUT_SECONDS));
            apiBuilder.restClientBuilder(RestClient.builder().requestFactory(patrolFactory));
        }
        OpenAiApi api = apiBuilder.build();
        OpenAiChatOptions.Builder ob = OpenAiChatOptions.builder()
            .model(config.getModelName())
            .temperature(config.getTemperature());
        // maxTokens 支持(Q6-A: 槽覆盖优先, 0=显式不限制)
        Integer effectiveMaxTokens = resolveMaxTokens(config, overrides);
        if (effectiveMaxTokens != null && effectiveMaxTokens > 0) {
            ob.maxTokens(effectiveMaxTokens);
        }
        // 思考模式(reasoningEffort): DeepSeek/OpenAI reasoner 适用(Q6-A: 槽覆盖优先, 空串=显式清空)
        String effectiveEffort = resolveReasoningEffort(config, overrides);
        boolean isReasoner = config.getModelName() != null && config.getModelName().contains("reasoner");
        if (StrUtil.isNotBlank(effectiveEffort)) {
            ob.reasoningEffort(effectiveEffort.toLowerCase());
            // reasoner 模型锁定 temperature=0
            ob.temperature(0.0);
            isReasoner = true;
        }
        // reasoner 不支持 responseFormat JSON, 条件跳过
        if (forceJson && !isReasoner) {
            ob.responseFormat(ResponseFormat.builder().type(ResponseFormat.Type.JSON_OBJECT).build());
        }
        OpenAiChatModel model = OpenAiChatModel.builder()
            .openAiApi(api)
            .defaultOptions(ob.build())
            .toolCallingManager(ToolCallingManager.builder().build())
            .retryTemplate(retryTemplate)
            .observationRegistry(ObservationRegistry.NOOP)
            .build();
        long start = System.currentTimeMillis();
        // glm53 P1-1 回修（AI 缺口批4）：PATROL 巡检不落用量台账也不扣租户钱包——系统每日
        // 探测是平台基础设施行为非租户消费（手动测速/测连通是用户主动行为，沿用计费口径
        // 不动）；探活审计已由 dcs_ai_model_health 承载。顺带掐断「巡检 usage 行 → 永远算
        // 在用 → 永远被巡检」的自我续命闭环（mapper 侧 caller 过滤为双保险）。
        boolean bill = !PATROL_CALLER.equals(caller);
        try {
            ChatResponse response = model.call(new Prompt(promptText));
            long latency = System.currentTimeMillis() - start;
            AiTokenUsageSnapshot usage = extractUsage(response);
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                if (bill) {
                    billingService.record(config, caller, null, usage, latency, false, "AI模型未返回内容");
                }
                throw new ServiceException("AI模型未返回内容");
            }
            String content = response.getResult().getOutput().getText();
            if (StrUtil.isBlank(content)) {
                if (bill) {
                    billingService.record(config, caller, null, usage, latency, false, "AI模型返回内容为空");
                }
                throw new ServiceException("AI模型返回内容为空");
            }
            if (bill) {
                billingService.record(config, caller, null, usage, latency, true, null);
            }
            return content;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            if (bill) {
                billingService.record(config, caller, null, latency, false, e.getMessage());
            }
            throw new ServiceException("AI调用失败: " + e.getMessage());
        }
    }

    /**
     * 后台槽调用参数覆盖(Q6-A, 2026-10-16)。AiGatewayImpl 从 dcs_assistant_model_setting
     * 槽解析后传入; 字段三态语义见 {@code call(..., overrides)} 参数注释。
     */
    public record CallOverrides(Integer maxTokens, String reasoningEffort) {
    }

    /** maxTokens 合成: overrides 未涉=null=沿用 config 行; 0=显式不限制(清空); >0=覆盖。 */
    private static Integer resolveMaxTokens(DcsAiConfig config, CallOverrides overrides) {
        if (overrides == null || overrides.maxTokens() == null) {
            return config.getMaxTokens();
        }
        return overrides.maxTokens() > 0 ? overrides.maxTokens() : null;
    }

    /** reasoningEffort 合成: overrides 未涉=null=沿用 config 行; 空串=显式清空(不发思考参数); 非空=覆盖。 */
    private static String resolveReasoningEffort(DcsAiConfig config, CallOverrides overrides) {
        if (overrides == null || overrides.reasoningEffort() == null) {
            return config.getReasoningEffort();
        }
        return overrides.reasoningEffort().isBlank() ? null : overrides.reasoningEffort();
    }

    /**
     * OpenAiApi 构建基底(baseUrl + apiKey + OPENCODE 网关头), chat/embedding 两链共用。
     *
     * <p>OPENCODE Go 网关官方要求自报 UA + x-opencode-session(2026-10-08 实测缺 UA 被 Cloudflare 1010 拦)。
     * UA 不能走 {@code headers()}: OpenAiApi 构造器在 defaultHeaders 消费者里先 set("User-Agent","spring-ai")
     * 再 addAll(自定义头)(javap 实证), 双 UA 时网关只读首值看到的是 "spring-ai"——故 UA 改由 RestClient
     * 拦截器在默认头合成之后强制覆盖(本链仅 model.call() 非流式, 不涉 WebClient);
     * x-opencode-session 无同名竞争, 仍走 headers()。其它厂商不注入, 零影响。</p>
     */
    private OpenAiApi.Builder apiBuilderWithGatewayHeaders(DcsAiConfig config) {
        OpenAiApi.Builder builder = OpenAiApi.builder()
            .baseUrl(config.getBaseUrl())
            .apiKey(config.getApiKey());
        if ("OPENCODE".equalsIgnoreCase(config.getProvider())) {
            LinkedMultiValueMap<String, String> extraHeaders = new LinkedMultiValueMap<>();
            // 会话头带租户命名空间: 防跨租户共享网关会话路由/prompt 缓存(dspro 三审 P2)
            extraHeaders.add("x-opencode-session", "dcs-gw-" + config.getTenantId());
            builder.headers(extraHeaders)
                .restClientBuilder(RestClient.builder()
                    .requestInterceptor((request, body, execution) -> {
                        request.getHeaders().set(HttpHeaders.USER_AGENT, "dcs-assistant/1.0");
                        return execution.execute(request, body);
                    }));
        }
        return builder;
    }

    private AiTokenUsageSnapshot extractUsage(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return null;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return null;
        }
        Integer cachedTokens = null;
        Object nativeUsage = usage.getNativeUsage();
        if (nativeUsage instanceof OpenAiApi.Usage openAiUsage && openAiUsage.promptTokensDetails() != null) {
            cachedTokens = openAiUsage.promptTokensDetails().cachedTokens();
        }
        return new AiTokenUsageSnapshot(usage.getPromptTokens(), cachedTokens, usage.getCompletionTokens(), usage.getTotalTokens());
    }

    /**
     * 判断该调用方是否免钱包检查(管理探测类不阻塞)。
     * 三审回修（AI 缺口批4）：PATROL 同级豁免——巡检是系统每日行为，被钱包闸拦下会把
     * 「余额不足」误记成「模型连续异常」（假告警），且健康检查应只测模型本身不测钱包。
     */
    private boolean isWalletExempt(String caller) {
        return "TEST_CONNECTION".equals(caller)
            || "SPEED_PROBE".equals(caller)
            || "PRICE_DETECT".equals(caller)
            || "PATROL".equals(caller);
    }

    /**
     * 将调用方标签映射为计费规则 service_code。
     */
    private String mapCallerToServiceCode(String caller) {
        if (caller == null) {
            return "AI_PROFILE_SCORE";
        }
        return switch (caller) {
            case "PROFILE_DIAGNOSE" -> "AI_PROFILE_DIAGNOSE";
            case "FIELD_GENERATE" -> "AI_FIELD_GENERATE";
            case "SCORE_ENHANCE" -> "AI_SCORE_ENHANCE";
            case "SCORE_RULE_RECOMMEND" -> "AI_SCORE_RULE_RECOMMEND";
            case "PROFILE_EMBEDDING" -> "AI_PROFILE_EMBEDDING";
            default -> "AI_PROFILE_SCORE";
        };
    }

    /**
     * 调用 embedding 模型生成向量(Phase 4 相似用户画像检索)。
     * <p>与 {@link #call} 区别: 走 /v1/embeddings 端点, 返回 float[] 向量而非文本; 不涉及 JSON/responseFormat。
     *
     * @param config     EMBEDDING 配置(已含明文 apiKey, 由 @EncryptField 解密)
     * @param text       待向量化的文本(画像描述, 由 UserProfileContextBuilder 产出)
     * @param dimensions 输出维度(前期 1024; 后期升级 4096 时传 null 用模型默认值); 传 null 则不附加 dimensions 参数
     * @return 维度等于 dimensions(或模型默认)的 float 向量
     */
    public float[] callEmbedding(DcsAiConfig config, String text, Integer dimensions) {
        return callEmbedding(config, text, dimensions, "PROFILE_EMBEDDING");
    }

    /**
     * 调用 embedding 模型(显式 caller, 便于计费区分)。
     */
    public float[] callEmbedding(DcsAiConfig config, String text, Integer dimensions, String caller) {
        // 钱包余额预检(embedding 也计费, 不免检)
        String serviceCode = mapCallerToServiceCode(caller);
        FallbackDecision decision = tenantWalletService.checkSufficientOrFallback(
            config.getTenantId(), serviceCode, null);
        if (decision.isAlgorithmFallback()) {
            throw new AiWalletFallbackException(decision.getMessage());
        }
        if (decision.isBlock()) {
            throw new ServiceException(decision.getMessage());
        }
        // 构造 embedding 端点 (与 call 同理: OpenAiApi 默认会补 /v1, 这里用 /embeddings 避免 /v1/v1/embeddings)
        OpenAiApi api = apiBuilderWithGatewayHeaders(config)
            .embeddingsPath("/embeddings")
            .build();
        OpenAiEmbeddingOptions.Builder ob = OpenAiEmbeddingOptions.builder()
            .model(config.getModelName());
        if (dimensions != null && dimensions > 0) {
            ob.dimensions(dimensions);
        }
        // Spring AI 1.1.5: OpenAiEmbeddingModel(api, MetadataMode, options, retryTemplate)
        OpenAiEmbeddingModel model = new OpenAiEmbeddingModel(api,
            org.springframework.ai.document.MetadataMode.EMBED, ob.build(), retryTemplate);
        long start = System.currentTimeMillis();
        try {
            // embed(Document) 直接返回 float[], 简单直接
            float[] vector = model.embed(new Document(text));
            long latency = System.currentTimeMillis() - start;
            if (vector == null || vector.length == 0) {
                billingService.record(config, caller, null, null, latency, false, "Embedding模型未返回内容");
                throw new ServiceException("Embedding模型未返回内容");
            }
            // 维度校验: 防止 dimensions 参数未生效导致与 DDL 不符
            if (dimensions != null && dimensions > 0 && vector.length != dimensions) {
                billingService.record(config, caller, null, null, latency, false,
                    "Embedding维度不匹配: 期望=" + dimensions + " 实际=" + vector.length);
                throw new ServiceException("Embedding维度不匹配: 期望 " + dimensions + " 实际 " + vector.length
                    + "(检查模型是否支持该 dimensions 参数)");
            }
            // embedding 计费: 用法暂记 null (embed(Document) 不返回 token metadata, Phase 4 可优化查 dcs_ai_usage)
            billingService.record(config, caller, null, null, latency, true, null);
            return vector;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            billingService.record(config, caller, null, null, latency, false, e.getMessage());
            throw new ServiceException("Embedding调用失败: " + e.getMessage());
        }
    }
}
