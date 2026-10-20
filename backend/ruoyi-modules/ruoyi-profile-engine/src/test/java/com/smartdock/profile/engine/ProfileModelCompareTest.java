package com.smartdock.profile.engine;

import com.smartdock.profile.engine.ProfileModelCompare.SubjectVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型版本对比纯函数测试（批9 拍板 Q1-A）。
 */
@Tag("local")
class ProfileModelCompareTest {

    private SubjectVersion v(String id, String level, String score) {
        return new SubjectVersion("user", id, "主体" + id, level, new BigDecimal(score));
    }

    @Test
    @SuppressWarnings("unchecked")
    void countsUpDownFlatAndMigration() {
        Map<String, Object> out = ProfileModelCompare.compare(
            List.of(v("s1", "高价值", "90"), v("s2", "高价值", "80"), v("s3", "中价值", "70"), v("s4", "低价值", "40")),
            List.of(v("s1", "高价值", "95"), v("s2", "中价值", "75"), v("s3", "中价值", "70"), v("s4", "低价值", "40")),
            20);
        Map<String, Object> delta = (Map<String, Object>) out.get("deltaStat");
        assertThat(delta.get("up")).isEqualTo(1L);
        assertThat(delta.get("down")).isEqualTo(1L);
        assertThat(delta.get("flat")).isEqualTo(2L);
        List<Map<String, Object>> migration = (List<Map<String, Object>>) out.get("migration");
        assertThat(migration).hasSize(1);
        assertThat(migration.get(0)).containsEntry("path", "高价值 → 中价值").containsEntry("count", 1L);
        assertThat(out.get("compared")).isEqualTo(4L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void oneSidedSubjectsAreCountedNotCompared() {
        Map<String, Object> out = ProfileModelCompare.compare(
            List.of(v("s1", "高价值", "90"), v("gone", "低价值", "10")),
            List.of(v("s1", "高价值", "90"), v("new", "高价值", "88")),
            20);
        Map<String, Object> delta = (Map<String, Object>) out.get("deltaStat");
        assertThat(delta.get("onlyInBase")).isEqualTo(1L);
        assertThat(delta.get("onlyInCand")).isEqualTo(1L);
        assertThat(delta.get("flat")).isEqualTo(1L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void topMoversSortedByAbsDelta() {
        Map<String, Object> out = ProfileModelCompare.compare(
            List.of(v("a", "高价值", "50"), v("b", "高价值", "90")),
            List.of(v("a", "低价值", "80"), v("b", "中价值", "88")),
            20);
        List<Map<String, Object>> movers = (List<Map<String, Object>>) out.get("topMovers");
        assertThat(movers).hasSize(2);
        // a 的 |+30| > b 的 |-2|, a 排前
        assertThat(movers.get(0).get("subjectId")).isEqualTo("a");
        assertThat(movers.get(0).get("delta")).isEqualTo(new BigDecimal("30.00"));
    }

    @Test
    void topNCapsMoverList() {
        Map<String, Object> out = ProfileModelCompare.compare(
            List.of(v("a", "A", "50"), v("b", "A", "50"), v("c", "A", "50"), v("d", "A", "50")),
            List.of(v("a", "A", "51"), v("b", "A", "52"), v("c", "A", "53"), v("d", "A", "54")),
            2);
        assertThat((List<?>) out.get("topMovers")).hasSize(2);
    }

    @Test
    void nullScoresCountAsFlat() {
        Map<String, Object> out = ProfileModelCompare.compare(
            List.of(new SubjectVersion("user", "s1", null, "高价值", null)),
            List.of(v("s1", "中价值", "70")),
            20);
        assertThat(String.valueOf(out.get("deltaStat"))).contains("flat=1");
    }

    @Test
    void levelDistributionCounts() {
        Map<String, Long> dist = ProfileModelCompare.levelDistribution(
            List.of(v("a", "高价值", "90"), v("b", "高价值", "80"), v("c", "中价值", "70")));
        assertThat(dist).containsEntry("高价值", 2L).containsEntry("中价值", 1L);
    }
}
