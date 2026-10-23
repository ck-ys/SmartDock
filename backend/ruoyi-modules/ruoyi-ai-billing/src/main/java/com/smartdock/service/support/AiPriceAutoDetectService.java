package com.smartdock.service.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.core.util.ReUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HtmlUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.domain.DcsAiDocDomain;
import com.smartdock.domain.vo.AiPriceDetectResult;
import com.smartdock.mapper.DcsAiConfigMapper;
import com.smartdock.mapper.DcsAiDocDomainMapper;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

import java.math.BigDecimal;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能识别模型计费参数(三级降级: 聚合API → 正则 → AI)。
 *
 * <p>用户在官方 token 计费参数区填入文档 URL, 系统自动识别价格。</p>
 * <ol>
 *   <li><b>聚合 API 优先</b>: 查 pricetoken.ai 统一价格 API, 海外模型(OpenAI/Anthropic/Google/DeepSeek/Qwen 等)
 *       直接拿结构化 JSON, 无需抓页面。国产小厂(GLM/Kimi/百度/讯飞等) pricetoken 未覆盖, 自动降级。</li>
 *   <li>SSRF 校验: URL host 必须命中预置厂商文档域名白名单</li>
 *   <li>HTTP 抓取 → cleanHtmlTag → 截断 8000 字</li>
 *   <li>正则匹配常见价格模式(命中输入+输出则直接返回)</li>
 *   <li>AI 回退: 调 DcsAiChatClientFactory 提取 JSON 价格(PRICE_DETECT 不扣钱包)</li>
 * </ol>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiPriceAutoDetectService {

    private static final int FETCH_TIMEOUT_MS = 15000;
    private static final int MAX_TEXT_LENGTH = 8000;

    /** pricetoken.ai 聚合价格 API(免费, 无 Key 30次/小时, 每日更新) */
    private static final String PRICETOKEN_API = "https://pricetoken.ai/api/v1/text";
    private static final String PRICETOKEN_SOURCE = "pricetoken.ai (聚合数据)";
    /** Q4-A：OpenRouter 官方 models 接口（公开、无需鉴权，pricing 字段为 USD/token） */
    private static final String OPENROUTER_MODELS_API = "https://openrouter.ai/api/v1/models";
    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);

    /** AI 聊天客户端工厂(正则失败时 AI 回退提取价格) */
    private final DcsAiChatClientFactory chatClientFactory;
    /** AI 配置 Mapper(回写识别到的价格) */
    private final DcsAiConfigMapper aiConfigMapper;
    /** AI 配置服务(J 批七审回修: AI 提取选模型统一走单点解析器, 不再跨租户直查) */
    private final com.smartdock.service.IDcsAiConfigService aiConfigService;
    /** 文档域名白名单 Mapper(SSRF 校验来源) */
    private final DcsAiDocDomainMapper docDomainMapper;

    /**
     * 智能识别模型计费参数。
     *
     * <p>优先查聚合 API(覆盖海外主流模型), 未覆盖时降级到文档抓取。</p>
     */
    public AiPriceDetectResult detect(String provider, String modelName, String sourceUrl) {
        // Q4-A（2026-10-23 拍板）：OpenRouter 走官方 models 接口——一条请求拿全量价格，
        // 不再抓动态渲染的定价页（free 转发页抓不到有效文本）。
        if ("OPENROUTER".equalsIgnoreCase(StrUtil.trim(provider))) {
            AiPriceDetectResult orResult = tryOpenRouterApi(modelName);
            if (orResult != null) {
                return orResult;
            }
        }
        // 0. 聚合 API 优先(海外模型直接拿结构化 JSON, 无需抓页面)
        AiPriceDetectResult apiResult = tryPricetokenApi(provider, modelName);
        if (apiResult != null) {
            return apiResult;
        }
        // pricetoken 未覆盖 → 降级到文档抓取流程
        if (StrUtil.isBlank(sourceUrl)) {
            throw new ServiceException("该模型不在聚合价格库中, 请填写官方计费文档 URL");
        }
        // 1. SSRF 校验
        assertDocUrlAllowed(sourceUrl);
        // 2. HTTP 抓取
        String text = fetchAndClean(sourceUrl);
        // 3. 正则先行
        AiPriceDetectResult regexResult = tryRegexExtract(text, sourceUrl);
        if (regexResult != null) {
            regexResult.setMethod("REGEX");
            return regexResult;
        }
        // 4. AI 回退
        AiPriceDetectResult aiResult = tryAiExtract(provider, modelName, text, sourceUrl);
        if (aiResult != null) {
            aiResult.setMethod("AI");
            return aiResult;
        }
        return AiPriceDetectResult.failure(sourceUrl, "未能从文档识别价格, 请手动填写", "NONE");
    }

    // ========== 聚合 API (pricetoken.ai) ==========

    /**
     * 查 pricetoken.ai 聚合价格 API。
     *
     * <p>仅覆盖 OpenAI/Anthropic/Google/xAI/DeepSeek/Qwen 等海外主流厂商。
     * 国产小厂(GLM/Kimi/百度/讯飞等)返回 null, 由调用方降级到文档抓取。</p>
     *
     * @return 命中返回带 USD 价格的结果; 未覆盖/超时/无匹配返回 null
     */
    AiPriceDetectResult tryPricetokenApi(String provider, String modelName) {
        String ptProvider = mapToPricetokenProvider(provider);
        if (ptProvider == null || StrUtil.isBlank(modelName)) {
            return null;
        }
        String modelLower = modelName.trim().toLowerCase();
        try {
            String json = fetchPricetokenJson();
            Map<String, Object> root = JsonUtils.parseMap(json);
            if (root == null) {
                return null;
            }
            Object dataObj = root.get("data");
            if (!(dataObj instanceof List<?> dataList)) {
                return null;
            }
            // 第一轮: modelId 精确匹配(同 provider)
            Map<String, Object> match = findModel(dataList, ptProvider, modelLower, true);
            // 第二轮: modelId 包含匹配(同 provider)
            if (match == null) {
                match = findModel(dataList, ptProvider, modelLower, false);
            }
            if (match == null) {
                return null;
            }
            BigDecimal input = toBd(match.get("inputPerMTok"));
            BigDecimal output = toBd(match.get("outputPerMTok"));
            if (input == null && output == null) {
                return null;
            }
            // 数据新鲜度: freshness.stale=true → 置信度降级
            String confidence = extractConfidence(match);
            AiPriceDetectResult result = AiPriceDetectResult.success(
                input, null, output, "USD", PRICETOKEN_SOURCE, confidence);
            result.setMethod("PRICETOKEN");
            return result;
        } catch (Exception e) {
            log.warn("[PriceDetect] pricetoken API 查询失败, 降级到文档抓取: {}", e.getMessage());
            return null;
        }
    }

    /** DCS provider 枚举 → pricetoken provider 小写名(null=未覆盖)。 */
    private String mapToPricetokenProvider(String provider) {
        if (StrUtil.isBlank(provider)) {
            return null;
        }
        return switch (provider.trim().toUpperCase()) {
            case "DEEPSEEK" -> "deepseek";
            case "QWEN" -> "qwen";
            case "OPENAI" -> "openai";
            case "ANTHROPIC" -> "anthropic";
            case "GOOGLE", "GEMINI" -> "google";
            case "XAI", "GROK" -> "xai";
            // Q4-A：补齐国内常见厂商映射；pricetoken 未收录时 findModel 返回 null，自动降级
            case "ZHIPU" -> "zhipu";
            case "KIMI" -> "moonshot";
            case "MINIMAX" -> "minimax";
            case "SILICONFLOW" -> "siliconflow";
            case "STEPFUN" -> "stepfun";
            default -> null;
        };
    }

    // ========== Q4-A：OpenRouter 官方 models 接口 ==========

    /**
     * 查 OpenRouter 官方模型目录价格。
     *
     * <p>响应 {@code data[].pricing.prompt/completion} 单位是 USD/token，
     * 本平台价格表按「每百万 token」存储，此处乘 1e6。free 模型两项均为 0，
     * 币种归一为 CNY(0)，否则保留 USD（与价格来源一致，不做汇率臆算）。</p>
     */
    AiPriceDetectResult tryOpenRouterApi(String modelName) {
        if (StrUtil.isBlank(modelName)) {
            return null;
        }
        try (HttpResponse response = HttpRequest.get(OPENROUTER_MODELS_API)
            .timeout(FETCH_TIMEOUT_MS)
            .setMaxRedirectCount(3)
            .header("User-Agent", "Mozilla/5.0 (compatible; SmartDock-PriceBot/1.0)")
            .header("Accept", "application/json")
            .execute()) {
            if (!response.isOk()) {
                log.warn("[PriceDetect] OpenRouter models API HTTP {}", response.getStatus());
                return null;
            }
            return extractOpenRouterPrice(JsonUtils.parseMap(response.body()), modelName);
        } catch (Exception e) {
            log.warn("[PriceDetect] OpenRouter 官方接口查询失败, 降级到文档抓取: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 纯函数：从 OpenRouter /models 响应中提取指定模型价格。
     * 精确 id 优先，其次 {@code 厂商/模型} 后缀匹配（用户常只填模型名）。
     */
    @SuppressWarnings("unchecked")
    static AiPriceDetectResult extractOpenRouterPrice(Map<String, Object> root, String modelName) {
        if (root == null || StrUtil.isBlank(modelName)) {
            return null;
        }
        Object dataObj = root.get("data");
        if (!(dataObj instanceof List<?> data)) {
            return null;
        }
        String target = modelName.trim().toLowerCase();
        Map<String, Object> hit = null;
        for (Object item : data) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object idObj = m.get("id");
            String id = idObj == null ? "" : String.valueOf(idObj).toLowerCase();
            if (id.equals(target)) {
                hit = (Map<String, Object>) m;
                break;
            }
            // 只认「厂商/模型名」后缀：contains 会把 gpt-4 命中 gpt-4o/gpt-4-turbo 等同厂不同型号的价
            if (hit == null && !id.isEmpty() && id.endsWith("/" + target)) {
                hit = (Map<String, Object>) m;
            }
        }
        if (hit == null || !(hit.get("pricing") instanceof Map<?, ?> pricing)) {
            return null;
        }
        BigDecimal prompt = toDecimal(pricing.get("prompt"));
        BigDecimal completion = toDecimal(pricing.get("completion"));
        if (prompt == null || completion == null) {
            return null;
        }
        BigDecimal input = prompt.multiply(ONE_MILLION).stripTrailingZeros();
        BigDecimal output = completion.multiply(ONE_MILLION).stripTrailingZeros();
        BigDecimal cached = toDecimal(pricing.get("input_cache_read"));
        BigDecimal cachedInput = cached == null ? null : cached.multiply(ONE_MILLION).stripTrailingZeros();
        boolean free = input.compareTo(BigDecimal.ZERO) == 0 && output.compareTo(BigDecimal.ZERO) == 0;
        AiPriceDetectResult r = AiPriceDetectResult.success(
            input, cachedInput, output, free ? "CNY" : "USD",
            OPENROUTER_MODELS_API, "high");
        r.setMethod("OPENROUTER");
        r.setMessage(free ? "OpenRouter 免费模型（价格 0）" : "来源 OpenRouter 官方 models 接口（USD）");
        return r;
    }

    private static BigDecimal toDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 在模型列表中查找匹配项。
     *
     * @param exact true=精确匹配 modelId; false=modelId 互相 contains
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> findModel(List<?> dataList, String ptProvider,
                                          String modelLower, boolean exact) {
        for (Object item : dataList) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            if (!ptProvider.equals(String.valueOf(m.get("provider")).toLowerCase())) {
                continue;
            }
            String modelId = String.valueOf(m.get("modelId")).toLowerCase();
            boolean hit = exact
                ? modelId.equals(modelLower)
                : (modelId.contains(modelLower) || modelLower.contains(modelId));
            if (hit) {
                return (Map<String, Object>) m;
            }
        }
        return null;
    }

    /** 从 freshness.stale 推断置信度: stale=true → low, 否则沿用 API 返回的 confidenceLevel。 */
    private String extractConfidence(Map<String, Object> model) {
        Object freshness = model.get("freshness");
        if (freshness instanceof Map<?, ?> f && Boolean.TRUE.equals(f.get("stale"))) {
            return "low";
        }
        Object conf = model.get("confidenceLevel");
        return conf != null ? String.valueOf(conf) : "medium";
    }

    /** 抓取 pricetoken API 全量文本模型列表(固定可信端点, 不走 SSRF 白名单校验)。 */
    private String fetchPricetokenJson() {
        try (HttpResponse response = HttpRequest.get(PRICETOKEN_API)
            .timeout(FETCH_TIMEOUT_MS)
            .setMaxRedirectCount(3)
            .header("User-Agent", "Mozilla/5.0 (compatible; DCS-PriceBot/1.0)")
            .header("Accept", "application/json")
            .execute()) {
            if (!response.isOk()) {
                throw new ServiceException("pricetoken API HTTP " + response.getStatus());
            }
            return response.body();
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("pricetoken API 请求异常: " + e.getMessage());
        }
    }

    // ========== SSRF 防御 ==========

    private void assertDocUrlAllowed(String url) {
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            throw new ServiceException("URL 格式不正确");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ServiceException("仅支持 https URL");
        }
        String host = uri.getHost();
        if (StrUtil.isBlank(host)) {
            throw new ServiceException("URL host 为空");
        }
        // 防内网
        if (isInternalHost(host)) {
            throw new ServiceException("拒绝访问内网地址");
        }
        // 白名单匹配(从 DB 读取, 支持子域)
        List<String> allowedDomains = loadAllowedDomains();
        boolean allowed = allowedDomains.stream()
            .anyMatch(domain -> host.equalsIgnoreCase(domain) || host.endsWith("." + domain));
        if (!allowed) {
            throw new ServiceException("该 URL 域名不在白名单内, 请在「识别域名管理」菜单中添加 " + host);
        }
    }

    private boolean isInternalHost(String host) {
        String lower = host.toLowerCase();
        return "localhost".equals(lower) || lower.startsWith("127.")
            || lower.startsWith("169.254.") || lower.startsWith("10.")
            || lower.startsWith("192.168.")
            || lower.startsWith("172.16.") || lower.startsWith("172.17.")
            || lower.startsWith("172.18.") || lower.startsWith("172.19.")
            || lower.startsWith("172.2") || lower.startsWith("172.3")
            || "::1".equals(lower);
    }

    /**
     * 从 DB 加载启用的白名单域名列表。
     */
    private List<String> loadAllowedDomains() {
        List<DcsAiDocDomain> rows = docDomainMapper.selectList(
            new LambdaQueryWrapper<DcsAiDocDomain>()
                .eq(DcsAiDocDomain::getEnabled, "1")
                .eq(DcsAiDocDomain::getDelFlag, "0"));
        return rows.stream()
            .map(DcsAiDocDomain::getDomain)
            .filter(StrUtil::isNotBlank)
            .map(String::toLowerCase)
            .toList();
    }

    // ========== HTTP 抓取 ==========

    private String fetchAndClean(String url) {
        try (HttpResponse response = HttpRequest.get(url)
            .timeout(FETCH_TIMEOUT_MS)
            .setMaxRedirectCount(5)
            .header("User-Agent", "Mozilla/5.0 (compatible; DCS-PriceBot/1.0)")
            .execute()) {
            if (!response.isOk()) {
                throw new ServiceException("抓取文档失败: HTTP " + response.getStatus());
            }
            String html = response.body();
            String text = HtmlUtil.cleanHtmlTag(html);
            text = StrUtil.isBlank(text) ? html : text;
            // 压缩空白 + 截断
            text = text.replaceAll("\\s+", " ").trim();
            return text.length() > MAX_TEXT_LENGTH ? text.substring(0, MAX_TEXT_LENGTH) : text;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("抓取文档异常: " + e.getMessage());
        }
    }

    // ========== 正则提取 ==========

    /**
     * 尝试正则匹配常见价格模式。
     * 命中至少 2 个价格字段(输入/输出)则返回结果。
     */
    AiPriceDetectResult tryRegexExtract(String text, String sourceUrl) {
        BigDecimal input = null;
        BigDecimal output = null;
        BigDecimal cachedInput = null;
        String currency = detectCurrency(text);

        // 模式1: 中文 "输入 X 元 / 百万" 或 "输入 X / 百万 token"
        // 模式2: "input: $X" 或 "Input: ¥X"
        // 模式3: "X元/百万tokens"
        input = findPriceNearKeyword(text, List.of("输入", "input", "Input", "缓存未命中"));
        output = findPriceNearKeyword(text, List.of("输出", "output", "Output", "生成"));
        cachedInput = findPriceNearKeyword(text, List.of("缓存输入", "cached input", "cache hit", "命中缓存"));

        if (input == null && output == null) {
            return null;
        }
        // 至少命中输入+输出才算成功
        if (input == null || output == null) {
            return null;
        }
        return AiPriceDetectResult.success(input, cachedInput, output, currency, sourceUrl, "medium");
    }

    private BigDecimal findPriceNearKeyword(String text, List<String> keywords) {
        for (String kw : keywords) {
            int idx = text.indexOf(kw);
            if (idx < 0) {
                continue;
            }
            // 取关键词后 80 字符窗口
            int end = Math.min(text.length(), idx + kw.length() + 80);
            String window = text.substring(idx, end);
            // 匹配 ¥X / $X / X元 / X.XX / X.XX
            Matcher m = Pattern.compile("(?:¥|￥|\\$)?\\s*(\\d+(?:\\.\\d{1,6})?)\\s*(?:元/百万|元/1M|/百万|/1M|per.*?million)").matcher(window);
            if (m.find()) {
                try {
                    return new BigDecimal(m.group(1));
                } catch (Exception ignore) {
                }
            }
            // 匹配 X.XX(裸数字, 可能是价格)
            m = Pattern.compile("(?:¥|￥|\\$)\\s*(\\d+(?:\\.\\d{1,6})?)").matcher(window);
            if (m.find()) {
                try {
                    return new BigDecimal(m.group(1));
                } catch (Exception ignore) {
                }
            }
        }
        return null;
    }

    private String detectCurrency(String text) {
        if (text.contains("¥") || text.contains("￥") || text.contains("元")) {
            return "CNY";
        }
        if (text.contains("$")) {
            return "USD";
        }
        return "CNY";
    }

    // ========== AI 提取 ==========

    private AiPriceDetectResult tryAiExtract(String provider, String modelName, String text, String sourceUrl) {
        try {
            // J 批七审回修(Explore 席 P1): 原跨租户任取启用行(无租户/use_type/CNY 闸三缺)——
            // 违反多租户隔离且可能选到 EMBEDDING 行或美元模型。统一走单点解析器取当前租户
            // 的 CHAT 配置; 无配置/闸不过(均抛 ServiceException)由下方 catch 统一降级跳过
            DcsAiConfig config = aiConfigService.resolveEnabledConfig(
                org.dromara.common.tenant.helper.TenantHelper.getTenantId(), "CHAT");
            if (config == null) {
                log.warn("[PriceDetect] 无可用 AI 配置, 跳过 AI 提取");
                return null;
            }
            String prompt = buildExtractPrompt(text, modelName);
            String content = chatClientFactory.call(config, prompt, true, "PRICE_DETECT");
            return parseAiResult(content, sourceUrl);
        } catch (Exception e) {
            log.warn("[PriceDetect] AI 提取失败: {}", e.getMessage());
            return null;
        }
    }

    private String buildExtractPrompt(String text, String modelName) {
        return """
            你是定价信息提取专家。从以下大模型官方文档文本中提取 %s 模型的计费参数。
            重点关注"输入/缓存输入/输出"的每百万 token 单价。

            返回严格 JSON 格式(只返回 JSON, 不要其它文字):
            {"inputPricePerMillion": 1.0, "cachedInputPricePerMillion": 0.1, "outputPricePerMillion": 8.0, "currency": "CNY", "confidence": "high"}

            规则:
            - 价格单位统一为"每百万 token"(per 1M tokens)
            - 如果文档用"每千 token", 请换算为每百万(×1000)
            - currency 根据文档符号判断: ¥/元=CNY, $=USD
            - 找不到的字段设为 null
            - confidence: high=明确找到, medium=推断, low=不确定

            文档文本(已清理HTML, 截断8000字):
            %s
            """.formatted(StrUtil.blankToDefault(modelName, "未知模型"), text);
    }

    @SuppressWarnings("unchecked")
    private AiPriceDetectResult parseAiResult(String content, String sourceUrl) {
        if (StrUtil.isBlank(content)) {
            return null;
        }
        try {
            String clean = content.trim();
            if (clean.startsWith("```")) {
                clean = clean.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").trim();
            }
            Map<String, Object> obj = JsonUtils.parseMap(clean);
            if (obj == null) {
                return null;
            }
            BigDecimal input = toBd(obj.get("inputPricePerMillion"));
            BigDecimal output = toBd(obj.get("outputPricePerMillion"));
            BigDecimal cachedInput = toBd(obj.get("cachedInputPricePerMillion"));
            String currency = StrUtil.toStringOrNull(obj.get("currency"));
            String confidence = StrUtil.toStringOrNull(obj.get("confidence"));
            if (input == null && output == null) {
                return null;
            }
            return AiPriceDetectResult.success(input, cachedInput, output,
                StrUtil.blankToDefault(currency, "CNY"), sourceUrl,
                StrUtil.blankToDefault(confidence, "medium"));
        } catch (Exception e) {
            log.warn("[PriceDetect] AI 结果解析失败: {}", e.getMessage());
            return null;
        }
    }

    private BigDecimal toBd(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
