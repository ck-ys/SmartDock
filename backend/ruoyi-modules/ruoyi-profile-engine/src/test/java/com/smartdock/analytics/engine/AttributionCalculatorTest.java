package com.smartdock.analytics.engine;

import com.smartdock.analytics.engine.AttributionCalculator.Conversion;
import com.smartdock.analytics.engine.AttributionCalculator.Touch;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 渠道归因纯函数测试（批8 拍板 Q3-A：首次/末次/线性、转化前触点、无来源兜底）。
 */
@Tag("local")
class AttributionCalculatorTest {

    private static final long H = 3_600_000L;

    @Test
    @SuppressWarnings("unchecked")
    void firstLastLinearCreditsPerChannel() {
        // v1: baidu(早) → google(晚) 后注册; v2: baidu 后注册
        Map<String, Object> out = AttributionCalculator.attribute(
            List.of(new Touch("v1", 1 * H, "baidu"), new Touch("v1", 2 * H, "google"),
                    new Touch("v2", 3 * H, "baidu")),
            List.of(new Conversion("v1", 4 * H), new Conversion("v2", 5 * H)));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        Map<String, Map<String, Object>> byChannel = new java.util.HashMap<>();
        for (Map<String, Object> row : rows) {
            byChannel.put(String.valueOf(row.get("channel")), row);
        }
        assertThat(byChannel.get("baidu")).containsEntry("first", 2L).containsEntry("last", 1L);
        assertThat(byChannel.get("google")).containsEntry("first", 0L).containsEntry("last", 1L);
        // v1 线性: baidu 0.5 + v2: baidu 1.0 → baidu 1.5; google 0.5
        assertThat((Double) byChannel.get("baidu").get("linear")).isEqualTo(1.5);
        assertThat((Double) byChannel.get("google").get("linear")).isEqualTo(0.5);
        assertThat(out.get("conversions")).isEqualTo(2L);
    }

    @Test
    void touchpointsAfterConversionAreIgnored() {
        Map<String, Object> out = AttributionCalculator.attribute(
            List.of(new Touch("v1", 1 * H, "baidu"), new Touch("v1", 9 * H, "weibo")),
            List.of(new Conversion("v1", 2 * H)));
        assertThat(String.valueOf(out.get("rows"))).contains("baidu").doesNotContain("weibo");
    }

    @Test
    @SuppressWarnings("unchecked")
    void conversionWithoutTouchpointsFallsToNoChannel() {
        Map<String, Object> out = AttributionCalculator.attribute(
            List.of(new Touch("v2", 1, "baidu")),
            List.of(new Conversion("v1", 2)));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("channel", AttributionCalculator.NO_CHANNEL)
            .containsEntry("first", 1L).containsEntry("last", 1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void blankChannelIsNormalized() {
        Map<String, Object> out = AttributionCalculator.attribute(
            List.of(new Touch("v1", 1, "  ")),
            List.of(new Conversion("v1", 2)));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertThat(rows.get(0)).containsEntry("channel", AttributionCalculator.NO_CHANNEL);
    }

    @Test
    @SuppressWarnings("unchecked")
    void firstColumnSortedDescending() {
        Map<String, Object> out = AttributionCalculator.attribute(
            List.of(new Touch("v1", 1, "a"), new Touch("v2", 1, "b"), new Touch("v3", 1, "b")),
            List.of(new Conversion("v1", 2), new Conversion("v2", 2), new Conversion("v3", 2)));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("rows");
        assertThat(rows.get(0)).containsEntry("channel", "b").containsEntry("first", 2L);
        assertThat(rows.get(1)).containsEntry("channel", "a").containsEntry("first", 1L);
    }
}
