package com.smartdock.profile.model;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("local")
class ProfileBinResolverTest {

    private ProfileModelDefinition.BinSpec fixed(String direction) {
        ProfileModelDefinition.BinSpec spec = new ProfileModelDefinition.BinSpec();
        spec.setMethod("fixed");
        spec.setDirection(direction);
        spec.setEdges(List.of(BigDecimal.valueOf(10), BigDecimal.valueOf(20)));
        spec.setScores(List.of(BigDecimal.ONE, BigDecimal.valueOf(3), BigDecimal.valueOf(5)));
        return spec;
    }

    private ProfileModelDefinition.BinSpec percentile(String direction) {
        ProfileModelDefinition.BinSpec spec = new ProfileModelDefinition.BinSpec();
        spec.setMethod("percentile");
        spec.setDirection(direction);
        spec.setQuantiles(List.of(
            BigDecimal.valueOf(0.25), BigDecimal.valueOf(0.5), BigDecimal.valueOf(0.75)));
        spec.setScores(List.of(BigDecimal.ONE, BigDecimal.valueOf(2), BigDecimal.valueOf(3), BigDecimal.valueOf(4)));
        return spec;
    }

    @Test
    void fixedHigherMapsAscending() {
        var pop = ProfileBinResolver.Population.of(List.of(BigDecimal.ONE));
        assertThat(ProfileBinResolver.resolve(fixed("higher"), BigDecimal.valueOf(5), pop).getScore())
            .isEqualByComparingTo("1");
        assertThat(ProfileBinResolver.resolve(fixed("higher"), BigDecimal.valueOf(15), pop).getScore())
            .isEqualByComparingTo("3");
        assertThat(ProfileBinResolver.resolve(fixed("higher"), BigDecimal.valueOf(30), pop).getScore())
            .isEqualByComparingTo("5");
    }

    @Test
    void fixedLowerReversesScores() {
        var pop = ProfileBinResolver.Population.of(List.of(BigDecimal.ONE));
        assertThat(ProfileBinResolver.resolve(fixed("lower"), BigDecimal.valueOf(5), pop).getScore())
            .isEqualByComparingTo("5");
        assertThat(ProfileBinResolver.resolve(fixed("lower"), BigDecimal.valueOf(30), pop).getScore())
            .isEqualByComparingTo("1");
    }

    @Test
    void percentileEdgesComeFromPopulation() {
        var pop = ProfileBinResolver.Population.of(List.of(
            BigDecimal.valueOf(1), BigDecimal.valueOf(2), BigDecimal.valueOf(3),
            BigDecimal.valueOf(4), BigDecimal.valueOf(5)));
        var low = ProfileBinResolver.resolve(percentile("higher"), BigDecimal.ONE, pop);
        var high = ProfileBinResolver.resolve(percentile("higher"), BigDecimal.valueOf(5), pop);
        assertThat(low.getScore()).isEqualByComparingTo("1");
        assertThat(high.getScore()).isEqualByComparingTo("4");
        assertThat(low.getEdges()).hasSize(3);
    }

    @Test
    void percentileDegeneratePopulationUsesMiddle() {
        var pop = ProfileBinResolver.Population.of(List.of(
            BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE));
        var result = ProfileBinResolver.resolve(percentile("higher"), BigDecimal.ONE, pop);
        assertThat(result.getScore()).isEqualByComparingTo("3");
    }

    @Test
    void meanUsesMeanAndStd() {
        ProfileModelDefinition.BinSpec spec = new ProfileModelDefinition.BinSpec();
        spec.setMethod("mean");
        spec.setBands(5);
        spec.setScores(List.of(BigDecimal.ONE, BigDecimal.valueOf(2), BigDecimal.valueOf(3),
            BigDecimal.valueOf(4), BigDecimal.valueOf(5)));
        var pop = ProfileBinResolver.Population.of(List.of(
            BigDecimal.valueOf(10), BigDecimal.valueOf(20), BigDecimal.valueOf(30),
            BigDecimal.valueOf(40), BigDecimal.valueOf(50)));
        // mean=30, std≈14.14 → edges ≈ [8.79, 22.93, 37.07, 51.21]
        assertThat(ProfileBinResolver.resolve(spec, BigDecimal.valueOf(30), pop).getScore())
            .isEqualByComparingTo("3");
        assertThat(ProfileBinResolver.resolve(spec, BigDecimal.valueOf(60), pop).getScore())
            .isEqualByComparingTo("5");
    }

    @Test
    void topNRanksBestFirst() {
        ProfileModelDefinition.BinSpec spec = new ProfileModelDefinition.BinSpec();
        spec.setMethod("topN");
        spec.setDirection("higher");
        spec.setN(2);
        spec.setScores(List.of(BigDecimal.valueOf(5), BigDecimal.valueOf(4), BigDecimal.valueOf(3)));
        var pop = ProfileBinResolver.Population.of(List.of(
            BigDecimal.valueOf(1), BigDecimal.valueOf(2), BigDecimal.valueOf(3),
            BigDecimal.valueOf(4), BigDecimal.valueOf(5)));
        assertThat(ProfileBinResolver.resolve(spec, BigDecimal.valueOf(5), pop).getScore())
            .isEqualByComparingTo("5");
        assertThat(ProfileBinResolver.resolve(spec, BigDecimal.valueOf(4), pop).getScore())
            .isEqualByComparingTo("5");
        assertThat(ProfileBinResolver.resolve(spec, BigDecimal.valueOf(3), pop).getScore())
            .isEqualByComparingTo("4");
        assertThat(ProfileBinResolver.resolve(spec, BigDecimal.valueOf(1), pop).getScore())
            .isEqualByComparingTo("3");
    }

    @Test
    void missingValueUsesConfiguredScore() {
        ProfileModelDefinition.BinSpec spec = fixed("higher");
        spec.setMissingScore(BigDecimal.ZERO);
        assertThat(ProfileBinResolver.resolve(spec, null, ProfileBinResolver.Population.of(List.of()))
            .getScore()).isEqualByComparingTo("0");
    }

    @Test
    void quantileInterpolatesLinearly() {
        List<BigDecimal> sorted = List.of(
            BigDecimal.valueOf(10), BigDecimal.valueOf(20),
            BigDecimal.valueOf(30), BigDecimal.valueOf(40), BigDecimal.valueOf(50));
        assertThat(ProfileBinResolver.quantile(sorted, 0.5)).isEqualByComparingTo("30");
        assertThat(ProfileBinResolver.quantile(sorted, 0.25)).isEqualByComparingTo("20");
        assertThat(ProfileBinResolver.quantile(sorted, 0.75)).isEqualByComparingTo("40");
        assertThat(ProfileBinResolver.quantile(sorted, 0.1)).isEqualByComparingTo("14");
    }
}
