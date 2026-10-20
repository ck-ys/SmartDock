package com.smartdock.profile.engine;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 复核 SLA 超时天数现算纯函数测试（批9 拍板 Q4/Q8-A：按源配置，入队时间+SLA 现算不回填）。
 */
@Tag("local")
class ProfileReviewSlaTest {

    private static final long DAY = TimeUnit.DAYS.toMillis(1);

    @Test
    void withinSlaIsZero() {
        Date now = new Date(100 * DAY);
        assertThat(ProfileReviewService.slaOverdueDays(new Date(now.getTime() - 1 * DAY), 7, now)).isZero();
    }

    @Test
    void exactlyAtSlaBoundaryIsZero() {
        Date now = new Date(100 * DAY);
        assertThat(ProfileReviewService.slaOverdueDays(new Date(now.getTime() - 7 * DAY), 7, now)).isZero();
    }

    @Test
    void firstDayPastSlaCountsAsOne() {
        Date now = new Date(100 * DAY);
        // 超过 SLA 12 小时 = 超时第 1 天
        assertThat(ProfileReviewService.slaOverdueDays(new Date(now.getTime() - 7 * DAY - 12 * 3_600_000L), 7, now)).isEqualTo(1L);
        // 超过 1 整天 = 第 2 天
        assertThat(ProfileReviewService.slaOverdueDays(new Date(now.getTime() - 8 * DAY), 7, now)).isEqualTo(2L);
    }

    @Test
    void nullSlaFallsBackToDefaultSevenDays() {
        Date now = new Date(100 * DAY);
        assertThat(ProfileReviewService.slaOverdueDays(new Date(now.getTime() - 1 * DAY), null, now)).isZero();
        assertThat(ProfileReviewService.slaOverdueDays(new Date(now.getTime() - 9 * DAY), null, now)).isEqualTo(3L);
    }

    @Test
    void nullCreatedAtNeverOverdue() {
        assertThat(ProfileReviewService.slaOverdueDays(null, 7, new Date())).isZero();
    }
}
