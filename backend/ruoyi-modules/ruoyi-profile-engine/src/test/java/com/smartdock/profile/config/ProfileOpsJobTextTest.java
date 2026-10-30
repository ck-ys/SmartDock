package com.smartdock.profile.config;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题4 站内通知人话化：复核超时提醒的正文逐字钉死（2026-10-30）。
 *
 * <p>原来的坑：正文直接拼 {@code Date} 对象，用户看到的是
 * 「最早一条入队于 Mon Oct 19 23:08:19 GMT+08:00 2026。」——中文通知里嵌英文长日期。</p>
 */
@Tag("local")
class ProfileOpsJobTextTest {

    @Test
    void slaReminderUsesHumanReadableDate() {
        // 2026-10-19 23:08:19 GMT+8（现网那条旧通知的入队时间）
        Date earliest = new Date(1792422499000L);
        String content = ProfileOpsJob.slaReminderContent("dcs", 1, 7, earliest);
        assertThat(content).isEqualTo(
            "业务源 dcs 有 1 条复核已超过 SLA 7 天未处理，请尽快处理。最早一条入队于 2026-10-19 23:08。");
        // 英文星期/时区表述不许再出现
        assertThat(content).doesNotContain("GMT").doesNotContain("Mon ");
    }

    @Test
    void slaReminderKeepsTrueCountAndRomansWithoutDate() {
        // 积压 300 条时正文必须是 300（旧实现 LIMIT 100 后数长度，会报「有 100 条」）
        assertThat(ProfileOpsJob.slaReminderContent("dcs", 300, 7, null))
            .isEqualTo("业务源 dcs 有 300 条复核已超过 SLA 7 天未处理，请尽快处理。");
    }
}
