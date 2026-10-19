package com.smartdock.analytics.query;

import cn.dev33.satoken.annotation.SaIgnore;
import com.smartdock.analytics.engine.AnalyticsCollectorService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
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

    @PostMapping("/collect")
    public R<Map<String, Object>> collect(@RequestBody AnalyticsCollectBo bo) {
        return R.ok(collectorService.collect(bo));
    }
}
