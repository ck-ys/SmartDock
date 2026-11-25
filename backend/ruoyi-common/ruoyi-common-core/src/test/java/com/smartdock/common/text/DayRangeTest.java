package com.smartdock.common.text;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 按天筛选的区间口径（2026-11-25 钱包流水批 Q5）。
 *
 * <p>三件事必须钉死：① 上界是「次日 00:00 不含」而不是「当天 23:59:59」——闭区间会漏 .001~.999 的行；
 * ② 时区固定 GMT+8，不随机器默认时区漂；③ 跨度上限会拒绝，不让人拉十年流水做在线合计。</p>
 */
@Tag("local")
class DayRangeTest {

    /** 2026-11-25 00:00:00 GMT+8（= 2026-11-24 16:00Z） */
    private static final long NOV25_0000 = 1795536000000L;

    @Test
    void 上界是次日零点不含_闭区间会漏毫秒行() {
        DayRange r = DayRange.of("2026-11-25", "2026-11-25");
        assertThat(r.beginInclusive().getTime()).isEqualTo(NOV25_0000);
        // 次日 00:00 —— 不含，所以 23:59:59.999 那一行仍在区间内
        assertThat(r.endExclusive().getTime()).isEqualTo(NOV25_0000 + 86_400_000L);
        assertThat(r.isEmpty()).isFalse();
    }

    @Test
    void 两端可空_空串与null同义() {
        assertThat(DayRange.of(null, null).isEmpty()).isTrue();
        assertThat(DayRange.of("", "  ").isEmpty()).isTrue();
        DayRange onlyBegin = DayRange.of("2026-11-25", null);
        assertThat(onlyBegin.beginInclusive()).isNotNull();
        assertThat(onlyBegin.endExclusive()).isNull();
        DayRange onlyEnd = DayRange.of(null, "2026-11-25");
        assertThat(onlyEnd.beginInclusive()).isNull();
        assertThat(onlyEnd.endExclusive()).isNotNull();
    }

    @Test
    void 格式不对报400而不是静默放过() {
        assertThatThrownBy(() -> DayRange.of("2026/11/25", null))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("yyyy-MM-dd");
        assertThatThrownBy(() -> DayRange.of(null, "11-25"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("yyyy-MM-dd");
    }

    @Test
    void 结束早于开始要拒绝() {
        assertThatThrownBy(() -> DayRange.of("2026-11-25", "2026-11-24"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("不能早于");
    }

    @Test
    void 跨度超上限要拒绝_边界值刚好通过() {
        // 366 天差：2026-01-01 ~ 2027-01-02 正好 366 天 → 通过
        assertThat(DayRange.of("2026-01-01", "2027-01-02").isEmpty()).isFalse();
        // 367 天 → 拒绝
        assertThatThrownBy(() -> DayRange.of("2026-01-01", "2027-01-03"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("最长");
    }

    @Test
    void 时区固定GMT8_不读系统默认() {
        // 2026-11-25 00:00 GMT+8 == 2026-11-24 16:00 UTC
        Date begin = DayRange.of("2026-11-25", null).beginInclusive();
        assertThat(begin.toInstant().toString()).isEqualTo("2026-11-24T16:00:00Z");
    }
}
