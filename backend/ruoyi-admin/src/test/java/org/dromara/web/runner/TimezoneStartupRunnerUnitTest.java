package org.dromara.web.runner;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.TimeZone;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 时区自检强制闸单测（2026-09-20 拍板：非北京时区拒绝启动）。
 *
 * <p>三分支：①北京时区正常放行；②非北京时区默认拒绝启动；③显式 -Ddcs.timezone.enforce=false 降级仅告警。
 * TimeZone.setDefault 是 JVM 全局态——每个用例 try/finally 保存恢复原时区与系统属性，
 * 保证不污染同 JVM 顺跑的其它测试（forkCount=0 模式）。</p>
 */
@Tag("dev")
class TimezoneStartupRunnerUnitTest {

    private static final TimeZone ORIGINAL_TZ = TimeZone.getDefault();

    private void withTimezone(TimeZone tz, Runnable body) {
        TimeZone.setDefault(tz);
        String oldProp = System.getProperty(TimezoneStartupRunner.ENFORCE_PROPERTY);
        try {
            body.run();
        } finally {
            TimeZone.setDefault(ORIGINAL_TZ);
            if (oldProp == null) {
                System.clearProperty(TimezoneStartupRunner.ENFORCE_PROPERTY);
            } else {
                System.setProperty(TimezoneStartupRunner.ENFORCE_PROPERTY, oldProp);
            }
        }
    }

    @Test
    void beijingTimezoneBootsNormally() {
        withTimezone(TimeZone.getTimeZone("GMT+8"), () ->
            assertThatCode(() -> new TimezoneStartupRunner().run(null))
                .doesNotThrowAnyException());
    }

    @Test
    void nonBeijingTimezoneRefusesBootByDefault() {
        withTimezone(TimeZone.getTimeZone("America/New_York"), () -> {
            System.clearProperty(TimezoneStartupRunner.ENFORCE_PROPERTY);
            assertThatThrownBy(() -> new TimezoneStartupRunner().run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("拒绝启动")
                .hasMessageContaining("-Duser.timezone=GMT+8");
        });
    }

    @Test
    void nonBeijingTimezoneDegradesToWarnWithExplicitOptOut() {
        withTimezone(TimeZone.getTimeZone("America/New_York"), () -> {
            System.setProperty(TimezoneStartupRunner.ENFORCE_PROPERTY, "false");
            assertThatCode(() -> new TimezoneStartupRunner().run(null))
                .doesNotThrowAnyException();
        });
    }

    @Test
    void beijingEquivalentsAreAccepted() {
        assertThat(TimezoneStartupRunner.isBeijingTimezone(TimeZone.getTimeZone("Asia/Shanghai"))).isTrue();
        assertThat(TimezoneStartupRunner.isBeijingTimezone(TimeZone.getTimeZone("GMT+8"))).isTrue();
        assertThat(TimezoneStartupRunner.isBeijingTimezone(TimeZone.getTimeZone("America/New_York"))).isFalse();
        assertThat(TimezoneStartupRunner.isBeijingTimezone(null)).isFalse();
    }
}
