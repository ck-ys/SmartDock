package com.smartdock.common.text;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 题4 人话格式化：日期/时长/金额三种口径各钉几个边界（拼串处全靠它，错一个字用户就看到机器味）。
 */
@Tag("local")
class HumanTextTest {

    @Test
    void dateTimeFormatsInBeijingTime() {
        // 2026-10-21 00:19:45 GMT+08:00 —— 正是以前 Date.toString 露出来的那串
        Date date = new Date(1792513185000L);
        assertThat(HumanText.dateTime(date)).isEqualTo("2026-10-21 00:19");
        assertThat(HumanText.dateTime(null)).isEqualTo("—");
    }

    @Test
    void durationSpeaksSecondsMinutesHoursDays() {
        assertThat(HumanText.duration(850)).isEqualTo("850 毫秒");
        // 整数秒不加「约」：阈值 10 秒/1 分是精确值，写成「约」会被读成浮动阈值
        assertThat(HumanText.duration(1_000)).isEqualTo("1 秒");
        assertThat(HumanText.duration(10_000)).isEqualTo("10 秒");
        assertThat(HumanText.duration(53_300)).isEqualTo("约 53.3 秒");
        assertThat(HumanText.duration(60_000)).isEqualTo("1 分");
        assertThat(HumanText.duration(125_000)).isEqualTo("2 分 5 秒");
        assertThat(HumanText.duration(130_500)).isEqualTo("约 2 分 11 秒");
        // 59.6 秒进位不能出现「约 2 分 60 秒」
        assertThat(HumanText.duration(119_600)).isEqualTo("约 2 分");
        // 秒段进位要升档，不能出「约 60 秒」
        assertThat(HumanText.duration(59_950)).isEqualTo("1 分");
        // 分段进位要升档，不能出「约 60 分」
        assertThat(HumanText.duration(3_599_999)).isEqualTo("1 小时");
        assertThat(HumanText.duration(5_400_000)).isEqualTo("约 1.5 小时");
        assertThat(HumanText.duration(3_600_000)).isEqualTo("1 小时");
        assertThat(HumanText.duration(3 * 86_400_000L)).isEqualTo("3 天");
        assertThat(HumanText.duration(3 * 86_400_000L + 1)).isEqualTo("约 3 天");
        assertThat(HumanText.duration(-1)).isEqualTo("—");
    }

    @Test
    void moneyKeepsTwoDecimalsButNeverLiesAboutSubCentDiff() {
        assertThat(HumanText.money(new BigDecimal("10.0000"))).isEqualTo("10.00");
        assertThat(HumanText.money(new BigDecimal("0.3201"))).isEqualTo("0.32");
        assertThat(HumanText.money(new BigDecimal("-3.5"))).isEqualTo("-3.50");
        assertThat(HumanText.money(BigDecimal.ZERO)).isEqualTo("0.00");
        // 分以下差额：两位小数会渲染成 0.00（等于说没差异），必须保留位数
        assertThat(HumanText.money(new BigDecimal("0.0002"))).isEqualTo("0.0002");
        // 比 4 位还小也不能归零、不能丢符号（对账告警里报「0」比报「0.00」更误导）
        assertThat(HumanText.money(new BigDecimal("0.00001"))).isEqualTo("0.00001");
        assertThat(HumanText.money(new BigDecimal("-0.00001"))).isEqualTo("-0.00001");
        assertThat(HumanText.money(null)).isEqualTo("—");
    }
}
