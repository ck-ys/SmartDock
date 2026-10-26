package com.smartdock.profile.engine;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 规模护栏纯函数测试（批4A：阈值判定边界 + 报错文案含实际数量 + 默认值锚点）。
 */
@Tag("local")
class ProfileScaleGuardTest {

    @Test
    void boundaryEqualsThresholdPassesOverByOneThrows() {
        // 恰好等于阈值=审计安全区边界内, 放行; 超一即拦
        assertThatCode(() -> ProfileScaleGuard.requireWithin(100_000L, 100_000L, "msg"))
            .doesNotThrowAnyException();
        assertThat(ProfileScaleGuard.exceeds(100_000L, 100_000L)).isFalse();
        assertThat(ProfileScaleGuard.exceeds(100_001L, 100_000L)).isTrue();
        assertThatThrownBy(() -> ProfileScaleGuard.requireWithin(100_001L, 100_000L, "超限"))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("超限");
    }

    @Test
    void messagesCarryActualCountAndThreshold() {
        // 报错文案必须含实际数量与阈值——用户据此决定缩窗还是找管理员
        assertThat(ProfileScaleGuard.windowMessage(123_456L, 100_000L))
            .contains("123456").contains("100000").contains("缩小时间范围");
        assertThat(ProfileScaleGuard.segmentMessage(50_001L, 50_000L))
            .contains("50001").contains("50000").contains("分群评估暂不可用");
        assertThat(ProfileScaleGuard.modelMessage(20_001L, 20_000L))
            .contains("20001").contains("20000");
    }

    @Test
    void modelMessageMustNotPointToRecalc() {
        // 设计定案(上轮评审 P0 教训): recalc 是经典评分链, 指路即断头——防文案回归
        assertThat(ProfileScaleGuard.modelMessage(99L, 20_000L))
            .doesNotContain("recalc").doesNotContain("重算");
    }

    @Test
    void directInstantiationKeepsAuditSafeDefaults() {
        // 无 Spring 注入时字段初始化器兜底(直接 new 不至于阈值为 0 全拦)
        ProfileScaleGuard guard = new ProfileScaleGuard(null, null);
        assertThat(guard.maxSegmentCandidates())
            .isEqualTo(ProfileScaleGuard.DEFAULT_MAX_SEGMENT_CANDIDATES)
            .isEqualTo(50_000L);
        assertThat(guard.exceedsSegmentCandidates(50_000L)).isFalse();
        assertThat(guard.exceedsSegmentCandidates(50_001L)).isTrue();
    }
}
