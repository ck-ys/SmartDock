package com.smartdock.common.text;

import org.dromara.common.core.exception.ServiceException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Date;

/**
 * 「按天筛选」的区间口径（2026-11-25 钱包流水批 Q5）。
 *
 * <p>前端给的是 {@code yyyy-MM-dd} 的「天」，库里存的是 {@code timestamp}。两边直接比较会踩两个坑：
 * ① PG 不做 varchar 与 timestamp 的隐式转换（String 绑定直接 42883）；
 * ② 上界若取当天 23:59:59，{@code .001~.999} 的行会漏掉。</p>
 *
 * <p>所以统一成半开区间：下界 = 起始日 00:00（含），上界 = 结束日次日 00:00（不含）。
 * 时区固定 GMT+8（与后端启动参数 {@code -Duser.timezone=GMT+8} 一致），不读系统默认时区，
 * 免部署环境漂移。</p>
 *
 * <p>纯函数、无 Spring 依赖，放在 common-core 与 {@link HumanText} 同层：消费方跨钱包/积分两条线，
 * 口径只许有一份。</p>
 *
 * @author smartdock
 */
public final class DayRange {

    /** 后端统一时区（启动参数 -Duser.timezone=GMT+8） */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 单次查询允许的最大跨度（天）。流水表按天查，一年足够；再大就该走导出而不是在线合计 */
    public static final int MAX_SPAN_DAYS = 366;

    private final Date beginInclusive;
    private final Date endExclusive;

    private DayRange(Date beginInclusive, Date endExclusive) {
        this.beginInclusive = beginInclusive;
        this.endExclusive = endExclusive;
    }

    /** 下界（含）；未指定为 {@code null}。 */
    public Date beginInclusive() {
        return beginInclusive;
    }

    /** 上界（不含）；未指定为 {@code null}。 */
    public Date endExclusive() {
        return endExclusive;
    }

    /** 两端都没给 = 不限时间。 */
    public boolean isEmpty() {
        return beginInclusive == null && endExclusive == null;
    }

    /**
     * 解析起止「天」。两端都可为空（= 该侧不限）。
     *
     * @param beginDay 起始日 {@code yyyy-MM-dd}，空串/null 表示不限
     * @param endDay   结束日 {@code yyyy-MM-dd}（含当天），空串/null 表示不限
     * @throws ServiceException 格式不对 / 结束早于开始 / 跨度超过 {@link #MAX_SPAN_DAYS} 天
     */
    public static DayRange of(String beginDay, String endDay) {
        LocalDate begin = parseDay(beginDay);
        LocalDate end = parseDay(endDay);
        if (begin != null && end != null) {
            if (end.isBefore(begin)) {
                throw new ServiceException("结束日期不能早于开始日期", 400);
            }
            long span = end.toEpochDay() - begin.toEpochDay();
            if (span > MAX_SPAN_DAYS) {
                throw new ServiceException("查询区间最长 " + MAX_SPAN_DAYS + " 天，请缩小范围或改用导出", 400);
            }
        }
        return new DayRange(
            begin == null ? null : atStartOfDay(begin),
            end == null ? null : atStartOfDay(end.plusDays(1)));
    }

    private static LocalDate parseDay(String day) {
        if (day == null || day.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(day.trim());
        } catch (DateTimeParseException e) {
            throw new ServiceException("时间格式须为 yyyy-MM-dd", 400);
        }
    }

    private static Date atStartOfDay(LocalDate day) {
        LocalDateTime t = day.atStartOfDay();
        return Date.from(t.atZone(ZONE).toInstant());
    }
}
