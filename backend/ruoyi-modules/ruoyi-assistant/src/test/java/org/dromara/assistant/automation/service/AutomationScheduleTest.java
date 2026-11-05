package org.dromara.assistant.automation.service;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 自动化日程纯函数单测（2026-11-05 自动化批·题4 乙）。
 *
 * <p>覆盖：每天/每周几的下一次计算（严格晚于基准）、未来 5 次预览、非法输入、
 * 每周几解析与去重、内置项不参与扫描。无 Spring 依赖，{@code -P local} 直跑。</p>
 */
@Tag("local")
class AutomationScheduleTest {

    // 2026-11-05 是周四
    private static final LocalDateTime THU_1000 = LocalDateTime.of(2026, 11, 5, 10, 0);

    @Test
    void dailyNextIsSameDayWhenTimeStillAhead() {
        assertThat(AutomationSchedule.next(THU_1000, "DAILY", "11:30", null))
            .isEqualTo(LocalDateTime.of(2026, 11, 5, 11, 30));
    }

    @Test
    void dailyNextRollsToTomorrowWhenTimePassed() {
        assertThat(AutomationSchedule.next(THU_1000, "DAILY", "09:00", null))
            .isEqualTo(LocalDateTime.of(2026, 11, 6, 9, 0));
    }

    @Test
    void nextIsStrictlyAfterBaseline() {
        LocalDateTime exactly = LocalDateTime.of(2026, 11, 5, 9, 0);
        assertThat(AutomationSchedule.next(exactly, "DAILY", "09:00", null))
            .isEqualTo(LocalDateTime.of(2026, 11, 6, 9, 0));
    }

    @Test
    void weeklyPicksConfiguredDay() {
        // 周四 10:00 起，下一个周一 = 11-09
        assertThat(AutomationSchedule.next(THU_1000, "WEEKLY", "09:00", "1"))
            .isEqualTo(LocalDateTime.of(2026, 11, 9, 9, 0));
    }

    @Test
    void weeklyPicksNearestConfiguredDay() {
        // 一三五：周四之后的第一个是周五 11-06
        assertThat(AutomationSchedule.next(THU_1000, "WEEKLY", "09:00", "1,3,5"))
            .isEqualTo(LocalDateTime.of(2026, 11, 6, 9, 0));
    }

    @Test
    void weeklyOnSameWeekdayRollsToNextWeek() {
        LocalDateTime mondayAt9 = LocalDateTime.of(2026, 11, 9, 9, 0);
        assertThat(AutomationSchedule.next(mondayAt9, "WEEKLY", "09:00", "1"))
            .isEqualTo(LocalDateTime.of(2026, 11, 16, 9, 0));
    }

    @Test
    void previewReturnsFiveUpcoming() {
        List<LocalDateTime> out = AutomationSchedule.preview(THU_1000, "WEEKLY", "09:00", "1,3,5", 5);
        assertThat(out).containsExactly(
            LocalDateTime.of(2026, 11, 6, 9, 0),
            LocalDateTime.of(2026, 11, 9, 9, 0),
            LocalDateTime.of(2026, 11, 11, 9, 0),
            LocalDateTime.of(2026, 11, 13, 9, 0),
            LocalDateTime.of(2026, 11, 16, 9, 0));
    }

    @Test
    void builtinNeverScheduledByScanner() {
        assertThat(AutomationSchedule.next(THU_1000, "BUILTIN", "08:00", null)).isNull();
    }

    @Test
    void validateRejectsBadTimeAndEmptyWeekdays() {
        assertThatThrownBy(() -> AutomationSchedule.validate("DAILY", "9:00", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutomationSchedule.validate("DAILY", "24:00", null))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutomationSchedule.validate("WEEKLY", "09:00", ""))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AutomationSchedule.validate("CRON", "09:00", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parseWeekdaysDedupesAndSorts() {
        assertThat(AutomationSchedule.parseWeekdays("5,1,3,3,9,x"))
            .containsExactly(1, 3, 5);
    }

    @Test
    void describeInPlainLanguage() {
        assertThat(AutomationSchedule.describe("DAILY", "09:00", null)).isEqualTo("每天 09:00");
        assertThat(AutomationSchedule.describe("WEEKLY", "09:00", "1,3,5")).isEqualTo("每周一、三、五 09:00");
        assertThat(AutomationSchedule.describe("BUILTIN", null, null)).isEqualTo("每天 08:00");
    }
}
