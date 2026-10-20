package com.smartdock.profile.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PSI 稳定性纯函数测试（批9 拍板 Q6-A 风暴加固）。
 */
@Tag("local")
class ProfilePsiTest {

    private Map<String, Long> dist(Object... kv) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            out.put((String) kv[i], ((Number) kv[i + 1]).longValue());
        }
        return out;
    }

    @Test
    void identicalDistributionsAreStable() {
        Map<String, Object> psi = ProfilePsi.of(
            dist("高价值", 10, "中价值", 20, "低价值", 10),
            dist("高价值", 10, "中价值", 20, "低价值", 10));
        assertThat(((BigDecimal) psi.get("psi")).doubleValue()).isLessThan(0.0001);
        assertThat(psi.get("verdict")).isEqualTo(ProfilePsi.STABLE);
    }

    @Test
    void moderateShiftLandsInMiddleBand() {
        // 高价值 40% → 25%、低价值 20% → 35%: PSI≈0.154 落中带
        Map<String, Object> psi = ProfilePsi.of(
            dist("高价值", 8, "中价值", 8, "低价值", 4),
            dist("高价值", 5, "中价值", 8, "低价值", 7));
        double v = ((BigDecimal) psi.get("psi")).doubleValue();
        assertThat(v).isBetween(0.1, 0.25);
        assertThat(psi.get("verdict")).isEqualTo(ProfilePsi.MODERATE);
    }

    @Test
    void drasticShiftIsFlagged() {
        Map<String, Object> psi = ProfilePsi.of(
            dist("高价值", 100),
            dist("低价值", 100));
        assertThat(((BigDecimal) psi.get("psi")).doubleValue()).isGreaterThan(0.25);
        assertThat(psi.get("verdict")).isEqualTo(ProfilePsi.SHIFTED);
    }

    @Test
    void emptyInputYieldsZeroPsiWithoutCrash() {
        Map<String, Object> psi = ProfilePsi.of(Map.of(), null);
        assertThat(((BigDecimal) psi.get("psi")).doubleValue()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void distributionMarksSmallSamples() {
        List<Map<String, Object>> rows = ProfilePsi.distribution(
            dist("高价值", 100),
            dist("高价值", 80, "中价值", 25));
        Map<String, Object> mid = rows.stream().filter(r -> "中价值".equals(r.get("level"))).findFirst().orElseThrow();
        assertThat(mid.get("smallSample")).isEqualTo(true);
        Map<String, Object> high = rows.stream().filter(r -> "高价值".equals(r.get("level"))).findFirst().orElseThrow();
        assertThat(high.get("smallSample")).isEqualTo(false);
        assertThat(((Number) high.get("delta")).longValue()).isEqualTo(-20L);
    }
}
