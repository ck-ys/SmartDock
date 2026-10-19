package com.smartdock.profile.model;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("local")
class ProfileModelConfigTest {

    private ProfileModelDefinition rfm() {
        ProfileModelDefinition def = new ProfileModelDefinition();
        def.setPreset("RFM");
        def.setDimensions(List.of(
            dim("R", "RECENCY_DAYS", "percentile", "lower"),
            dim("F", "COUNT", "percentile", "higher"),
            dim("M", "COUNT", "percentile", "higher")
        ));
        return def;
    }

    private ProfileModelDefinition.ModelDimension dim(String code, String type, String method, String direction) {
        ProfileModelDefinition.MetricCalc calc = new ProfileModelDefinition.MetricCalc();
        calc.setType(type);
        ProfileModelDefinition.BinSpec bins = new ProfileModelDefinition.BinSpec();
        bins.setMethod(method);
        bins.setDirection(direction);
        bins.setQuantiles(List.of(
            BigDecimal.valueOf(0.2), BigDecimal.valueOf(0.4),
            BigDecimal.valueOf(0.6), BigDecimal.valueOf(0.8)));
        bins.setScores(List.of(
            BigDecimal.ONE, BigDecimal.valueOf(2), BigDecimal.valueOf(3),
            BigDecimal.valueOf(4), BigDecimal.valueOf(5)));
        ProfileModelDefinition.ModelDimension d = new ProfileModelDefinition.ModelDimension();
        d.setCode(code);
        d.setName(code);
        d.setCalc(calc);
        d.setBins(bins);
        return d;
    }

    @Test
    void acceptsRfmPreset() {
        assertThat(ProfileModelConfig.validate(rfm())).isEmpty();
    }

    @Test
    void rejectsDuplicateDimensionCode() {
        ProfileModelDefinition def = rfm();
        def.getDimensions().get(1).setCode("R");
        assertThat(ProfileModelConfig.validate(def)).anyMatch(e -> e.contains("重复"));
    }

    @Test
    void rejectsFixedEdgesLengthMismatch() {
        ProfileModelDefinition def = rfm();
        ProfileModelDefinition.BinSpec bins = def.getDimensions().get(0).getBins();
        bins.setMethod("fixed");
        bins.setEdges(List.of(BigDecimal.ONE));
        assertThat(ProfileModelConfig.validate(def)).anyMatch(e -> e.contains("edges 长度"));
    }

    @Test
    void rejectsPercentileQuantileOrder() {
        ProfileModelDefinition def = rfm();
        def.getDimensions().get(0).getBins().setQuantiles(List.of(
            BigDecimal.valueOf(0.4), BigDecimal.valueOf(0.2),
            BigDecimal.valueOf(0.6), BigDecimal.valueOf(0.8)));
        assertThat(ProfileModelConfig.validate(def)).anyMatch(e -> e.contains("升序"));
    }

    @Test
    void rejectsMissingPropForSum() {
        ProfileModelDefinition def = rfm();
        def.getDimensions().get(2).getCalc().setType("SUM");
        assertThat(ProfileModelConfig.validate(def)).anyMatch(e -> e.contains(".prop"));
    }

    @Test
    void rejectsTopNWithoutN() {
        ProfileModelDefinition def = rfm();
        def.getDimensions().get(1).getBins().setMethod("topN");
        assertThat(ProfileModelConfig.validate(def)).anyMatch(e -> e.contains(".n"));
    }

    @Test
    void rejectsZeroWeightSum() {
        ProfileModelDefinition def = rfm();
        def.getDimensions().forEach(d -> d.setWeight(BigDecimal.ZERO));
        assertThat(ProfileModelConfig.validate(def)).anyMatch(e -> e.contains("权重合计"));
    }
}
