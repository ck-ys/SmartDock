package com.smartdock.profile.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模型版本号递增纯函数测试（批9 拍板 Q1-A：发布时自动 v12→v13，杜绝同版本冲突）。
 */
@Tag("local")
class ProfileModelVersionTest {

    @Test
    void numericSuffixIncrements() {
        assertThat(ProfileScoreModelService.nextVersion("v12")).isEqualTo("v13");
        assertThat(ProfileScoreModelService.nextVersion("v9")).isEqualTo("v10");
    }

    @Test
    void chinesePrefixPreserved() {
        assertThat(ProfileScoreModelService.nextVersion("RFM模型2")).isEqualTo("RFM模型3");
    }

    @Test
    void bareNumberAndBlankFallBackToV1() {
        assertThat(ProfileScoreModelService.nextVersion(null)).isEqualTo("v1");
        assertThat(ProfileScoreModelService.nextVersion("  ")).isEqualTo("v1");
        assertThat(ProfileScoreModelService.nextVersion("12")).isEqualTo("13");
    }

    @Test
    void nonNumericSuffixGetsNextMarker() {
        assertThat(ProfileScoreModelService.nextVersion("RFM")).isEqualTo("RFM-next");
    }

    @Test
    void nearCeilingBumpsToMarkerNotOverflow() {
        assertThat(ProfileScoreModelService.nextVersion("v9998")).isEqualTo("v9999");
        assertThat(ProfileScoreModelService.nextVersion("v9999")).isEqualTo("v9999-next");
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        assertThat(ProfileScoreModelService.nextVersion(" v12 ")).isEqualTo("v13");
    }
}
