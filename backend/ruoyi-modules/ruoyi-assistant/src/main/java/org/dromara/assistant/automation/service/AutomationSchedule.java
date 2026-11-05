package org.dromara.assistant.automation.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 自动化日程纯函数（2026-11-05 拍板 题4 乙：每天/每周几 + 时刻，不碰 cron）。
 *
 * <p>全部按 {@code Asia/Shanghai} 墙上时间算（该时区无夏令时，LocalDateTime 直接加减安全）；
 * 「下次」一律严格晚于给定时刻——创建时传 now，到点那一刻不会自己又算成同一时刻。</p>
 *
 * <p>无 Spring 依赖，{@code @Tag("local")} 单测直调。</p>
 */
public final class AutomationSchedule {

    public static final String DAILY = "DAILY";
    public static final String WEEKLY = "WEEKLY";
    public static final String BUILTIN = "BUILTIN";

    /** 每人自建条数上限（题3 甲） */
    public static final int MAX_PER_USER = 5;
    /** 连续失败达到此次数自动停用（题6 甲） */
    public static final int FAILURE_LIMIT = 3;
    /** 补跑窗口：迟到超过 6 小时记 MISSED 不补跑 */
    public static final Duration CATCH_UP_WINDOW = Duration.ofHours(6);
    /** 运行超时（悬挂 RUNNING 收口阈值） */
    public static final Duration RUN_TIMEOUT = Duration.ofMinutes(15);

    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");
    private static final char[] WEEK_CN = {'一', '二', '三', '四', '五', '六', '日'};

    private AutomationSchedule() {
    }

    /** 校验日程配置；不合法抛 IllegalArgumentException（service 转 400） */
    public static void validate(String type, String time, String weekdays) {
        if (BUILTIN.equals(type)) {
            return;
        }
        if (!DAILY.equals(type) && !WEEKLY.equals(type)) {
            throw new IllegalArgumentException("日程类型只能是每天或每周");
        }
        if (time == null || !time.matches("^([01]\\d|2[0-3]):[0-5]\\d$")) {
            throw new IllegalArgumentException("执行时间格式须为 HH:mm");
        }
        if (WEEKLY.equals(type)) {
            Set<Integer> days = parseWeekdays(weekdays);
            if (days.isEmpty()) {
                throw new IllegalArgumentException("每周执行至少要选一天");
            }
        }
    }

    /**
     * 下一次执行时刻（严格晚于 {@code after}）。
     *
     * @return BUILTIN 或配置为空时返回 null（内置项走自己的 08:00 任务，不参与扫描）
     */
    public static LocalDateTime next(LocalDateTime after, String type, String time, String weekdays) {
        if (after == null || BUILTIN.equals(type) || time == null) {
            return null;
        }
        LocalTime at;
        try {
            at = LocalTime.parse(time, HH_MM);
        } catch (RuntimeException e) {
            return null;
        }
        if (DAILY.equals(type)) {
            LocalDateTime candidate = after.toLocalDate().atTime(at);
            return candidate.isAfter(after) ? candidate : candidate.plusDays(1);
        }
        if (WEEKLY.equals(type)) {
            Set<Integer> days = parseWeekdays(weekdays);
            if (days.isEmpty()) {
                return null;
            }
            for (int i = 0; i <= 7; i++) {
                LocalDate date = after.toLocalDate().plusDays(i);
                if (!days.contains(date.getDayOfWeek().getValue())) {
                    continue;
                }
                LocalDateTime candidate = date.atTime(at);
                if (candidate.isAfter(after)) {
                    return candidate;
                }
            }
            return null;
        }
        return null;
    }

    /** 未来 N 次预览（新建/编辑页展示，题1 乙「先跑一次 + 预览」） */
    public static List<LocalDateTime> preview(LocalDateTime after, String type, String time,
                                              String weekdays, int count) {
        List<LocalDateTime> out = new ArrayList<>();
        if (count <= 0) {
            return out;
        }
        LocalDateTime cursor = after;
        for (int i = 0; i < count; i++) {
            LocalDateTime n = next(cursor, type, time, weekdays);
            if (n == null) {
                break;
            }
            out.add(n);
            cursor = n;
        }
        return out;
    }

    /** 人话日程（通知标题/卡片兜底用）：每天 09:00 / 每周一、三 09:00 */
    public static String describe(String type, String time, String weekdays) {
        if (BUILTIN.equals(type)) {
            return "每天 08:00";
        }
        if (time == null) {
            return "未设置时间";
        }
        if (DAILY.equals(type)) {
            return "每天 " + time;
        }
        Set<Integer> days = parseWeekdays(weekdays);
        if (days.isEmpty()) {
            return "每周 " + time;
        }
        StringBuilder sb = new StringBuilder("每周");
        boolean first = true;
        for (int d : days) {
            if (!first) {
                sb.append('、');
            }
            sb.append(WEEK_CN[d - 1]);
            first = false;
        }
        return sb.append(' ').append(time).toString();
    }

    /** 解析每周几；忽略非法片段，去重升序 */
    public static Set<Integer> parseWeekdays(String raw) {
        Set<Integer> out = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        List<Integer> parsed = new ArrayList<>();
        for (String part : raw.split("[,，\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            try {
                int d = Integer.parseInt(part.trim());
                if (d >= 1 && d <= 7 && !parsed.contains(d)) {
                    parsed.add(d);
                }
            } catch (NumberFormatException ignore) {
                // 非法片段直接丢弃，不阻断保存（前端只可能传 1-7）
            }
        }
        parsed.sort(Integer::compareTo);
        out.addAll(parsed);
        return out;
    }
}
