package com.smartdock.profile.engine;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 来源配置入参归一化是纯函数，直接测(三审 dsflash P3: 新功能配 local 单测)。
 */
@Tag("local")
class ProfileSourceConfigServiceTest {

    @Test
    void sourceTrim后原样返回() {
        assertThat(ProfileSourceConfigService.normalizeSource("  dcs ")).isEqualTo("dcs");
        assertThat(ProfileSourceConfigService.normalizeSource("myapp")).isEqualTo("myapp");
    }

    @Test
    void source空白或超长报错() {
        assertThatThrownBy(() -> ProfileSourceConfigService.normalizeSource(null))
                .isInstanceOf(ServiceException.class).hasMessageContaining("source 不能为空");
        assertThatThrownBy(() -> ProfileSourceConfigService.normalizeSource("   "))
                .isInstanceOf(ServiceException.class).hasMessageContaining("source 不能为空");
        assertThatThrownBy(() -> ProfileSourceConfigService.normalizeSource("a".repeat(65)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("source 最长 64 字");
    }

    @Test
    void 显示名空白归null其余trim() {
        assertThat(ProfileSourceConfigService.normalizeDisplayName(null)).isNull();
        assertThat(ProfileSourceConfigService.normalizeDisplayName("  ")).isNull();
        assertThat(ProfileSourceConfigService.normalizeDisplayName(" 数据采集 ")).isEqualTo("数据采集");
    }

    @Test
    void 显示名超64字报错() {
        assertThatThrownBy(() -> ProfileSourceConfigService.normalizeDisplayName("长".repeat(65)))
                .isInstanceOf(ServiceException.class).hasMessageContaining("显示名最长 64 字");
    }

    @Test
    void enabled归一化_null保留语义() {
        // null=未传(保留原值), "0"=停用, 其余非空=启用
        assertThat(ProfileSourceConfigService.normalizeEnabled(null)).isNull();
        assertThat(ProfileSourceConfigService.normalizeEnabled("0")).isEqualTo("0");
        assertThat(ProfileSourceConfigService.normalizeEnabled("1")).isEqualTo("1");
        assertThat(ProfileSourceConfigService.normalizeEnabled("yes")).isEqualTo("1");
    }
}
