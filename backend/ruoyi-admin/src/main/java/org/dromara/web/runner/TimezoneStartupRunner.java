package org.dromara.web.runner;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.TimeZone;

/**
 * 启动时区自检强制闸（2026-09-17 时区事故防再犯建告警；2026-09-20 用户拍板升级为拒绝启动）。
 *
 * <p>背景：2026-09-13~09-16 与 09-17 两轮进程启动丢失 {@code -Duser.timezone=GMT+8}，
 * JVM 落回系统时区（美东），落库时间整体偏移 8~12 小时。原告警-only 方案（09-17 拍板
 * "不做自动拨正"）依赖人工巡检 sys-error.log，两轮事故证明等于没防线——2026-09-20
 * 多视角审查问卷 E2/Q5 拍板升级：时区不对直接拒绝启动。本类仍不做任何"拨正"（不写数据、
 * 不改时区），只拒启，与 09-17"不做拨正"拍板不冲突。</p>
 *
 * <p>逃生开关：确需在非北京时区跑对比测试时，显式带 {@code -Ddcs.timezone.enforce=false}
 * 启动（默认强制）。开关只在日志留痕，不降低默认防线。</p>
 */
@Slf4j
@Component
public class TimezoneStartupRunner implements ApplicationRunner {

    /** 北京时间原始偏移毫秒数（GMT+8，不含夏令时） */
    private static final int BEIJING_RAW_OFFSET_MILLIS = 8 * 3600 * 1000;

    /** 逃生开关：-Ddcs.timezone.enforce=false 时只告警不拒启（默认强制） */
    static final String ENFORCE_PROPERTY = "dcs.timezone.enforce";

    @Override
    public void run(ApplicationArguments args) {
        TimeZone defaultTz = TimeZone.getDefault();
        if (isBeijingTimezone(defaultTz)) {
            log.info("[时区自检] JVM 默认时区正常: {}", defaultTz.getID());
            return;
        }
        boolean enforce = !"false".equalsIgnoreCase(System.getProperty(ENFORCE_PROPERTY, "true"));
        log.error("[时区自检] JVM 默认时区不是北京时间: {}(rawOffset={}ms)——新写入的提交/发布/登录时间将整体偏移! "
            + "请带启动参数 -Duser.timezone=GMT+8 重启进程(参考 scripts/start-backend.ps1)。"
            + "历史事故: 2026-09-13~16 落库时间偏移 8~12 小时(业务反馈#7)",
            defaultTz.getID(), defaultTz.getRawOffset());
        if (enforce) {
            throw new IllegalStateException("[时区自检] 拒绝启动: JVM 默认时区 " + defaultTz.getID()
                + " 不是北京时间——须带 -Duser.timezone=GMT+8 启动; 确需其他时区对比测试请显式带 "
                + "-D" + ENFORCE_PROPERTY + "=false(2026-09-20 拍板, 历史两轮事故防再犯)");
        }
        log.warn("[时区自检] 已按 -D{}=false 降级为仅告警(对比测试用途)", ENFORCE_PROPERTY);
    }

    static boolean isBeijingTimezone(TimeZone tz) {
        // Asia/Shanghai / GMT+8 / Asia/Chongqing 等价写法均按原始偏移放行(不含夏令时)
        return tz != null && tz.getRawOffset() == BEIJING_RAW_OFFSET_MILLIS;
    }
}
