package org.dromara.assistant.core;

import cn.hutool.core.lang.Dict;
import org.dromara.common.core.service.ConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 题①（2026-10-31 拍板 B）：驱逐阈值两级解析纯函数单测。
 *
 * <p>判据=可转 int 且 1000 ≤ v ≤ 1000000（含端点）：先本租户、非法再默认租户、
 * 仍非法回 80000。read() 在纯单测（无 Spring 上下文）走 isEnable 异常后的直读分支
 * ——两段读到同值，租户级差异由 resolve 纯函数覆盖；真实租户切换由活体验证覆盖。</p>
 */
@Tag("local")
class AssistantEvictionThresholdTest {

    @Test
    void 本租户配置合法_原值生效() {
        assertThat(AssistantEvictionThreshold.resolve("30000", null)).isEqualTo(30_000);
        // 本租户优先于默认租户
        assertThat(AssistantEvictionThreshold.resolve("30000", "50000")).isEqualTo(30_000);
        // 前后空白容忍
        assertThat(AssistantEvictionThreshold.resolve(" 30000 ", "50000")).isEqualTo(30_000);
    }

    @Test
    void 空白与非数字_一律视为未配置() {
        assertThat(AssistantEvictionThreshold.resolve(null, null)).isEqualTo(80_000);
        assertThat(AssistantEvictionThreshold.resolve("  ", " \t ")).isEqualTo(80_000);
        assertThat(AssistantEvictionThreshold.resolve("abc", "abc")).isEqualTo(80_000);
        assertThat(AssistantEvictionThreshold.resolve("30000abc", "abc")).isEqualTo(80_000);
    }

    @Test
    void 零与负数_非法回默认() {
        assertThat(AssistantEvictionThreshold.resolve("0", "0")).isEqualTo(80_000);
        assertThat(AssistantEvictionThreshold.resolve("-1", "-1")).isEqualTo(80_000);
    }

    @Test
    void 上下边界_含端点() {
        assertThat(AssistantEvictionThreshold.resolve("1000", null)).isEqualTo(1_000);
        assertThat(AssistantEvictionThreshold.resolve("999", null)).isEqualTo(80_000);
        assertThat(AssistantEvictionThreshold.resolve("1000000", null)).isEqualTo(1_000_000);
        assertThat(AssistantEvictionThreshold.resolve("1000001", null)).isEqualTo(80_000);
    }

    @Test
    void 本租户非法_继承默认租户合法值() {
        assertThat(AssistantEvictionThreshold.resolve("abc", "50000")).isEqualTo(50_000);
        assertThat(AssistantEvictionThreshold.resolve(null, "1000")).isEqualTo(1_000);
    }

    @Test
    void 双非法_回80000() {
        assertThat(AssistantEvictionThreshold.resolve("abc", "0")).isEqualTo(80_000);
        assertThat(AssistantEvictionThreshold.resolve("999", "1000001")).isEqualTo(80_000);
    }

    @Test
    void read配置服务缺失_回默认不抛() {
        assertThatCode(() -> AssistantEvictionThreshold.read(null, "000001"))
            .doesNotThrowAnyException();
        assertThat(AssistantEvictionThreshold.read(null, "000001")).isEqualTo(80_000);
        // 租户空/空白同样安全
        assertThat(AssistantEvictionThreshold.read(null, null)).isEqualTo(80_000);
        assertThat(AssistantEvictionThreshold.read(null, " ")).isEqualTo(80_000);
    }

    @Test
    void read读取抛异常_回默认不炸() {
        ConfigService boom = new FixedConfigService(null) {
            @Override
            public String getConfigValue(String configKey) {
                throw new IllegalStateException("db down");
            }
        };
        assertThat(AssistantEvictionThreshold.read(boom, "000001")).isEqualTo(80_000);
    }

    @Test
    void read直读分支_合法值生效非法值回默认() {
        // 纯单测无 Spring 上下文：isEnable 抛异常按未启用直读，两段同值，本租户段生效
        assertThat(AssistantEvictionThreshold.read(new FixedConfigService("30000"), "000001"))
            .isEqualTo(30_000);
        assertThat(AssistantEvictionThreshold.read(new FixedConfigService("abc"), "000001"))
            .isEqualTo(80_000);
        assertThat(AssistantEvictionThreshold.read(new FixedConfigService(null), "000001"))
            .isEqualTo(80_000);
    }

    /** 最小桩：getConfigValue 固定返回，其余方法本读取路径用不到（空实现）；可被子类覆写成抛异常 */
    private static class FixedConfigService implements ConfigService {

        private final String value;

        private FixedConfigService(String value) {
            this.value = value;
        }

        @Override
        public String getConfigValue(String configKey) {
            return value;
        }

        @Override
        public Dict getConfigMap(String configKey) {
            return new Dict();
        }

        @Override
        public List<Dict> getConfigArrayMap(String configKey) {
            return List.of();
        }

        @Override
        public <T> T getConfigObject(String configKey, Class<T> clazz) {
            return null;
        }

        @Override
        public <T> List<T> getConfigArray(String configKey, Class<T> clazz) {
            return List.of();
        }
    }
}
