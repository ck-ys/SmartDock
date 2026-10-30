package com.smartdock.analytics.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题4 站内告警人话化：越线文案不许再甩裸毫秒（2026-10-30）。
 *
 * <p>现网旧证据：「P95 53255011ms」——8 位毫秒没人能秒算成 53 秒，告警因此被无视。
 * {@link #alerts} 是页面红标与站内通知共用的唯一来源，这里钉住它的话术。</p>
 */
@Tag("local")
class AnalyticsHealthAlertTextTest {

    private final AnalyticsHealthService service = new AnalyticsHealthService(null, null, null, null);

    @Test
    void latencyAndRebuildMessagesSpeakHuman() {
        List<Map<String, Object>> alerts = service.alerts(
            Map.of("p95", 53_300.0),       // 53.3 秒延迟
            Map.of("p95", 125_000.0),      // 重建 2 分 5 秒
            BigDecimal.ZERO, 0L);

        assertThat(alerts).extracting(a -> a.get("message"))
            .containsExactly(
                // 实测值近似带「约」，阈值 10 秒/1 分是精确值不带（三审 P2：阈值写成「约」会被读成浮动）
                "事件延迟 P95 约 53.3 秒，超过阈值 10 秒",
                "重建耗时 P95 2 分 5 秒，超过阈值 1 分");
        // 机器值仍保留（页面画表/红标要用）
        assertThat(alerts.get(0).get("value")).isEqualTo("53300.0");
        // 现网真出现过 53255011ms（约 14.8 小时）——超大值同样不能退化成裸毫秒
        assertThat(service.alerts(Map.of("p95", 53_255_011.0), Map.of(), BigDecimal.ZERO, 0L))
            .extracting(a -> a.get("message"))
            .containsExactly("事件延迟 P95 约 14.8 小时，超过阈值 10 秒");
    }

    @Test
    void backlogAlertCarriesUnit() {
        assertThat(service.alerts(Map.of(), Map.of(), BigDecimal.ZERO, 5_000L))
            .extracting(a -> a.get("message"))
            .containsExactly("去抖队列积压 5000 条 超过阈值 1000 条");
    }

    @Test
    void underThresholdStaysSilent() {
        assertThat(service.alerts(Map.of("p95", 12.0), Map.of("p95", 300.0), BigDecimal.ZERO, 0L)).isEmpty();
    }
}
