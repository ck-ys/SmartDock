package com.smartdock.analytics.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.analytics.engine.AnalyticsHealthService;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 引擎健康接口(批10 Q5-A): 四指标 + 越线告警项, 数据全部现算自现有记录表。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/analytics/health")
@RequiredArgsConstructor
public class AnalyticsHealthController {

    private final AnalyticsHealthService healthService;

    @SaCheckPermission("profile:health:query")
    @GetMapping
    public R<Map<String, Object>> health(@RequestParam String source) {
        return R.ok(healthService.health(source));
    }
}
