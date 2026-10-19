package com.smartdock.risk;

import org.dromara.common.core.exception.ServiceException;
import com.smartdock.risk.support.SensitiveWordMatcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SensitiveWordMatcherUnitTest {

    @Test
    @Tag("dev")
    void masksDefaultMaskedWord() {
        SensitiveWordMatcher matcher = new SensitiveWordMatcher(List.of(
            new SensitiveWordMatcher.WordRule("傻逼", "MASK")
        ));

        SensitiveWordMatcher.FilterResult result = matcher.filter("这个人真傻逼");

        assertThat(result.content()).isEqualTo("这个人真**");
        assertThat(result.hitWords()).containsExactly("傻逼");
    }

    @Test
    @Tag("dev")
    void blocksDefaultBlockedWord() {
        SensitiveWordMatcher matcher = new SensitiveWordMatcher(List.of(
            new SensitiveWordMatcher.WordRule("非法交易", "BLOCK")
        ));

        assertThatThrownBy(() -> matcher.filter("非法交易"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("禁止发送");
    }

    @Test
    @Tag("dev")
    void customWordTakesEffectImmediately() {
        SensitiveWordMatcher matcher = new SensitiveWordMatcher(List.of(
            new SensitiveWordMatcher.WordRule("内卷", "MASK")
        ));

        SensitiveWordMatcher.FilterResult result = matcher.filter("别再内卷了");

        assertThat(result.content()).isEqualTo("别再**了");
    }

    @Test
    @Tag("dev")
    void normalizesFullWidthAsciiAndCase() {
        SensitiveWordMatcher matcher = new SensitiveWordMatcher(List.of(
            new SensitiveWordMatcher.WordRule("abc", "MASK")
        ));

        SensitiveWordMatcher.FilterResult result = matcher.filter("ＡBc");

        assertThat(result.content()).isEqualTo("***");
    }

    @Test
    @Tag("dev")
    void blockActionWinsDuplicateWord() {
        SensitiveWordMatcher matcher = new SensitiveWordMatcher(List.of(
            new SensitiveWordMatcher.WordRule("重复", "BLOCK"),
            new SensitiveWordMatcher.WordRule("重复", "MASK")
        ));

        assertThatThrownBy(() -> matcher.filter("重复"))
            .isInstanceOf(ServiceException.class);
    }

    // ===== inspect(): 命中自测工具专用(2026-08-24), 不抛异常返回完整命中明细 =====
    // 用例全部使用无害自造词, 不复述真实词库样本

    @Test
    @Tag("dev")
    void inspectCollectsAllHitsWithActionAndRangeWithoutThrowing() {
        SensitiveWordMatcher matcher = new SensitiveWordMatcher(List.of(
            new SensitiveWordMatcher.WordRule("zzhitone", "MASK"),
            new SensitiveWordMatcher.WordRule("zzblocker", "BLOCK"),
            new SensitiveWordMatcher.WordRule("zzreview", "REVIEW")
        ));

        SensitiveWordMatcher.InspectionResult result = matcher.inspect("say zzhitone and zzblocker now");

        assertThat(result.blocked()).isTrue();
        assertThat(result.hits()).extracting(SensitiveWordMatcher.Hit::word)
            .containsExactly("zzhitone", "zzblocker");
        assertThat(result.hits()).extracting(SensitiveWordMatcher.Hit::action)
            .containsExactly("MASK", "BLOCK");
        assertThat(result.hits().get(0).start()).isEqualTo(4);
        assertThat(result.hits().get(0).end()).isEqualTo(12);
    }

    @Test
    @Tag("dev")
    void inspectFlagsReviewAndDeduplicatesRepeatedWord() {
        SensitiveWordMatcher matcher = new SensitiveWordMatcher(List.of(
            new SensitiveWordMatcher.WordRule("zzloop", "REVIEW")
        ));

        SensitiveWordMatcher.InspectionResult result = matcher.inspect("zzloop mid zzloop");

        assertThat(result.reviewRequired()).isTrue();
        assertThat(result.blocked()).isFalse();
        // 同词多次命中只报一次(首次位置)
        assertThat(result.hits()).hasSize(1);
        assertThat(result.hits().get(0).start()).isEqualTo(0);
    }

    @Test
    @Tag("dev")
    void inspectReturnsEmptyOnCleanTextOrBlank() {
        SensitiveWordMatcher matcher = new SensitiveWordMatcher(List.of(
            new SensitiveWordMatcher.WordRule("zzhitone", "MASK")
        ));

        assertThat(matcher.inspect("clean text").hits()).isEmpty();
        assertThat(matcher.inspect("clean text").blocked()).isFalse();
        assertThat(matcher.inspect(" ").hits()).isEmpty();
    }

    @Test
    @Tag("dev")
    void filterBehaviorUnchangedAfterScanRefactor() {
        // scan 抽取后 filter 语义回归保护: 掩码区间/命中词/REVIEW 标志与重构前一致
        SensitiveWordMatcher matcher = new SensitiveWordMatcher(List.of(
            new SensitiveWordMatcher.WordRule("zzhitone", "MASK"),
            new SensitiveWordMatcher.WordRule("zzreview", "REVIEW")
        ));

        SensitiveWordMatcher.FilterResult result = matcher.filter("a zzhitone b zzreview c");

        // REVIEW 词同样会被掩码(与重构前一致), 只是额外置 reviewRequired
        assertThat(result.content()).isEqualTo("a ******** b ******** c");
        assertThat(result.hitWords()).containsExactly("zzhitone", "zzreview");
        assertThat(result.reviewRequired()).isTrue();
    }
}
