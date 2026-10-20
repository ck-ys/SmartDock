package com.smartdock.analytics.query;

import cn.dev33.satoken.annotation.SaIgnore;
import com.smartdock.analytics.engine.AnalyticsCollectorService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
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
 * 事件大小与批量上限由服务层控制，敏感字段不落 IP。</p>
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
     * 公开口必须限流: 单 IP 每分钟 120 批(单批上限 200 条由服务层控制),
     * 否则可被灌水打满事件表并持续触发整源重建。
     */
    @RateLimiter(time = 60, count = 120, limitType = LimitType.IP)
    @PostMapping("/collect")
    public R<Map<String, Object>> collect(@RequestBody AnalyticsCollectBo bo) {
        return R.ok(collectorService.collect(bo));
    }
}
