package com.smartdock.analytics.assistant;

import com.smartdock.analytics.domain.AnalyticsFunnel;
import com.smartdock.assistant.spi.BizToolSpec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「问数据」批·题2A/题3A 口径钉死:
 * ①六工具齐全、全只读、全挂 analytics:center:query、描述里带口径护栏句;
 * ②参数归一（天数夹取/枚举白名单, 越权组查直接拒绝的根）;
 * ③图表协议字段全部来自真数结构（chart 照抄=图里无模型编数空间）。
 */
@Tag("local")
class AnalyticsToolProviderTest {

    private static Map<String, Object> row(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }

    @Test
    void sixToolsAllReadOnlySamePermissionWithGuardrail() {
        AnalyticsToolProvider provider = new AnalyticsToolProvider(null);
        List<BizToolSpec> tools = provider.tools();
        assertThat(tools).extracting(BizToolSpec::getName).containsExactlyInAnyOrder(
            "analytics_overview", "analytics_trend", "analytics_funnel",
            "analytics_retention", "analytics_paths", "analytics_custom");
        for (BizToolSpec t : tools) {
            assertThat(t.isReadOnly()).as("只读: " + t.getName()).isTrue();
            assertThat(t.getRequiredPermission()).as("权限同源: " + t.getName())
                .isEqualTo("analytics:center:query");
            assertThat(t.getDescription()).as("护栏句必带: " + t.getName())
                .contains("禁止自己对工具数字做算术");
        }
        // 自定义组查的描述必须把白名单写明白（模型侧第二道门）
        BizToolSpec custom = tools.stream().filter(t -> t.getName().equals("analytics_custom")).findFirst().orElseThrow();
        assertThat(custom.getDescription()).contains("channel").contains("uv");
    }

    @Test
    void clampDaysNullIllegalOutOfRange() {
        assertThat(AnalyticsToolProvider.clampDays(null, 7, 90)).isEqualTo(7);
        assertThat(AnalyticsToolProvider.clampDays(0, 7, 90)).isEqualTo(1);
        assertThat(AnalyticsToolProvider.clampDays(-5, 7, 90)).isEqualTo(1);
        assertThat(AnalyticsToolProvider.clampDays(365, 7, 90)).isEqualTo(90);
        assertThat(AnalyticsToolProvider.clampDays(14, 7, 90)).isEqualTo(14);
    }

    @Test
    void normalizeEnumBlankDefaultIllegalNull() {
        assertThat(AnalyticsToolProvider.normalizeEnum(null, AnalyticsToolProvider.CUSTOM_METRICS, "uv")).isEqualTo("uv");
        assertThat(AnalyticsToolProvider.normalizeEnum("  ", AnalyticsToolProvider.CUSTOM_METRICS, "uv")).isEqualTo("uv");
        assertThat(AnalyticsToolProvider.normalizeEnum(" UV ", AnalyticsToolProvider.CUSTOM_METRICS, "uv")).isEqualTo("uv");
        // 越权组查的根: 白名单外一律 null（调用方人话拒绝）
        assertThat(AnalyticsToolProvider.normalizeEnum("user_name", AnalyticsToolProvider.CUSTOM_DIMENSIONS, "day")).isNull();
        assertThat(AnalyticsToolProvider.normalizeEnum("day' or 1=1", AnalyticsToolProvider.CUSTOM_DIMENSIONS, "day")).isNull();
    }

    @Test
    void customDimensionWhitelistMatchesAnalyticsCenter() {
        // 与分析中心 AnalyticsCustomBo 注释同源的 11 维度（枚举漂移=组查口径漂移）
        assertThat(AnalyticsToolProvider.CUSTOM_DIMENSIONS).containsExactlyInAnyOrder(
            "day", "page", "channel", "device", "browser", "os", "geo", "entry", "exit", "search", "event");
        assertThat(AnalyticsToolProvider.CUSTOM_METRICS).containsExactlyInAnyOrder("pv", "uv", "visits", "events");
    }

    @Test
    void trendChartTakesLastTwelveWithShortDates() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int d = 1; d <= 20; d++) {
            rows.add(row("date", String.format("2026-10-%02d", d), "uv", d));
        }
        Map<String, Object> chart = AnalyticsToolProvider.buildTrendChart(rows, "uv", 12);
        assertThat(chart.get("type")).isEqualTo("line");
        assertThat(chart.get("title")).isEqualTo("近 12 天活跃访客");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) chart.get("data");
        assertThat(data).hasSize(12);
        // 只取最后 12 天（10-09..10-20），日期去掉年份
        assertThat(data.get(0).get("name")).isEqualTo("10-09");
        assertThat(data.get(11).get("name")).isEqualTo("10-20");
        assertThat(data.get(11).get("value")).isEqualTo(20L);
    }

    @Test
    void trendSumAndAvgAreBackendComputed() {
        // 口径护栏锚点（审查席：原版只测了 numberOrZero 是同义反复）——真调生产聚合逻辑：
        // 与 trend() 内同款公式，sum/avgPerDay 必须由这段算出钉死（模型只许引用不许算）
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(row("date", "2026-10-28", "uv", 4));
        rows.add(row("date", "2026-10-29", "uv", 0));
        rows.add(row("date", "2026-10-30", "uv", 7));
        Map<String, Object> agg = AnalyticsToolProvider.aggregate(rows, "uv");
        assertThat(agg.get("sum")).isEqualTo(11L);
        assertThat(agg.get("avgPerDay")).isEqualTo(3.7);
        assertThat(agg.get("days")).isEqualTo(3);
        // 空/单点/全 0 边界
        assertThat(AnalyticsToolProvider.aggregate(new ArrayList<>(), "uv").get("avgPerDay")).isEqualTo(0.0);
        assertThat(AnalyticsToolProvider.aggregate(List.of(row("uv", 0)), "uv").get("sum")).isEqualTo(0L);
    }

    @Test
    void funnelChartMapsStepNameAndCount() {
        List<Map<String, Object>> steps = List.of(
            row("name", "登录", "count", 12, "rate", "100.00"),
            row("name", "填表", "count", 8, "rate", "66.67"));
        Map<String, Object> chart = AnalyticsToolProvider.buildFunnelChart(steps);
        assertThat(chart.get("type")).isEqualTo("funnel");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> data = (List<Map<String, Object>>) chart.get("data");
        assertThat(data).hasSize(2);
        assertThat(data.get(0)).containsOnlyKeys("name", "value").containsEntry("name", "登录").containsEntry("value", 12L);
    }

    @Test
    void customChartDayLineOthersBar() {
        List<Map<String, Object>> points = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            points.add(row("label", "2026-10-" + i, "value", i));
        }
        Map<String, Object> line = AnalyticsToolProvider.buildCustomChart("day", points, 12);
        assertThat(line.get("type")).isEqualTo("line");
        assertThat(String.valueOf(line.get("title"))).contains("按日期");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lineData = (List<Map<String, Object>>) line.get("data");
        assertThat(lineData).hasSize(12);
        // 折线取最后 12 点（近段），柱取前 12 项（服务端已按量排序）——方向钉死防换向回归
        assertThat(lineData.get(11)).containsEntry("name", "2026-10-15");

        Map<String, Object> bar = AnalyticsToolProvider.buildCustomChart("channel", points, 12);
        assertThat(bar.get("type")).isEqualTo("bar");
        assertThat(String.valueOf(bar.get("title"))).contains("按渠道");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> barData = (List<Map<String, Object>>) bar.get("data");
        assertThat(barData).hasSize(12);
        assertThat(barData.get(0)).containsEntry("name", "2026-10-1");
    }

    @Test
    void retentionChartWhitelistsCellFields() {
        Map<String, Object> retention = row(
            "window", 7,
            "cohorts", List.of(row(
                "cohort", "2026-10-27", "total", 3,
                "cells", List.of(
                    row("day", 0, "visitors", 3, "rate", "100.00"),
                    row("day", 1, "visitors", 1, "rate", "33.33")))));
        Map<String, Object> chart = AnalyticsToolProvider.buildRetentionChart("day", retention);
        assertThat(chart.get("type")).isEqualTo("retention");
        // 窗口数取服务端值（retention map 里的 window），单位词随粒度
        assertThat(String.valueOf(chart.get("title"))).contains("按天").contains("7");
        assertThat(chart.get("unit")).isEqualTo("按天");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cohorts = (List<Map<String, Object>>) chart.get("cohorts");
        assertThat(cohorts).hasSize(1);
        assertThat(cohorts.get(0)).containsOnlyKeys("cohort", "total", "cells");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cells = (List<Map<String, Object>>) cohorts.get(0).get("cells");
        assertThat(cells.get(0)).containsOnlyKeys("day", "visitors");
    }

    @Test
    void sankeyChartSkipsBrokenLinks() {
        Map<String, Object> paths = row(
            "nodes", List.of(row("name", "首页", "extra", "drop"), row("name", "任务中心")),
            "links", List.of(
                row("source", "首页", "target", "任务中心", "value", 5),
                row("source", null, "target", "任务中心", "value", 1)));
        Map<String, Object> chart = AnalyticsToolProvider.buildSankeyChart(paths);
        assertThat(chart.get("type")).isEqualTo("sankey");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) chart.get("nodes");
        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0)).containsOnlyKeys("name");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> links = (List<Map<String, Object>>) chart.get("links");
        assertThat(links).hasSize(1);
        assertThat(links.get(0)).containsEntry("source", "首页").containsEntry("target", "任务中心").containsEntry("value", 5L);
    }

    @Test
    void resolveFunnelByIdExactNameThenContains() {
        AnalyticsFunnel a = new AnalyticsFunnel();
        a.setFunnelId(101L);
        a.setFunnelName("新同事上手");
        AnalyticsFunnel b = new AnalyticsFunnel();
        b.setFunnelId(202L);
        b.setFunnelName("数据填报");

        List<AnalyticsFunnel> all = List.of(a, b);
        assertThat(AnalyticsToolProvider.resolveFunnel(all, "101").getFunnelId()).isEqualTo(101L);
        assertThat(AnalyticsToolProvider.resolveFunnel(all, "202").getFunnelId()).isEqualTo(202L);
        assertThat(AnalyticsToolProvider.resolveFunnel(all, "新同事上手").getFunnelId()).isEqualTo(101L);
        // ID 未命中落名称包含兜底（名字叫 2024 的分群同款先例）
        assertThat(AnalyticsToolProvider.resolveFunnel(all, "上手").getFunnelId()).isEqualTo(101L);
        assertThat(AnalyticsToolProvider.resolveFunnel(all, "999")).isNull();
        assertThat(AnalyticsToolProvider.resolveFunnel(all, "不存在")).isNull();
    }

    @Test
    void shortDateStripsYearOnlyForIsoFormat() {
        assertThat(AnalyticsToolProvider.shortDate("2026-11-02")).isEqualTo("11-02");
        assertThat(AnalyticsToolProvider.shortDate("首页")).isEqualTo("首页");
        assertThat(AnalyticsToolProvider.shortDate(null)).isNull();
    }
}
