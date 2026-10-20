package com.smartdock.analytics.engine;

import com.smartdock.analytics.engine.AnalyticsPathBuilder.PathEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路径聚合纯函数测试（批8 拍板 Q1-A/Q7-A：首次顺序、连续重复页折叠、起点截取、按步分层防环）。
 */
@Tag("local")
class AnalyticsPathBuilderTest {

    private PathEvent e(String visitor, long minute, String page) {
        return new PathEvent(visitor, minute * 60_000L, page);
    }

    @Test
    @SuppressWarnings("unchecked")
    void countsTransitionsPerVisitorOrder() {
        Map<String, Object> out = AnalyticsPathBuilder.build(List.of(
            e("v1", 0, "/home"), e("v1", 1, "/docs"), e("v1", 2, "/pricing"),
            e("v2", 0, "/home"), e("v2", 5, "/docs"),
            e("v3", 0, "/search"), e("v3", 9, "/pricing")), null, 3, 30);
        List<Map<String, Object>> links = (List<Map<String, Object>>) out.get("links");
        long homeDocs = links.stream()
            .filter(l -> "/home".equals(l.get("source")) && "/docs·2".equals(l.get("target")))
            .mapToLong(l -> (Long) l.get("value")).sum();
        long docsPricing = links.stream()
            .filter(l -> "/docs·2".equals(l.get("source")) && "/pricing·3".equals(l.get("target")))
            .mapToLong(l -> (Long) l.get("value")).sum();
        assertThat(homeDocs).isEqualTo(2);
        assertThat(docsPricing).isEqualTo(1);
        assertThat((Long) out.get("visitors")).isEqualTo(3);
    }

    @Test
    void collapsesConsecutiveDuplicatePages() {
        Map<String, Object> out = AnalyticsPathBuilder.build(List.of(
            e("v1", 0, "/home"), e("v1", 1, "/home"), e("v1", 2, "/home"), e("v1", 3, "/docs")), null, 3, 30);
        String dump = String.valueOf(out.get("links"));
        assertThat(dump).contains("/home").contains("/docs");
        // 重复页折叠后 home→home 自环不允许出现
        assertThat(dump).doesNotContain("target=/home, value=");
        assertThat(dump).doesNotContain("target=/home·2, value=");
    }

    @Test
    void revisitProducesLayeredNodesWithoutCycle() {
        Map<String, Object> out = AnalyticsPathBuilder.build(List.of(
            e("v1", 0, "/home"), e("v1", 1, "/docs"), e("v1", 2, "/home"),
            e("v2", 0, "/docs"), e("v2", 1, "/home")), null, 3, 30);
        String dump = String.valueOf(out.get("links"));
        // 回访环 v1(home→docs→home) 与对向边 v2(docs→home) 并存时仍必须是 DAG:
        // 同一页在不同步是不同节点(/home 与 /home·3、/docs 与 /docs·2), 环不可能闭合
        assertThat(dump).contains("source=/home, target=/docs·2")
            .contains("source=/docs·2, target=/home·3")
            .contains("source=/docs, target=/home·2");
        // 没有任何边指回"第 1 步裸名节点"——分层后环在结构上不可能出现
        assertThat(dump).doesNotContain("target=/docs,").doesNotContain("target=/home,");
    }

    @Test
    @SuppressWarnings("unchecked")
    void entryPerspectiveCountsFirstPageOnly() {
        Map<String, Object> out = AnalyticsPathBuilder.build(List.of(
            e("v1", 0, "/home"), e("v1", 1, "/docs"),
            e("v2", 0, "/home"),
            e("v3", 0, "/blog"), e("v3", 1, "/home")), null, 3, 30);
        List<Map<String, Object>> entries = (List<Map<String, Object>>) out.get("entries");
        assertThat(entries.get(0)).containsEntry("page", "/home").containsEntry("visitors", 2L);
        assertThat(entries).hasSize(2);
    }

    @Test
    void startPageSlicesFromFirstOccurrence() {
        Map<String, Object> out = AnalyticsPathBuilder.build(List.of(
            e("v1", 0, "/home"), e("v1", 1, "/docs"), e("v1", 2, "/pricing"),
            e("v2", 0, "/blog")), "/docs", 2, 30);
        String dump = String.valueOf(out.get("links"));
        // v1 从 /docs 截取: docs→pricing 保留, home 不再作为入口
        assertThat(dump).contains("/pricing");
        assertThat(String.valueOf(out.get("entries"))).doesNotContain("/home");
        // v2 没走过 /docs, 整个访客跳过
        assertThat(dump).doesNotContain("/blog");
    }

    @Test
    void depthLimitsPathLength() {
        Map<String, Object> out = AnalyticsPathBuilder.build(List.of(
            e("v1", 0, "/a"), e("v1", 1, "/b"), e("v1", 2, "/c"), e("v1", 3, "/d")), null, 3, 30);
        String dump = String.valueOf(out.get("links"));
        assertThat(dump).contains("/b").contains("/c").doesNotContain("/d");
    }

    @Test
    void blankPageAndNullRowsAreSkipped() {
        Map<String, Object> out = AnalyticsPathBuilder.build(java.util.Arrays.asList(
            null, e("v1", 0, "  "), e("v1", 1, "/ok")), null, 3, 30);
        assertThat((Long) out.get("visitors")).isEqualTo(1);
    }
}
