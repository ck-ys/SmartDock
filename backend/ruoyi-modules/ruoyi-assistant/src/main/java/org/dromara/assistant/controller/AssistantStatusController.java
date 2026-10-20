package org.dromara.assistant.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.domain.DcsAssistantConfig;
import org.dromara.assistant.limiter.AssistantDailyQuota;
import org.dromara.assistant.service.AssistantConfigService;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.assistant.tools.WebSearchTools;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 助手租户状态/配置端点（批G G1，spec §18）。
 *
 * <p>GET /dcs/assistant/status：悬浮球条件加载的数据源（enabled+modelConfigured+degraded
 * 三态 + 限额/剩余回合——原型 §10.1 统计面板与欢迎引导今日概况的后端数据源之一）。</p>
 */
@Tag(name = "助手租户配置", description = "租户三态感知 + 租户配置读/写")
@RestController
@RequestMapping("/dcs/assistant")
@RequiredArgsConstructor
public class AssistantStatusController extends BaseController {

    private final AssistantConfigService configService;
    private final AssistantDailyQuota dailyQuota;
    private final org.dromara.assistant.service.AssistantConversationService conversationService;
    private final org.dromara.assistant.service.AssistantUserQuotaService quotaService;
    /** 业务批3-④：联网已用次数真值源（此前占位 0） */
    private final org.dromara.assistant.limiter.AssistantWebSearchQuota webSearchQuota;
    /** 业务批7 Q7：今日缓存命中率聚合 */
    private final com.smartdock.service.support.AiBillingService aiBillingService;
    /** 业务反馈批1015b Q14：搜索 key 掩码读（写走参数设置/system config 自带缓存刷新，此处零写） */
    private final org.dromara.common.core.service.ConfigService sysConfigService;

    /** 租户三态感知（perm=dcs:assistant:use；批B B2：带 threadId 时附 contextTokens=
     *  该会话最新 assistant 消息的 total_tokens（末次模型调用 input+cached+output），
     *  前端合并环的上下文占用分子；无会话/未落库/估算缺真值=null=环空态）。
     *  业务批7：billingMode=credits 时附积分三本账快照 + 今日缓存命中率（Q7）。 */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/status")
    public R<Map<String, Object>> status(@RequestParam(required = false) String threadId) {
        String tenantId = LoginHelper.getTenantId();
        Long userId = LoginHelper.getUserId();
        AssistantConfigService.EffectiveConfig cfg = configService.effective(tenantId);
        // 业务批7 Q1：计费感知限额——订阅租户回合=UNLIMITED_ROUNDS、联网=套餐档位
        org.dromara.assistant.service.AssistantUserQuotaService.BillingAwareLimits limits =
            quotaService.billingAwareLimits(tenantId, userId);
        Map<String, Object> out = new LinkedHashMap<>();
        // 快刀批 Q6：助手称呼（租户可改，读侧两级默认合并后必有值）——前端 nameState 全局态数据源
        out.put("name", cfg.assistantName());
        out.put("enabled", cfg.enabled());
        out.put("modelConfigured", cfg.enabled() && configService.modelConfigured(tenantId));
        out.put("degraded", configService.degraded());
        out.put("billingMode", limits.creditsMode() ? "credits" : "wallet");
        // glm53 P2-3 回修：积分三本账快照字段全删——前端实际数据源=my-summary（有完整
        // windowCap/Used/tankBalance），此处字段零消费纯属死数据；billingMode 保留（前端消费）。
        out.put("dailyRoundLimitPerUser", limits.roundLimit());
        out.put("dailyBudgetAmount", cfg.dailyBudgetAmount());
        // 二站④ AU-53：记忆页按此判断空态（开=拉真数据，关=引导文案）
        out.put("memoryEnabled", "1".equals(cfg.memoryEnabled()));
        long used = dailyQuota.turnsUsed(tenantId, userId);
        out.put("turnsUsedToday", used);
        out.put("remainingTurnsToday", Math.max(0, limits.roundLimit() - used));
        // 业务批3-③(#16)：禁用用户前端显示横幅/提示
        // 业务批3-④(#17)：联网已用/上限真值（Redis 计数 + 三层合成上限），前端状态弹窗联网行
        out.put("quotaDisabled", limits.disabled());
        out.put("websearchUsedToday", webSearchQuota.searchesUsed(tenantId, userId));
        out.put("websearchLimit", limits.websearchLimit());
        out.put("contextTokens", contextTokensOf(tenantId, userId, threadId));
        // Q9（2026-10-15 拍板A·ZCode 同构）：本会话缓存命中（运行时逐回合累计，只含主链
        // 真实回传；换会话即重置、重启重新累计）。前端状态卡显示口径=本会话；
        // cacheHitRateToday 保留为管理/排错参考（含 Q1 脏数据护栏）。
        // 归属校验（三席审查 P2）：与 contextTokensOf 同款先查会话行，threadId 属本人
        // 才返回聚合——防拿他人/他租户 threadId 读走会话指标；非本人时前端显示「刚开了头」
        if (threadId != null && !threadId.isBlank()) {
            try {
                org.dromara.assistant.domain.DcsAssistantConversation conv =
                    conversationService.conversationByThread(tenantId, threadId);
                if (conv != null && userId.equals(conv.getUserId())) {
                    org.dromara.assistant.core.AssistantCacheHitHolder.Aggregate agg =
                        org.dromara.assistant.core.AssistantCacheHitHolder.aggregate(threadId);
                    out.put("sessionCacheHitRate", agg.ratePct());
                    out.put("sessionCacheHitRequests", agg.requests());
                }
            } catch (Exception e) {
                // 会话行查询失败按无数据处理，不影响状态接口其余字段
            }
        }
        // 业务批7 Q7：今日缓存命中率（LiteLLM 口径；估算行/侧车调用排除，样本<3 为 null）
        Map<String, Object> cacheHit = aiBillingService.assistantCacheHitToday(tenantId, userId);
        out.put("cacheHitRateToday", cacheHit.get("rate"));
        out.put("cacheHitSamples", cacheHit.get("samples"));
        return R.ok(out);
    }

    /** 批B B2：threadId → 本会话最新 assistant 消息末次调用 tokens（无会话/无值返回 null）。
     *  B1 修：取「最新 tokens 非空行」——truncated/中断行恒空，取最新行会被空值遮蔽 */
    private Long contextTokensOf(String tenantId, Long userId, String threadId) {
        if (threadId == null || threadId.isBlank()) {
            return null;
        }
        try {
            org.dromara.assistant.domain.DcsAssistantConversation conv =
                conversationService.conversationByThread(tenantId, threadId);
            if (conv == null) {
                return null;
            }
            return conversationService.latestContextTokens(tenantId, userId, conv.getId());
        } catch (Exception e) {
            return null;
        }
    }

    /** 管理页统计头（ZCode 对齐 Q4 批C）：全租户会话/消息/动作/反馈计数 + 本月费用（perm=config 同源） */
    @SaCheckPermission("dcs:assistant:config")
    @GetMapping("/admin/stats")
    public R<org.dromara.assistant.domain.vo.AssistantAdminStatsVo> adminStats() {
        return R.ok(configService.adminStats(LoginHelper.getTenantId()));
    }

    /** 租户配置读（perm=dcs:assistant:config；effective=两级默认合并结果，row=覆盖行原文） */
    @SaCheckPermission("dcs:assistant:config")
    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        String tenantId = LoginHelper.getTenantId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("effective", configService.effective(tenantId));
        out.put("row", configService.raw(tenantId));
        return R.ok(out);
    }

    /** 租户配置写（upsert；超管写任意租户须走跨租户端点=P2 登记项）；铁律#21：PUT→POST /config/edit（2026-10-16 批17 Q4，前端已同步） */
    @SaCheckPermission("dcs:assistant:config")
    @Log(title = "AI助手-租户配置", businessType = BusinessType.UPDATE)
    @PostMapping("/config/edit")
    public R<Void> updateConfig(@RequestBody DcsAssistantConfig body) {
        body.setTenantId(LoginHelper.getTenantId());
        configService.update(body);
        return R.ok();
    }

    /**
     * 业务反馈批1015b Q14：联网搜索配置读（perm=config，管理页卡片数据源）。
     * 安全口径：密钥只出掩码（前 4 后 4），明文永不出后端；平台开关状态一并返回供卡片展示。
     */
    @SaCheckPermission("dcs:assistant:config")
    @GetMapping("/admin/websearch")
    public R<Map<String, Object>> websearchInfo() {
        String tenantId = LoginHelper.getTenantId();
        String key = TenantHelper.dynamic(tenantId, () -> sysConfigService.getConfigValue(WebSearchTools.CONFIG_KEY));
        String trim = key == null ? "" : key.trim();
        boolean usable = !trim.isEmpty() && !"off".equalsIgnoreCase(trim) && !"disabled".equalsIgnoreCase(trim) && trim.startsWith("sk-");
        String mask = usable ? trim.substring(0, Math.min(6, trim.length())) + "****" + trim.substring(Math.max(0, trim.length() - 4)) : "";
        String sw = TenantHelper.dynamic("000000", () -> sysConfigService.getConfigValue(WebSearchTools.PLATFORM_DEFAULT_SWITCH_KEY));
        String swTrim = sw == null ? "" : sw.trim();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", usable);
        out.put("maskedKey", mask);
        out.put("placeholderHint", trim.isEmpty() ? "未配置" : usable ? "" : "已配置但为占位/停用值（off/disabled 或非 sk- 前缀）");
        out.put("platformDefault", "true".equalsIgnoreCase(swTrim) || "1".equals(swTrim));
        out.put("configKey", WebSearchTools.CONFIG_KEY);
        return R.ok(out);
    }

    /**
     * 业务反馈批1015b Q14：搜索连通测试（perm=config）。body.key 可选——带值=先验新钥匙再替换
     * （推荐流程），不带=用当前生效 key（含平台默认回退口径，与真实调用同解析）。
     * 打一次 count=1 的真请求量延迟；任何异常返回 ok=false + 人话原因，不抛 500。
     */
    @SaCheckPermission("dcs:assistant:config")
    @PostMapping("/admin/websearch/test")
    public R<Map<String, Object>> websearchTest(@RequestBody(required = false) Map<String, String> body) {
        String tenantId = LoginHelper.getTenantId();
        String candidate = body == null ? null : body.get("key");
        String key = candidate != null && !candidate.isBlank() ? candidate.trim() : null;
        if (key == null) {
            // 复用工具同一解析链（本租户 → 平台开关）——测试的就是真实调用会用的那把
            String saved = TenantHelper.dynamic(tenantId, () -> sysConfigService.getConfigValue(WebSearchTools.CONFIG_KEY));
            key = saved == null ? "" : saved.trim();
            if ((key.isEmpty() || "off".equalsIgnoreCase(key) || "disabled".equalsIgnoreCase(key) || !key.startsWith("sk-"))
                && !"000000".equals(tenantId)) {
                String sw = TenantHelper.dynamic("000000", () -> sysConfigService.getConfigValue(WebSearchTools.PLATFORM_DEFAULT_SWITCH_KEY));
                String swTrim = sw == null ? "" : sw.trim();
                if ("true".equalsIgnoreCase(swTrim) || "1".equals(swTrim)) {
                    String platform = TenantHelper.dynamic("000000", () -> sysConfigService.getConfigValue(WebSearchTools.CONFIG_KEY));
                    if (platform != null && platform.trim().startsWith("sk-")) {
                        key = platform.trim();
                    }
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        if (key == null || key.isEmpty() || !key.startsWith("sk-")) {
            out.put("ok", false);
            out.put("message", "没有可测试的密钥（未配置或占位值）");
            return R.ok(out);
        }
        long start = System.currentTimeMillis();
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofSeconds(5)).build();
            String reqBody = "{\"query\":\"connectivity test\",\"count\":1}";
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder()
                .uri(java.net.URI.create("https://api.bocha.cn/v1/web-search"))
                .timeout(java.time.Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(reqBody)).build();
            java.net.http.HttpResponse<String> resp = client.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            long latency = System.currentTimeMillis() - start;
            if (resp.statusCode() == 200) {
                out.put("ok", true);
                out.put("latencyMs", latency);
                out.put("message", "连通正常（" + latency + " ms）");
            } else {
                out.put("ok", false);
                out.put("message", switch (resp.statusCode()) {
                    case 401 -> "密钥无效（401）：请核对博查后台的 key";
                    case 403 -> "无权限（403）：key 可能已停用或套餐到期";
                    case 429 -> "触发限流（429）：稍后再试";
                    default -> "上游返回 " + resp.statusCode();
                });
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "请求失败：" + e.getMessage());
        }
        return R.ok(out);
    }
}