package com.smartdock.common.text;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

/**
 * 面向用户的「人话」格式化（2026-10-30 题4）。
 *
 * <p>站内通知/告警是给人看的：日期别写成 {@code Wed Oct 21 00:19:45 GMT+08:00 2026}，
 * 时长别甩 8 位毫秒，金额别把 {@code 10.0000} 原样端上来。三处拼串（画像复核超时、
 * 引擎健康、钱包对账）统一走这里，口径才不会各写一套。</p>
 *
 * <p>纯函数、无 Spring 依赖，时间固定按 GMT+8（与后端启动参数一致）。
 * 放在 common-core 是因为消费方横跨画像/钱包/助手三个模块；本类不引业务实体、不参与组件扫描，
 * 后续如要沉淀同类工具请继续遵守这两条。</p>
 *
 * @author smartdock
 */
public final class HumanText {

    /** 后端统一时区（启动参数 -Duser.timezone=GMT+8），这里不读系统默认时区以免部署环境漂移 */
    private static final ZoneId ZONE = ZoneId.of("GMT+8");

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private HumanText() {
    }

    /** 日期时间 → {@code 2026-10-21 00:19}；空值给「—」。 */
    public static String dateTime(Date date) {
        if (date == null) {
            return "—";
        }
        Instant instant = date.toInstant();
        return MINUTE.format(instant.atZone(ZONE));
    }

    /**
     * 时长 → 人话：{@code 850 毫秒} / {@code 53 秒} / {@code 约 53.3 秒} / {@code 约 2 分 10 秒} / {@code 1 分} / {@code 约 1.5 小时}。
     *
     * <p>不足 1 秒保留毫秒（短延迟换秒会全成 0 秒）；1 分钟以上按「分+秒」给，小时以上一位小数。
     * 整数秒不加「约」——阈值这类精确值（10 秒 / 1 分）写成「约 10 秒」会让运维以为阈值在浮动（2026-10-30 三审 P2）。</p>
     */
    public static String duration(long millis) {
        if (millis < 0) {
            return "—";
        }
        if (millis < 1_000L) {
            return millis + " 毫秒";
        }
        if (millis < 60_000L) {
            long seconds = Math.round(millis / 1_000.0);
            // 59.5 秒以上进位到分钟，别出「约 60 秒」
            if (seconds >= 60L) {
                return "1 分";
            }
            return approx(millis % 1_000L != 0) + oneDecimal(millis / 1_000.0) + " 秒";
        }
        if (millis < 3_600_000L) {
            long minutes = millis / 60_000L;
            long seconds = Math.round((millis % 60_000L) / 1_000.0);
            // 59.6 秒进位成 60 秒时要进到分钟上，别出现「约 2 分 60 秒」
            if (seconds >= 60L) {
                minutes += 1;
                seconds = 0;
            }
            // 同理：59 分 59.5 秒进位后要升到小时，别出「约 60 分」
            if (minutes >= 60L) {
                return "1 小时";
            }
            return approx(millis % 1_000L != 0)
                + (seconds == 0 ? minutes + " 分" : minutes + " 分 " + seconds + " 秒");
        }
        if (millis < 86_400_000L) {
            return approx(millis % 3_600_000L != 0) + oneDecimal(millis / 3_600_000.0) + " 小时";
        }
        return approx(millis % 86_400_000L != 0) + oneDecimal(millis / 86_400_000.0) + " 天";
    }

    /**
     * 金额 → 两位小数（{@code 10.0000} → {@code 10.00}）。
     *
     * <p>特例：非零但绝对值小于 0.005 的差额（钱包对账会出现 0.0002 这种分以下差异）保留到 4 位——
     * 两位小数会渲染成 {@code 0.00}，等于一边报警一边说「没差」，比不格式化更糟。</p>
     */
    public static String money(BigDecimal value) {
        if (value == null) {
            return "—";
        }
        BigDecimal rounded = value.setScale(2, RoundingMode.HALF_UP);
        if (value.signum() != 0 && rounded.signum() == 0) {
            BigDecimal fine = value.setScale(4, RoundingMode.HALF_UP);
            // 4 位仍归零（如 0.00001）就退回原值：只要真有差，方向和数值都得说出来，
            // 对账告警里报「0」比报「0.00」更误导（2026-10-30 三审 P2）
            return (fine.signum() == 0 ? value : fine).stripTrailingZeros().toPlainString();
        }
        return rounded.toPlainString();
    }

    private static String approx(boolean approximate) {
        return approximate ? "约 " : "";
    }

    private static String oneDecimal(double value) {
        BigDecimal decimal = BigDecimal.valueOf(value).setScale(1, RoundingMode.HALF_UP);
        // 53.0 → 53；53.3 保留一位
        return decimal.stripTrailingZeros().scale() <= 0 ? decimal.setScale(0, RoundingMode.HALF_UP).toPlainString()
            : decimal.toPlainString();
    }
}
