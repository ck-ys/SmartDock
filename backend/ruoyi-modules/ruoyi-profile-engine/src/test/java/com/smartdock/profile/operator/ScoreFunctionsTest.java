package com.smartdock.profile.operator;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 打分映射纯函数单测(V1): linear/bucket/钳位/方向/退化区间。
 *
 * @author smartdock
 */
@Tag("local")
class ScoreFunctionsTest {

    private static ScoreSpec linearSpec(String lo, String hi, String direction) {
        ScoreSpec s = new ScoreSpec();
        s.setType("linear");
        s.setLo(new BigDecimal(lo));
        s.setHi(new BigDecimal(hi));
        s.setDirection(direction);
        return s;
    }

    private static final BigDecimal MAX = BigDecimal.valueOf(100);

    @Test
    void linearHigherMapsRatioLinearly() {
        // 完成率 0→0 分, 1→100 分, 0.83→83
        assertThat(ScoreFunctions.linear(new BigDecimal("0.83"), linearSpec("0", "1", "higher"), MAX))
            .isEqualByComparingTo(new BigDecimal("83.00"));
    }

    @Test
    void linearLowerInvertsDirection() {
        // 响应时效: 0h→100 分, 72h→0 分, 18h→75
        assertThat(ScoreFunctions.linear(new BigDecimal("18"), linearSpec("0", "72", "lower"), MAX))
            .isEqualByComparingTo(new BigDecimal("75.00"));
    }

    @Test
    void linearClampsOutOfRange() {
        // 比率 1.4 超 hi → 钳 100; 负值 → 钳 0
        assertThat(ScoreFunctions.linear(new BigDecimal("1.4"), linearSpec("0", "1", "higher"), MAX))
            .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(ScoreFunctions.linear(new BigDecimal("-3"), linearSpec("0", "1", "higher"), MAX))
            .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void linearDegenerateRangeGivesFullOrZero() {
        // hi == lo: 达到 hi 满分, 否则 0
        assertThat(ScoreFunctions.linear(new BigDecimal("5"), linearSpec("5", "5", "higher"), MAX))
            .isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(ScoreFunctions.linear(new BigDecimal("4.9"), linearSpec("5", "5", "higher"), MAX))
            .isEqualByComparingTo(new BigDecimal("0.00"));
    }

    @Test
    void bucketHitsFirstMatchInOrder() {
        // negative 口径: 0→100, 1-2→70, 3-5→40, 6+→0
        ScoreSpec spec = new ScoreSpec();
        spec.setType("bucket");
        spec.setBuckets(List.of(
            bucket("0", "100"), bucket("2", "70"), bucket("5", "40"), bucket(null, "0")));
        assertThat(ScoreFunctions.bucket(BigDecimal.ZERO, spec, MAX)).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(ScoreFunctions.bucket(BigDecimal.valueOf(2), spec, MAX)).isEqualByComparingTo(new BigDecimal("70"));
        assertThat(ScoreFunctions.bucket(BigDecimal.valueOf(4), spec, MAX)).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(ScoreFunctions.bucket(BigDecimal.valueOf(9), spec, MAX)).isEqualByComparingTo(new BigDecimal("0"));
    }

    @Test
    void bucketEmptyFallsBackNeutral() {
        ScoreSpec spec = new ScoreSpec();
        spec.setType("bucket");
        assertThat(ScoreFunctions.bucket(BigDecimal.TEN, spec, MAX))
            .isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void mapDispatchesByType() {
        assertThat(ScoreFunctions.map(new BigDecimal("0.5"), linearSpec("0", "1", "higher"), MAX))
            .isEqualByComparingTo(new BigDecimal("50.00"));
    }

    @Test
    void mapRejectsNullMetricOrScoreMax() {
        assertThatThrownBy(() -> ScoreFunctions.map(null, linearSpec("0", "1", "higher"), MAX))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ScoreFunctions.map(BigDecimal.ONE, linearSpec("0", "1", "higher"), null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    private static ScoreSpec.Bucket bucket(String lte, String score) {
        ScoreSpec.Bucket b = new ScoreSpec.Bucket();
        b.setLte(lte == null ? null : new BigDecimal(lte));
        b.setScore(new BigDecimal(score));
        return b;
    }
}
