package org.dromara.assistant.tools;

import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.limiter.AssistantWebSearchQuota;
import org.dromara.assistant.security.AssistantPiiFilter;
import org.dromara.assistant.service.AssistantUserQuotaService;
import org.dromara.common.core.service.ConfigService;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 联网搜索工具：web_search（2026-10-06 用户拍板接入博查 Web Search API）。
 *
 * <p><b>key 走系统参数</b>（用户要求"放系统参数随时可改"）：{@code dcs.assistant.websearch.key}，
 * 每次调用实时读取——参数为空即视为未启用，返回 DISABLED JSON（模型会转告用户功能未开放），
 * 填入 key 立即生效无需重启。注意 sys_config 有缓存口径：改值请走系统管理「参数设置」
 * 界面或 API（直改库不刷缓存）。</p>
 *
 * <p><b>出网脱敏</b>：搜索词出网前过 {@link AssistantPiiFilter#mask}（与模型出网装饰器同
 * 口径）——用户把手机号/身份证写进搜索词时先脱敏再发给博查。</p>
 *
 * <p><b>计费配额（业务批3-④ #17，2026-10-12）</b>：每次执行先过 {@link AssistantWebSearchQuota}
 * 日配额（单人覆盖 &gt; 租户默认 websearch_limit_per_user，平台默认 20 次/天）——超限不计数、
 * 返回人话 LIMIT JSON（模型转告恢复时点，管理端「用户额度」可重置）。上游 HTTP 失败/超时/
 * 异常时 refundSearch 补偿——用户不为「没搜到」买单。</p>
 *
 * <p><b>站点范围（Q8A）</b>：include/exclude 管道分隔域名（≤100 个），博查接口本身不支持
 * 站点过滤，本工具对返回结果按 url 主机名做后缀匹配客户端过滤；include 指定后仅保留命中
 * 站点，exclude 剔除命中站点。域名语法非法返回 BAD_REQUEST。</p>
 *
 * <p><b>契约</b>：POST https://api.bocha.cn/v1/web-search（Bearer 鉴权，Bing 兼容响应，
 * data.webPages.value[]）。返回紧凑 JSON：query/rows（name/siteName/datePublished/summary/
 * url）/total/truncated/filteredOut/status；上游 403/410/429 等人话转告（模型可直接念给
 * 用户）；超时或上游异常返回错误 JSON 不炸会话（QueryTools 同款防御）。本工具只读不入库，
 * 不占动作账本。</p>
 */
@Slf4j
public class WebSearchTools {

    /** 系统参数 key：博查 API Key（空=未启用） */
    public static final String CONFIG_KEY = "dcs.assistant.websearch.key";

    /**
     * 系统参数 key（业务反馈批1015b Q14）：「作为全平台默认」显式开关——true 时，没配自己
     * key 的租户借用平台（000000）的搜索 key（博查按次计费，费用记平台账，用户拍板知情）。
     * 默认不配置=不共用，与 Q8「平台兜底必须显式勾选」同一口径；开关行放在平台租户宇宙。
     */
    public static final String PLATFORM_DEFAULT_SWITCH_KEY = "dcs.assistant.websearch.platform_default";

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** O-3 口径（原判定抽出复用）：空/off/disabled 占位与非 sk- 前缀一律视为不可用 */
    private static boolean isUsableKey(String keyTrim) {
        return !keyTrim.isEmpty()
            && !keyTrim.equalsIgnoreCase("off")
            && !keyTrim.equalsIgnoreCase("disabled")
            && keyTrim.startsWith("sk-");
    }

    /** Q14（业务反馈批1015b）：搜索 key 解析——本租户 key 不可用且非平台自身时，
     * 查平台宇宙的「作为全平台默认」显式开关（true/1），开着借平台 key；否则维持本租户值 */
    private String resolveSearchKey(String tenantId) {
        String key = org.dromara.common.tenant.helper.TenantHelper.dynamic(
            tenantId, () -> configService.getConfigValue(CONFIG_KEY));
        String keyTrim = key == null ? "" : key.trim();
        if (!isUsableKey(keyTrim) && !"000000".equals(tenantId)) {
            String sw = org.dromara.common.tenant.helper.TenantHelper.dynamic(
                "000000", () -> configService.getConfigValue(PLATFORM_DEFAULT_SWITCH_KEY));
            String swTrim = sw == null ? "" : sw.trim();
            if ("true".equalsIgnoreCase(swTrim) || "1".equals(swTrim)) {
                String platformKey = org.dromara.common.tenant.helper.TenantHelper.dynamic(
                    "000000", () -> configService.getConfigValue(CONFIG_KEY));
                String pTrim = platformKey == null ? "" : platformKey.trim();
                if (isUsableKey(pTrim)) {
                    return pTrim;
                }
            }
        }
        return keyTrim;
    }

    private static final String ENDPOINT = "https://api.bocha.cn/v1/web-search";
    private static final int MAX_COUNT = 8;
    private static final int DEFAULT_COUNT = 5;
    /** 站点范围列表域名数上限（Q8A 定稿 100） */
    private static final int MAX_SITES = 100;

    private final ToolIdentity identity;
    private final ConfigService configService;
    private final AssistantPiiFilter piiFilter;
    /** 测试可注入短超时；生产走 ToolContracts.DEFAULT_TIMEOUT_MS */
    private final long timeoutMs;
    /** 联网日配额（业务批3-④）：trySearch 计数 / refundSearch 失败补偿 */
    private final AssistantWebSearchQuota webSearchQuota;
    /** 限额源（单人覆盖>租户默认，批③三层合成唯一出口） */
    private final AssistantUserQuotaService quotaService;

    public WebSearchTools(ToolIdentity identity, ConfigService configService, AssistantPiiFilter piiFilter,
                          AssistantWebSearchQuota webSearchQuota, AssistantUserQuotaService quotaService) {
        this(identity, configService, piiFilter, webSearchQuota, quotaService, ToolContracts.DEFAULT_TIMEOUT_MS);
    }

    public WebSearchTools(ToolIdentity identity, ConfigService configService, AssistantPiiFilter piiFilter,
                          AssistantWebSearchQuota webSearchQuota, AssistantUserQuotaService quotaService,
                          long timeoutMs) {
        this.identity = identity;
        this.configService = configService;
        this.piiFilter = piiFilter;
        this.webSearchQuota = webSearchQuota;
        this.quotaService = quotaService;
        this.timeoutMs = timeoutMs;
    }

    @Tool(name = "web_search",
        description = "联网搜索公开网络信息（博查搜索引擎）。适用于系统内没有的实时或外部知识："
            + "新闻、天气、政策、公开资料、常识等。返回网页列表（标题/来源/日期/摘要/链接）。"
            + "任务/待办/催办等系统内数据禁止用本工具，请用对应查询工具。",
        readOnly = true)
    public String webSearch(
        @ToolParam(name = "query", description = "搜索关键词，1~100 字")
        String query,
        @ToolParam(name = "count", required = false, description = "返回条数 1-" + MAX_COUNT + "，缺省 " + DEFAULT_COUNT)
        Integer count,
        @ToolParam(name = "freshness", required = false,
            description = "结果时间范围：oneDay/oneWeek/oneMonth/oneYear/noLimit，缺省 noLimit")
        String freshness,
        @ToolParam(name = "include", required = false,
            description = "只保留这些站点的结果，管道分隔域名，如 a.com|b.com，最多 " + MAX_SITES + " 个；可与 exclude 同时使用（先剔除再只留）")
        String include,
        @ToolParam(name = "exclude", required = false,
            description = "剔除这些站点的结果，管道分隔域名，如 a.com|b.com，最多 " + MAX_SITES + " 个；可与 include 同时使用（先剔除再只留）")
        String exclude) {

        ObjectNode out = MAPPER.createObjectNode();
        // 二站② AU-48：时间锚——搜索结果是「取数时点」的快照，模型后续引用须知口径
        out.put("asOf", ToolSupport.nowIso());
        String tenantId = identity.getTenantId();
        Long userId = identity.getUserId();
        // 补偿锚：只有过完配额闸（已计数）后的失败才 refund——前置环节异常误退会
        // 错扣上一次搜索的计数（Lua DECR 无按次标记）
        boolean counted = false;
        try {
            // 读当前租户宇宙的 sys_config：参数设置界面 PUT 走 @CachePut 刷的也是该宇宙，
            // 改动即时生效。禁 ignore——那会落 global 宇宙缓存，界面改完助手仍读旧值（双宇宙实测踩坑）
            // 业务反馈批1015b Q14：本租户没配/关停时，看平台「作为全平台默认」显式开关——
            // 开着才借平台 key（费用记平台账）；开关没开=维持原"未启用"口径
            String keyTrim = resolveSearchKey(tenantId);
            boolean disabled = !isUsableKey(keyTrim);
            if (disabled) {
                out.put("status", "DISABLED");
                out.put("message", "联网搜索未启用：管理员未配置搜索服务密钥（系统参数 "
                    + CONFIG_KEY + "，博查 key 以 sk- 开头；填 off 可随时关停；或在平台侧开启「作为全平台默认」共用平台密钥）。请转告用户该功能暂不可用，不要重试。");
                return ToolSupport.secureJson(out, identity);
            }
            String q = query == null ? "" : query.trim();
            if (q.isEmpty() || q.length() > 100) {
                out.put("status", "BAD_REQUEST");
                out.put("message", "搜索词须为 1~100 字");
                return ToolSupport.secureJson(out, identity);
            }
            // 站点范围参数校验（Q8A）：语法非法/超量直接 BAD_REQUEST，不打无效请求出网
            List<String> includeSites = parseSites(include);
            if (include != null && includeSites == null) {
                out.put("status", "BAD_REQUEST");
                out.put("message", "include 域名列表格式不对：管道分隔的纯域名（如 a.com|b.com），最多 " + MAX_SITES + " 个");
                return ToolSupport.secureJson(out, identity);
            }
            List<String> excludeSites = parseSites(exclude);
            if (exclude != null && excludeSites == null) {
                out.put("status", "BAD_REQUEST");
                out.put("message", "exclude 域名列表格式不对：管道分隔的纯域名（如 a.com|b.com），最多 " + MAX_SITES + " 个");
                return ToolSupport.secureJson(out, identity);
            }

            // 计费配额闸（业务批3-④）：单人覆盖>租户默认；超限不计数+人话转告恢复时点
            // 业务批7 Q1：计费感知视图——积分订阅租户联网=套餐档位（未配档沿用租户默认）
            int limit = quotaService.billingAwareLimits(tenantId, userId).websearchLimit();
            // 0/负=租户/单人侧把联网关到零：语义是「未开放」而非「当日用完」（dspro P2-2），
            // 不能承诺「明日 0 点恢复」（明天照样不行）；也不触 Redis 计数
            if (limit <= 0) {
                out.put("status", "DISABLED");
                out.put("message", "联网搜索当前未开放（额度为 0），请联系管理员在「AI 助手管理」调整后再试。请转告用户。");
                return ToolSupport.secureJson(out, identity);
            }
            // 三态闸（dspro P2-1 回修）：COUNTED=已计数可补偿退款；PASSED_UNCOUNTED=fail-open
            // 放行未计数（Redis 抖动），退款守卫只认 COUNTED——否则会 DECR 掉当日既有计数多退额度
            org.dromara.assistant.limiter.AssistantWebSearchQuota.Gate gate =
                webSearchQuota.trySearch(tenantId, userId, limit);
            if (gate == org.dromara.assistant.limiter.AssistantWebSearchQuota.Gate.DENIED) {
                out.put("status", "LIMIT");
                out.put("message", "今日联网搜索次数已用完（每人每天 " + limit
                    + " 次），明日 0 点自动恢复；如需提前恢复请联系管理员在「AI 助手管理-用户额度」重置。请转告用户，不要重试。");
                return ToolSupport.secureJson(out, identity);
            }
            counted = gate == org.dromara.assistant.limiter.AssistantWebSearchQuota.Gate.COUNTED;

            String effective = piiFilter.mask(q, tenantId);
            int n = count == null ? DEFAULT_COUNT : Math.max(1, Math.min(MAX_COUNT, count));
            String range = freshness == null || freshness.isBlank() ? "noLimit" : freshness;

            ObjectNode body = MAPPER.createObjectNode();
            body.put("query", effective);
            body.put("count", n);
            body.put("freshness", range);
            body.put("summary", true);

            String resp;
            try (HttpResponse hr = HttpRequest.post(ENDPOINT)
                .header("Authorization", "Bearer " + keyTrim)
                .header("Content-Type", "application/json")
                .body(MAPPER.writeValueAsBytes(body))
                .timeout((int) timeoutMs)
                .execute()) {
                resp = hr.body();
                if (hr.getStatus() != 200) {
                    // 失败不占用户额度：上游没给结果就回退本次计数（用户不为「没搜到」买单）
                    if (counted) {
                        webSearchQuota.refundSearch(tenantId, userId);
                    }
                    out.put("status", "UPSTREAM_ERROR");
                    out.put("message", describeHttpFailure(hr.getStatus()));
                    log.warn("[dcs-assistant] web_search 上游非 200(已补偿额度): {} body={}",
                        hr.getStatus(), resp.substring(0, Math.min(resp.length(), 200)));
                    return ToolSupport.secureJson(out, identity);
                }
            }

            JsonNode pages = MAPPER.readTree(resp).path("data").path("webPages").path("value");
            // 200 但结构异常（网关改写/上游业务错误体）：按上游失败补偿口径退款，
            // 不当「成功 0 条」静默扣次（dspro P2-3——用户不为「没搜到」买单的口径要盖到空结构）
            if (!pages.isArray()) {
                if (counted) {
                    webSearchQuota.refundSearch(tenantId, userId);
                }
                out.put("status", "UPSTREAM_ERROR");
                out.put("message", "搜索服务返回了无法解析的响应（HTTP 200 但内容异常），本次不消耗联网次数，请稍后再试。");
                log.warn("[dcs-assistant] web_search 上游 200 空结构(已补偿额度): body={}",
                    resp.substring(0, Math.min(resp.length(), 200)));
                return ToolSupport.secureJson(out, identity);
            }
            out.put("status", "OK");
            out.put("query", effective);
            ArrayNode rows = out.putArray("rows");
            int added = 0;
            int filteredOut = 0;
            for (JsonNode p : pages) {
                if (added >= n) {
                    break;
                }
                String url = p.path("url").asText("");
                if (!siteAllowed(url, includeSites, excludeSites)) {
                    filteredOut++;
                    continue;
                }
                ObjectNode row = rows.addObject();
                row.put("name", p.path("name").asText(""));
                row.put("siteName", p.path("siteName").asText(""));
                row.put("datePublished", p.path("datePublished").asText(""));
                String summary = p.path("summary").asText("");
                row.put("summary", summary.isBlank() ? p.path("snippet").asText("") : summary);
                row.put("url", url);
                added++;
            }
            out.put("total", added);
            out.put("filteredOut", filteredOut);
            out.put("truncated", pages.size() > added + filteredOut);
            return ToolSupport.secureJson(out, identity);
        } catch (Exception e) {
            // 批I 零技术词：异常类名只进日志（排错），喂模型/用户的 message 换人话；
            // 已过配额闸（counted）的出网异常才补偿——前置环节异常未计数不退（防错退上一次）
            if (counted) {
                webSearchQuota.refundSearch(tenantId, userId);
            }
            log.warn("[dcs-assistant] web_search 调用失败(不炸会话,已补偿额度): {} {}", e.getClass().getSimpleName(), e.getMessage());
            out.put("status", "ERROR");
            out.put("message", "搜索服务暂时不可用，请稍后再试；也可以先把问题改成问我系统内的数据");
            return ToolSupport.secureJson(out, identity);
        }
    }

    /**
     * 上游人话化（业务批3-④ S2）：403/410/429/其它映射成模型可直接念给用户的文案，
     * 与 DcsAiProviderProbeClient.describeHttpFailure 同风格（零技术词）。
     */
    static String describeHttpFailure(int status) {
        return switch (status) {
            case 401 -> "搜索服务密钥无效（管理员配置问题），请转告用户该功能暂不可用，不要重试";
            case 403 -> "搜索服务拒绝了本次请求（密钥权限不足），请转告用户稍后再试或联系管理员";
            case 410 -> "该搜索接口已下线，不支持继续调用，请转告用户该功能暂不可用";
            case 429 -> "搜索太频繁了，请稍等片刻再试";
            default -> "搜索服务异常（HTTP " + status + "），请稍后再试或换个关键词";
        };
    }

    /**
     * 站点列表解析（Q8A）：管道分隔纯域名，忽略空段；返回 null=格式非法（非域名 token/
     * 超 100 个）。域名允许子域（a.b.com），不允许 scheme/路径/通配/IP 简化处理（保持
     * 校验严格——非法输入宁可拒绝也不带病出网）。
     */
    static List<String> parseSites(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String[] parts = raw.split("\\|", -1);
        List<String> sites = new ArrayList<>();
        for (String part : parts) {
            String d = part.trim().toLowerCase(Locale.ROOT);
            if (d.isEmpty()) {
                continue;
            }
            if (!isDomain(d)) {
                return null;
            }
            sites.add(d);
        }
        if (sites.size() > MAX_SITES) {
            return null;
        }
        return sites;
    }

    /** RFC1036 宽松域名判定：标量段字母数字连字符，尾段 ≥2 字母，总长 ≤253 */
    private static boolean isDomain(String d) {
        if (d.length() > 253 || d.startsWith("-") || d.endsWith("-") || d.contains("..")) {
            return false;
        }
        String[] labels = d.split("\\.", -1);
        if (labels.length < 2) {
            return false;
        }
        String last = labels[labels.length - 1];
        if (last.length() < 2 || !last.matches("[a-z]{2,}")) {
            return false;
        }
        for (String label : labels) {
            if (label.isEmpty() || !label.matches("[a-z0-9-]+") || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
        }
        return true;
    }

    /** url 主机名后缀匹配：include 非空=必须命中其一；exclude 命中即剔除（子域同算） */
    static boolean siteAllowed(String url, List<String> includeSites, List<String> excludeSites) {
        if (includeSites.isEmpty() && excludeSites.isEmpty()) {
            return true;
        }
        String host = hostOf(url);
        if (host == null) {
            // 解析不出主机：include 需要正向证明才保留（证不了=剔）；exclude 无证据证明命中=保留（宁多不少）
            return includeSites.isEmpty();
        }
        for (String ex : excludeSites) {
            if (host.equals(ex) || host.endsWith("." + ex)) {
                return false;
            }
        }
        if (!includeSites.isEmpty()) {
            for (String in : includeSites) {
                if (host.equals(in) || host.endsWith("." + in)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    /** 取 url 主机名（小写）；非 http(s)/解析失败返回 null */
    static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return null;
            }
            String host = uri.getHost();
            return host == null ? null : host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }
}
