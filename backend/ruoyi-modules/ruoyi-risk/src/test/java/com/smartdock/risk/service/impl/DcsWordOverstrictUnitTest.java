package com.smartdock.risk.service.impl;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 词库"疑似过严"判据单测(收尾批 Q1+Q2, 2026-09-23)。
 *
 * <p>拍板阈值: 误报率>=50% 且近 7 天命中>=5 次——整数运算 fpCnt*2>=hitCnt 防浮点误差。
 * 边界守护: 小样本不标(防 1/1=100% 的单词命中误标)、恰好 50% 标、49% 不标。</p>
 */
@Tag("dev")
class DcsWordOverstrictUnitTest {

    @Test
    void smallSampleNeverMarked() {
        // 命中不足 5 次, 误报率再高也不标(1/1=100% 是小样本噪声)
        assertThat(DcsSensitiveWordServiceImpl.isOverstrict(1, 1)).isFalse();
        assertThat(DcsSensitiveWordServiceImpl.isOverstrict(4, 4)).isFalse();
        assertThat(DcsSensitiveWordServiceImpl.isOverstrict(4, 0)).isFalse();
    }

    @Test
    void exactlyThresholdMarked() {
        // 恰好命中 5 次 + 误报率恰好 50%: 标
        assertThat(DcsSensitiveWordServiceImpl.isOverstrict(5, 3)).isTrue();
        // 5 次命中 3 次误报 = 60%: 标
        assertThat(DcsSensitiveWordServiceImpl.isOverstrict(10, 5)).isTrue();
    }

    @Test
    void belowRateNotMarked() {
        // 5 次命中 2 次误报 = 40%: 不标(fpCnt*2 < hitCnt)
        assertThat(DcsSensitiveWordServiceImpl.isOverstrict(5, 2)).isFalse();
        assertThat(DcsSensitiveWordServiceImpl.isOverstrict(100, 49)).isFalse();
    }

    @Test
    void zeroHitNeverMarked() {
        assertThat(DcsSensitiveWordServiceImpl.isOverstrict(0, 0)).isFalse();
        assertThat(DcsSensitiveWordServiceImpl.isOverstrict(0, 3)).isFalse();
    }
}
