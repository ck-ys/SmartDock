package com.smartdock.service.support;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.json.utils.JsonUtils;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.domain.bo.DcsAiProbeBo;
import com.smartdock.domain.vo.DcsAiBalanceVo;
import com.smartdock.domain.vo.DcsAiModelVo;
import com.smartdock.domain.vo.DcsAiTestResultVo;
import com.smartdock.enums.DcsAiApiFormat;
import com.smartdock.enums.DcsAiProvider;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible provider 探测客户端。
 *
 * @author smartdock
 */
@Component
@RequiredArgsConstructor
public class DcsAiProviderProbeClient {

    /** Kimi 订阅额度固定端点站点（与对话 baseUrl 的 moonshot 域名不同，2026-10-23 实测） */
    private static final String KIMI_QUOTA_ROOT = "https://api.kimi.com";

    private final DcsAiChatClientFactory chatClientFactory;

    /**
     * 拉取 provider 模型列表(调用 /models 接口), 解析 data 数组中的 id / owned_by / 免费标记。
     */
    public List<DcsAiModelVo> listModels(DcsAiProbeBo bo) {
        String body = get(apiUrl(bo.getBaseUrl(), "models"), bo.getApiKey(), bo.getTimeoutSeconds(), bo.getProvider());
        Map<String, Object> root = JsonUtils.parseObject(body, new TypeReference<>() {});
        Object data = root == null ? null : root.get("data");
        if (!(data instanceof List<?> list)) {
            throw new ServiceException("模型列表响应格式不正确");
        }
        List<DcsAiModelVo> result = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                String id = StrUtil.toStringOrNull(map.get("id"));
                if (StrUtil.isNotBlank(id)) {
                    result.add(new DcsAiModelVo(id, StrUtil.toStringOrNull(map.get("owned_by")), parseFreeFlag(map)));
                }
            }
        }
        return result;
    }

    /**
     * 业务批4 Q16-A：从 /models 响应解析免费标记——OpenRouter 等聚合源在 pricing.prompt 报
     * 字符串单价（"0"=免费，对应官方 :free 系）；无 pricing 字段或解析失败返回 null（不猜）。
     */
    private Boolean parseFreeFlag(Map<?, ?> modelItem) {
        Object pricing = modelItem.get("pricing");
        if (!(pricing instanceof Map<?, ?> pm)) {
            return null;
        }
        String promptPrice = StrUtil.toStringOrNull(pm.get("prompt"));
        if (StrUtil.isBlank(promptPrice)) {
            return null;
        }
        try {
            return new java.math.BigDecimal(promptPrice.trim()).signum() == 0;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 查询 provider 账号余额(按厂商路由: SILICONFLOW/DEEPSEEK 走各自余额端点, 其它不支持)。
     */
    public DcsAiBalanceVo balance(DcsAiProbeBo bo) {
        String provider = bo.getProvider();
        // 2026-08-01: 新增 SILICONFLOW 分支, 端点 /v1/user/info (字段 data.totalBalance/chargeBalance/balance)
        if ("SILICONFLOW".equalsIgnoreCase(provider)) {
            return querySiliconFlowBalance(bo);
        }
        // 业务批4 Q11(2026-10-12): OpenRouter 专有余额端点 GET {baseUrl}/key
        // 响应 { data: { usage, limit } }——usage=已用美元额度, limit=null/0 表示不限额
        if ("OPENROUTER".equalsIgnoreCase(provider)) {
            return queryOpenRouterBalance(bo);
        }
        // Q6(2026-10-23): 补齐国内四家用量/额度端点（实测字段见各方法注释）
        if ("ZHIPU".equalsIgnoreCase(provider)) {
            return queryZhipuBalance(bo);
        }
        if ("STEPFUN".equalsIgnoreCase(provider)) {
            return queryStepFunBalance(bo);
        }
        if ("KIMI".equalsIgnoreCase(provider)) {
            return queryKimiQuota(bo);
        }
        if ("MINIMAX".equalsIgnoreCase(provider)) {
            return queryMiniMaxQuota(bo);
        }
        if (!"DEEPSEEK".equalsIgnoreCase(provider)) {
            return new DcsAiBalanceVo(false, "该厂商暂未提供标准余额接口", List.of());
        }
        String body = get(deepSeekRootUrl(bo.getBaseUrl()) + "/user/balance", bo.getApiKey(), bo.getTimeoutSeconds());
        Map<String, Object> root = JsonUtils.parseObject(body, new TypeReference<>() {});
        if (root == null) {
            throw new ServiceException("余额响应格式不正确");
        }
        Boolean available = root.get("is_available") instanceof Boolean value ? value : null;
        List<DcsAiBalanceVo.BalanceInfo> balances = new ArrayList<>();
        Object balanceInfos = root.get("balance_infos");
        if (balanceInfos instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    balances.add(new DcsAiBalanceVo.BalanceInfo(
                        StrUtil.toStringOrNull(map.get("currency")),
                        StrUtil.toStringOrNull(map.get("total_balance")),
                        StrUtil.toStringOrNull(map.get("granted_balance")),
                        StrUtil.toStringOrNull(map.get("topped_up_balance"))
                    ));
                }
            }
        }
        return new DcsAiBalanceVo(available, available != null && available ? "余额可用" : "余额不足或不可用", balances);
    }

    /**
     * 硅基流动余额查询 (2026-08-01 新增).
     * <p>端点: GET {baseUrl}/user/info (保留 /v1, 与 DEEPSEEK 去掉 /v1 不同).
     * <p>响应结构: { code: 20000, status: true, data: { balance, chargeBalance, totalBalance } } (字符串金额, 单位元).
     * <p>映射到 DcsAiBalanceVo: currency=CNY, totalBalance=data.totalBalance, toppedUpBalance=data.chargeBalance.
     */
    private DcsAiBalanceVo querySiliconFlowBalance(DcsAiProbeBo bo) {
        String body = get(normalizeBaseUrl(bo.getBaseUrl()) + "/user/info", bo.getApiKey(), bo.getTimeoutSeconds());
        Map<String, Object> root = JsonUtils.parseObject(body, new TypeReference<>() {});
        if (root == null) {
            throw new ServiceException("硅基流动余额响应格式不正确");
        }
        // 硅基流动: status=true 表示成功 (code 20000)
        Boolean status = root.get("status") instanceof Boolean s ? s : null;
        Object dataObj = root.get("data");
        if (!(dataObj instanceof Map<?, ?> data)) {
            return new DcsAiBalanceVo(false, "硅基流动返回数据为空", List.of());
        }
        String totalBalance = StrUtil.toStringOrNull(data.get("totalBalance"));
        String chargeBalance = StrUtil.toStringOrNull(data.get("chargeBalance"));
        // balance 字段可能是 "0" 或具体金额, totalBalance 是最准确的"总余额"
        List<DcsAiBalanceVo.BalanceInfo> balances = List.of(new DcsAiBalanceVo.BalanceInfo(
            "CNY", StrUtil.blankToDefault(totalBalance, "0"), null, chargeBalance));
        boolean ok = Boolean.TRUE.equals(status) && StrUtil.isNotBlank(totalBalance);
        return new DcsAiBalanceVo(ok, ok ? "余额可用" : "硅基流动余额查询失败", balances);
    }

    /**
     * OpenRouter 余额查询 (业务批4 Q11, 2026-10-12 新增).
     * <p>端点: GET {baseUrl}/key (保留 /v1, OpenAI 兼容根).
     * <p>响应结构: { data: { label, usage, limit, is_limit_tier } }——usage=已用美元, limit=上限美元(null/0=不限额).
     * <p>映射到 DcsAiBalanceVo: currency=USD, totalBalance=limit-usage(不限额时显示已用), toppedUpBalance=usage(借用"充值"位展示已用).
     */
    private DcsAiBalanceVo queryOpenRouterBalance(DcsAiProbeBo bo) {
        String body = get(normalizeBaseUrl(bo.getBaseUrl()) + "/key", bo.getApiKey(), bo.getTimeoutSeconds());
        Map<String, Object> root = JsonUtils.parseObject(body, new TypeReference<>() {});
        if (root == null) {
            throw new ServiceException("OpenRouter 余额响应格式不正确");
        }
        Object dataObj = root.get("data");
        if (!(dataObj instanceof Map<?, ?> data)) {
            return new DcsAiBalanceVo(false, "OpenRouter 返回数据为空(密钥无效?)", List.of());
        }
        String usage = StrUtil.toStringOrNull(data.get("usage"));
        String limit = StrUtil.toStringOrNull(data.get("limit"));
        double used = parseDoubleOrZero(usage);
        double cap = parseDoubleOrZero(limit);
        // limit 为空或 0 = 不限额 key：可用额展示"不限"，仅报已用。
        // dsflash P2 回修：差值定 2 位小数——double 裸串会带 0.10000000000000009 类尾噪
        boolean unlimited = cap <= 0;
        String remaining = unlimited ? "不限" : String.format(java.util.Locale.ROOT, "%.2f", Math.max(0, cap - used));
        List<DcsAiBalanceVo.BalanceInfo> balances = List.of(new DcsAiBalanceVo.BalanceInfo(
            "USD", remaining, null, usage));
        boolean ok = StrUtil.isNotBlank(usage);
        return new DcsAiBalanceVo(ok, ok ? "额度可用" : "OpenRouter 额度查询失败", balances);
    }

    /**
     * 智谱余额 (Q6, 2026-10-23)。
     *
     * <p>端点: GET {站点根}/api/biz/account/query-customer-account-report。
     * <p>鉴权: {@code Authorization: <key>}——智谱 OpenAPI <b>不带 Bearer</b> 前缀。
     * <p>响应: {@code {code:200, success:true, data:{availableBalance,...}}}, 单位元。
     */
    private DcsAiBalanceVo queryZhipuBalance(DcsAiProbeBo bo) {
        String url = zhipuRootUrl(bo.getBaseUrl()) + "/api/biz/account/query-customer-account-report";
        String body = getWithHeaders(url, Map.of(
            "Authorization", StrUtil.trim(bo.getApiKey()),
            "Accept", "application/json"), bo.getTimeoutSeconds());
        Map<String, Object> root = JsonUtils.parseObject(body, new TypeReference<>() {});
        if (root == null) {
            throw new ServiceException("智谱余额响应格式不正确");
        }
        Object dataObj = root.get("data");
        if (!(dataObj instanceof Map<?, ?> data)) {
            return new DcsAiBalanceVo(false,
                StrUtil.blankToDefault(StrUtil.toStringOrNull(root.get("msg")), "智谱余额查询失败"), List.of());
        }
        String available = firstText(data, "availableBalance", "balance", "totalBalance");
        boolean ok = StrUtil.isNotBlank(available);
        return new DcsAiBalanceVo(ok, ok ? "余额可用" : "智谱余额查询失败",
            ok ? List.of(new DcsAiBalanceVo.BalanceInfo("CNY", available, null, null)) : List.of());
    }

    /** 智谱余额端点在站点根, baseUrl 常带 /api/paas/v4 或 /api/anthropic 后缀, 需剥掉 */
    private String zhipuRootUrl(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        for (String suffix : List.of("/api/paas/v4", "/api/anthropic", "/api/coding/paas/v4", "/v4", "/v1")) {
            if (normalized.endsWith(suffix)) {
                return StrUtil.removeSuffix(normalized, suffix);
            }
        }
        return normalized;
    }

    /**
     * 阶跃星辰余额 (Q6, 2026-10-23)。
     *
     * <p>端点: GET {站点根}/v1/accounts (Bearer)。
     * <p>响应字段随账号类型不同, 依次尝试 {@code data.balance} / {@code balance} /
     * {@code data[0].balance}, 都取不到就明确报"无余额字段"而不是假装成功。
     */
    private DcsAiBalanceVo queryStepFunBalance(DcsAiProbeBo bo) {
        String body = get(stepFunRootUrl(bo.getBaseUrl()) + "/v1/accounts", bo.getApiKey(), bo.getTimeoutSeconds());
        Map<String, Object> root = JsonUtils.parseObject(body, new TypeReference<>() {});
        if (root == null) {
            throw new ServiceException("阶跃星辰余额响应格式不正确");
        }
        Object dataObj = root.get("data");
        String balance = firstText(root, "balance", "totalBalance");
        if (StrUtil.isBlank(balance) && dataObj instanceof Map<?, ?> data) {
            balance = firstText(data, "balance", "totalBalance", "availableBalance");
        }
        if (StrUtil.isBlank(balance) && dataObj instanceof List<?> list && !list.isEmpty()
            && list.get(0) instanceof Map<?, ?> first) {
            balance = firstText(first, "balance", "totalBalance", "availableBalance");
        }
        boolean ok = StrUtil.isNotBlank(balance);
        return new DcsAiBalanceVo(ok, ok ? "余额可用" : "阶跃星辰返回中没有余额字段",
            ok ? List.of(new DcsAiBalanceVo.BalanceInfo("CNY", balance, null, null)) : List.of());
    }

    /** 阶跃余额端点在站点根, baseUrl 常以 /v1 结尾 */
    private String stepFunRootUrl(String baseUrl) {
        return StrUtil.removeSuffix(normalizeBaseUrl(baseUrl), "/v1");
    }

    /**
     * Kimi 订阅额度 (Q6, 2026-10-23)。
     *
     * <p>端点: GET https://api.kimi.com/coding/v1/usages (Bearer)。
     * <p>响应含 {@code usage:{limit,remaining}} 与 {@code limits:[{detail:{limit,remaining}}]};
     * 两者都按"已用百分比"折算展示——Kimi 是订阅额度, 没有可提现余额。
     */
    private DcsAiBalanceVo queryKimiQuota(DcsAiProbeBo bo) {
        // 额度端点在固定站点（api.kimi.com），与对话 baseUrl（api.moonshot.cn/.ai）不是同一域名；
        // 跟着 baseUrl 派生会 404（2026-10-23 实测）
        String body = get(KIMI_QUOTA_ROOT + "/coding/v1/usages", bo.getApiKey(), bo.getTimeoutSeconds());
        Map<String, Object> root = JsonUtils.parseObject(body, new TypeReference<>() {});
        if (root == null) {
            throw new ServiceException("Kimi 额度响应格式不正确");
        }
        Double usedPercent = percentFromQuota(root.get("usage"));
        if (usedPercent == null && root.get("limits") instanceof List<?> limits) {
            // 取剩余最少的那个窗口 = 当前最紧张的限制
            for (Object item : limits) {
                if (item instanceof Map<?, ?> limit && limit.get("detail") instanceof Map<?, ?> detail) {
                    Double p = percentFromQuota(detail);
                    if (p != null && (usedPercent == null || p > usedPercent)) {
                        usedPercent = p;
                    }
                }
            }
        }
        return quotaResult("Kimi", usedPercent);
    }

    /**
     * MiniMax 订阅额度 (Q6, 2026-10-23)。
     *
     * <p>端点: GET https://api.minimaxi.com/v1/api/openplatform/coding_plan/remains (Bearer)。
     * <p>响应: {@code base_resp.status_code=0} 且 {@code model_remains:[{model_name,
     * current_interval_remaining_percent}]}——按剩余百分比展示。
     */
    private DcsAiBalanceVo queryMiniMaxQuota(DcsAiProbeBo bo) {
        String body = get(miniMaxApiRoot(bo.getBaseUrl()) + "/v1/api/openplatform/coding_plan/remains",
            bo.getApiKey(), bo.getTimeoutSeconds());
        Map<String, Object> root = JsonUtils.parseObject(body, new TypeReference<>() {});
        if (root == null) {
            throw new ServiceException("MiniMax 额度响应格式不正确");
        }
        if (root.get("base_resp") instanceof Map<?, ?> baseResp
            && StrUtil.toStringOrNull(baseResp.get("status_code")) != null
            && !"0".equals(StrUtil.toStringOrNull(baseResp.get("status_code")))) {
            return new DcsAiBalanceVo(false,
                StrUtil.blankToDefault(StrUtil.toStringOrNull(baseResp.get("status_msg")), "MiniMax 额度查询失败"), List.of());
        }
        Double remaining = null;
        if (root.get("model_remains") instanceof List<?> remains) {
            for (Object item : remains) {
                if (item instanceof Map<?, ?> remain) {
                    String pct = firstText(remain, "current_interval_remaining_percent", "remaining_percent");
                    Double p = parsePercent(pct);
                    if (p != null && (remaining == null || p < remaining)) {
                        remaining = p;
                    }
                }
            }
        }
        if (remaining == null) {
            return new DcsAiBalanceVo(false, "MiniMax 返回中没有额度字段", List.of());
        }
        return new DcsAiBalanceVo(true, "额度可用", List.of(new DcsAiBalanceVo.BalanceInfo(
            "%", String.format(java.util.Locale.ROOT, "%.1f", remaining), null,
            String.format(java.util.Locale.ROOT, "%.1f", 100D - remaining))));
    }

    /** Kimi / MiniMax 共用: 已用百分比 -> 余额 VO */
    private DcsAiBalanceVo quotaResult(String label, Double usedPercent) {
        if (usedPercent == null) {
            return new DcsAiBalanceVo(false, label + " 返回中没有额度字段", List.of());
        }
        double remaining = Math.max(0D, 100D - usedPercent);
        return new DcsAiBalanceVo(true, "额度可用", List.of(new DcsAiBalanceVo.BalanceInfo(
            "%", String.format(java.util.Locale.ROOT, "%.1f", remaining), null,
            String.format(java.util.Locale.ROOT, "%.1f", usedPercent))));
    }

    /** {limit, remaining} -> 已用百分比; 缺字段或 limit<=0 返回 null */
    private Double percentFromQuota(Object quotaObj) {
        if (!(quotaObj instanceof Map<?, ?> quota)) {
            return null;
        }
        double limit = parseDoubleOrZero(firstText(quota, "limit", "total"));
        double remaining = parseDoubleOrZero(firstText(quota, "remaining", "left"));
        if (limit <= 0) {
            return null;
        }
        return Math.min(100D, Math.max(0D, (limit - remaining) / limit * 100D));
    }

    private Double parsePercent(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return Double.parseDouble(value.replace("%", "").trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 按候选键顺序取第一个非空字符串 */
    private String firstText(Map<?, ?> map, String... keys) {
        for (String key : keys) {
            String value = StrUtil.toStringOrNull(map.get(key));
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String miniMaxApiRoot(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        // 先剥更长的 /coding/v1 再剥 /v1，避免留下 /coding 残尾
        return StrUtil.removeSuffix(StrUtil.removeSuffix(normalized, "/coding/v1"), "/v1");
    }

    /** 自定义请求头的 GET(智谱余额端点要裸 Authorization, 不能用 bearerAuth) */
    private String getWithHeaders(String url, Map<String, String> headers, Integer timeoutSeconds) {
        HttpRequest request = HttpRequest.get(url)
            .timeout(Math.max(5, timeoutSeconds == null ? 60 : timeoutSeconds) * 1000);
        headers.forEach(request::header);
        try (HttpResponse response = request.execute()) {
            if (!response.isOk()) {
                throw new ServiceException(describeHttpFailure(response.getStatus()));
            }
            return response.body();
        }
    }

    /** 字符串金额安全转 double(空/非法返回 0), OpenRouter 余额解析用 */
    private double parseDoubleOrZero(String value) {
        if (StrUtil.isBlank(value)) {
            return 0D;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0D;
        }
    }

    /**
     * 测速: 调用一次 chat 并测量延迟(失败也返回结构化结果, 不抛异常)。
     */
    public DcsAiTestResultVo speed(DcsAiProbeBo bo) {
        if (StrUtil.isBlank(bo.getModelName())) {
            throw new ServiceException("请先选择模型");
        }
        DcsAiConfig config = new DcsAiConfig();
        config.setProvider(bo.getProvider());
        config.setBaseUrl(normalizeBaseUrl(bo.getBaseUrl()));
        config.setApiKey(bo.getApiKey());
        config.setModelName(StrUtil.trim(bo.getModelName()));
        config.setTemperature(bo.getTemperature() == null ? 0.2D : bo.getTemperature());
        config.setTimeoutSeconds(bo.getTimeoutSeconds() == null ? 60 : bo.getTimeoutSeconds());
        try {
            long begin = System.nanoTime();
            String content = chatClientFactory.call(config, "请只回复 JSON: {\"pong\":true}", true, "SPEED_PROBE");
            long latencyMs = (System.nanoTime() - begin) / 1_000_000;
            return new DcsAiTestResultVo(true, StrUtil.blankToDefault(content, "测速成功"), latencyMs);
        } catch (Exception e) {
            return new DcsAiTestResultVo(false, StrUtil.blankToDefault(e.getMessage(), "测速失败"));
        }
    }

    /**
     * Q5-A（2026-10-23）：按接口格式做 1-token 连通性探测。
     *
     * <p>Anthropic Messages 用 {@code x-api-key} + {@code anthropic-version}；
     * Responses 用 Bearer + {@code input}/{@code max_output_tokens}。只判 HTTP 2xx，
     * 不解析内容——这是连通性测试，不是对话调用。</p>
     */
    public DcsAiTestResultVo probeFormat(DcsAiProbeBo bo, String apiFormat) {
        DcsAiApiFormat format = DcsAiApiFormat.of(apiFormat);
        if (format == DcsAiApiFormat.CHAT_COMPLETIONS) {
            throw new ServiceException("Chat Completions 走对话链探活");
        }
        String model = StrUtil.blankToDefault(StrUtil.trim(bo.getModelName()), "probe");
        String safeModel = model.replace("\\", "\\\\").replace("\"", "\\\"");
        String body = format == DcsAiApiFormat.ANTHROPIC_MESSAGES
            ? "{\"model\":\"" + safeModel + "\",\"max_tokens\":1,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"
            : "{\"model\":\"" + safeModel + "\",\"input\":\"hi\",\"max_output_tokens\":16}";
        HttpRequest request = HttpRequest.post(format.requestUrl(bo.getBaseUrl()))
            .timeout(Math.max(5, bo.getTimeoutSeconds() == null ? 60 : bo.getTimeoutSeconds()) * 1000)
            .header("Content-Type", "application/json")
            .body(body);
        if (format == DcsAiApiFormat.ANTHROPIC_MESSAGES) {
            request.header("x-api-key", bo.getApiKey()).header("anthropic-version", "2023-06-01");
        } else {
            request.bearerAuth(bo.getApiKey());
        }
        long begin = System.nanoTime();
        try (HttpResponse response = request.execute()) {
            long latencyMs = (System.nanoTime() - begin) / 1_000_000;
            if (!response.isOk()) {
                String snippet = StrUtil.maxLength(StrUtil.blankToDefault(response.body(), ""), 200);
                return new DcsAiTestResultVo(false,
                    describeHttpFailure(response.getStatus()) + (StrUtil.isBlank(snippet) ? "" : "：" + snippet));
            }
            return new DcsAiTestResultVo(true, format.getLabel() + " 连通（HTTP " + response.getStatus() + "）", latencyMs);
        } catch (Exception e) {
            return new DcsAiTestResultVo(false, "连通测试失败: " + e.getMessage());
        }
    }

    private String get(String url, String apiKey, Integer timeoutSeconds) {
        return get(url, apiKey, timeoutSeconds, null);
    }

    /** @param provider OPENCODE 时补网关要求的自报 UA + 会话头(缺 UA 会被 Cloudflare 1010 拦, 2026-10-08 实测) */
    private String get(String url, String apiKey, Integer timeoutSeconds, String provider) {
        HttpRequest request = HttpRequest.get(url)
            .bearerAuth(apiKey)
            .timeout(Math.max(5, timeoutSeconds == null ? 60 : timeoutSeconds) * 1000);
        if ("OPENCODE".equalsIgnoreCase(provider)) {
            request.header("User-Agent", "dcs-assistant/1.0")
                .header("x-opencode-session", "dcs-gateway");
        }
        try (HttpResponse response = request.execute()) {
            if (!response.isOk()) {
                throw new ServiceException(describeHttpFailure(response.getStatus()));
            }
            return response.body();
        }
    }

    /**
     * 业务批3-②(#13) 厂商探针 HTTP 错误人话化：裸 "provider请求失败: HTTP 401" 用户看不懂，
     * 按常见状态码翻译成可操作的动作指引（纯函数，单测可直测）
     */
    static String describeHttpFailure(int status) {
        switch (status) {
            case 401:
                return "厂商密钥无效或未授权（HTTP 401），请到厂商后台重新生成密钥后再试";
            case 410:
                return "该厂商的查询接口已下线，不支持自动查询（HTTP 410）";
            case 429:
                return "厂商限流，请求太频繁，请稍后再试（HTTP 429）";
            default:
                return "厂商接口异常（HTTP " + status + "），请稍后重试或检查接口地址";
        }
    }

    private String apiUrl(String baseUrl, String path) {
        return normalizeBaseUrl(baseUrl) + "/" + path;
    }

    private String normalizeBaseUrl(String baseUrl) {
        return StrUtil.removeSuffix(StrUtil.trim(baseUrl), "/");
    }

    private String deepSeekRootUrl(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        return StrUtil.removeSuffix(normalized, "/v1");
    }

    /**
     * baseUrl SSRF 防御校验 (B1, 见 document/DCS_AUDIT_2026_06_22.md)。
     *
     * <p>规则:</p>
     * <ol>
     *   <li>必须 https (预置厂商全是 https, http 同名 host 也拒, 防降级攻击);</li>
     *   <li>host 必须命中该 provider 的官方域名集合（预置 baseUrl host + 模板库同厂商变体
     *       host，如智谱 bigmodel.cn/z.ai、Kimi moonshot.cn/.ai、千问 dashscope 国内/国际、
     *       MiniMax minimax.cn/.io、硅基流动 siliconflow.cn/.com），防指向攻击者服务器；</li>
     *   <li>host 不能是 IP 字面量, 不能命中内网/环回/链路本地段 (10/172.16-31/192.168/127/169.254)。</li>
     * </ol>
     *
     * <p>2026-10-23 N1 回修（dspro P1-4）：原实现只认预置单域名，模板库里的国际站模板
     * （z.ai / moonshot.cn / dashscope-intl / minimax.io / siliconflow.com）选完一探测就被
     * 自己的 SSRF 白名单拒掉——白名单与模板库同源后不再自相矛盾。</p>
     *
     * <p>抽成 public static 便于单测覆盖 (DcsAiBaseUrlGuardUnitTest)。</p>
     *
     * @param provider 指定 provider (决定白名单 host)
     * @param baseUrl  待校验的 URL
     * @return true 允许发起探测请求; false 拒绝
     */
    public static boolean isBaseUrlAllowed(DcsAiProvider provider, String baseUrl) {
        if (provider == null || StrUtil.isBlank(baseUrl)) {
            return false;
        }
        URI input;
        URI preset;
        try {
            input = new URI(baseUrl.trim());
            preset = new URI(provider.getBaseUrl());
        } catch (URISyntaxException e) {
            return false;
        }
        // OPENCODE 豁免：部署形态=客户/内网自建 Go 网关（http + 内网 IP 是常态），
        // 枚举预置的 opencode.ai 仅是云端中转参考值——host 相等性/https/内网三检查
        // 会把所有真实网关地址全拒（2026-10-16 客户机"获取模型"实锤）。该厂商行由
        // 管理员配置、指向自家网关，无 SSRF 外泄面；仅要求 URL 合法且 host 非空。
        if (provider == DcsAiProvider.OPENCODE) {
            String scheme = input.getScheme();
            return (("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                && StrUtil.isNotBlank(input.getHost()));
        }
        // 1) 必须 https (预置厂商全是 https)
        if (!"https".equalsIgnoreCase(input.getScheme())) {
            return false;
        }
        String inputHost = input.getHost();
        String presetHost = preset.getHost();
        if (StrUtil.isBlank(inputHost) || StrUtil.isBlank(presetHost)) {
            return false;
        }
        // 2) host 必须命中该 provider 的官方域名集合（预置 host + 模板库变体 host）
        if (!allowedHosts(provider, presetHost).contains(inputHost.toLowerCase())) {
            return false;
        }
        // 3) 防御性: host 不能是 IP 字面量, 不能命中内网段
        //    (正常情况下预置 host 都是域名如 api.deepseek.com, 不会命中; 但保留校验防配置被改)
        if (isInternalHost(inputHost)) {
            return false;
        }
        return true;
    }

    /**
     * 该 provider 的官方域名集合（小写）：预置 baseUrl host + 模板库同厂商变体 host。
     * 模板库是唯一来源，避免「模板能选、探测被拒」两套名单漂移。
     */
    private static java.util.Set<String> allowedHosts(DcsAiProvider provider, String presetHost) {
        java.util.Set<String> hosts = new java.util.HashSet<>();
        hosts.add(presetHost.toLowerCase());
        for (com.smartdock.domain.vo.DcsAiProviderTemplateVo t
            : com.smartdock.enums.DcsAiProviderTemplates.list()) {
            if (provider.name().equals(t.getProvider()) && StrUtil.isNotBlank(t.getBaseUrl())) {
                try {
                    String h = new URI(t.getBaseUrl()).getHost();
                    if (StrUtil.isNotBlank(h)) {
                        hosts.add(h.toLowerCase());
                    }
                } catch (URISyntaxException ignored) {
                    // 模板地址自身不合法=跳过，不影响预置 host 放行
                }
            }
        }
        return hosts;
    }

    /**
     * 判断 host 是否为内网/环回/链路本地的 IP 字面量。
     * 域名直接返回 false (域名解析由上游 DNS 控制, 不在 SSRF 防御职责内)。
     */
    private static boolean isInternalHost(String host) {
        if (StrUtil.isBlank(host)) {
            return true;
        }
        String lower = host.toLowerCase();
        // 环回
        if ("localhost".equals(lower) || lower.startsWith("127.")) {
            return true;
        }
        // 链路本地 169.254.x.x (云元数据常见)
        if (lower.startsWith("169.254.")) {
            return true;
        }
        // 内网 10.x.x.x
        if (lower.startsWith("10.")) {
            return true;
        }
        // 内网 192.168.x.x
        if (lower.startsWith("192.168.")) {
            return true;
        }
        // 内网 172.16-31.x.x
        if (lower.startsWith("172.")) {
            String[] parts = lower.split("\\.");
            if (parts.length >= 2) {
                try {
                    int second = Integer.parseInt(parts[1]);
                    if (second >= 16 && second <= 31) {
                        return true;
                    }
                } catch (NumberFormatException ignore) {
                    // 非数字, 不是 IP 字面量
                }
            }
        }
        // IPv6 环回
        if ("::1".equals(lower) || "0:0:0:0:0:0:0:1".equals(lower)) {
            return true;
        }
        return false;
    }
}
