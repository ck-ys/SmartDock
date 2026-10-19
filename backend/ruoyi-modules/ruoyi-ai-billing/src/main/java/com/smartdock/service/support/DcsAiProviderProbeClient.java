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
     *   <li>host 必须等于指定 provider 预置 baseUrl 的 host (域名白名单, 防指向攻击者服务器);</li>
     *   <li>host 不能是 IP 字面量, 不能命中内网/环回/链路本地段 (10/172.16-31/192.168/127/169.254)。</li>
     * </ol>
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
        // 2) host 必须等于预置 host (大小写不敏感)
        if (!inputHost.equalsIgnoreCase(presetHost)) {
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
