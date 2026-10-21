package com.smartdock.analytics.query;

import cn.dev33.satoken.annotation.SaIgnore;
import com.smartdock.analytics.engine.AnalyticsCollectorService;
import com.smartdock.analytics.quality.CollectRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.core.utils.ServletUtils;
import org.dromara.common.ratelimiter.annotation.RateLimiter;
import org.dromara.common.ratelimiter.enums.LimitType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 公开采集口：浏览器 SDK 直接上报，不需要登录态。
 *
 * <p>安全边界：siteKey 只映射租户/业务源，不授予管理权限；
 * 事件大小与批量上限由服务层控制，敏感字段不落 IP（只存哈希）；
 * 机器人 UA 与非白名单来源整批拒绝并留痕（批 7 采集质量）。</p>
 *
 * @author smartdock
 */
@SaIgnore
@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class AnalyticsCollectController {

    private final AnalyticsCollectorService collectorService;

    /**
     * 公开口必须限流: 单 IP 每分钟 300 批(单批上限 200 条由服务层控制)。
     * SDK 默认 3 秒批量上报, 单页约 20 批/分钟; 300 兼顾企业 NAT/CGNAT 同出口多用户,
     * 同时防止匿名灌水持续触发整源重建。
     */
    @RateLimiter(time = 60, count = 300, limitType = LimitType.IP)
    @PostMapping("/collect")
    public R<Map<String, Object>> collect(@RequestBody String rawBody, HttpServletRequest request) {
        AnalyticsCollectBo bo;
        try {
            bo = JsonUtils.parseObject(rawBody, AnalyticsCollectBo.class);
        } catch (Exception e) {
            // 批11 回修(终审 P2): 公开口畸形报文回 400 语义, 不刷 500
            return R.fail(400, "报文格式非法");
        }
        // 批11 Q3: 签名三头 + 原始报文体一起进上下文, 验签覆盖整包(改动报文即失效)
        CollectRequestContext ctx = new CollectRequestContext(
            ServletUtils.getHeader(request, "User-Agent"),
            ServletUtils.getHeader(request, "Origin"),
            ServletUtils.getHeader(request, "Referer"),
            ServletUtils.getClientIP(),
            ServletUtils.getHeader(request, "X-SD-Timestamp"),
            ServletUtils.getHeader(request, "X-SD-Nonce"),
            ServletUtils.getHeader(request, "X-SD-Signature"));
        return R.ok(collectorService.collect(bo, ctx, rawBody));
    }
}
