package org.dromara.assistant.core;

import org.dromara.common.tenant.helper.TenantHelper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

/**
 * 切租户包装的回归护栏（2026-11-11 STEP02）。
 *
 * <p>钉住的是「先存后还」这件事本身：{@code TenantHelper.dynamic} 的 finally 会
 * {@code clearDynamic()}，在已登录线程上会删掉超管「切换单位」的持久态（Redis+SaStorage）。
 * 所以包装必须在切之前记下原值、切完把它还回去——包括 body 抛异常的路径。</p>
 */
@Tag("dev")
@Tag("local")
class AssistantTenantScopeTest {

    @Test
    void restoresPreviousTenantAfterCall() {
        try (MockedStatic<TenantHelper> th = Mockito.mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getDynamic).thenReturn("123456");
            th.when(() -> TenantHelper.dynamic(eq("456829"), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());

            String out = AssistantTenantScope.call("456829", () -> "读到本租户的值");

            assertThat(out).isEqualTo("读到本租户的值");
            th.verify(() -> TenantHelper.setDynamic("123456", true));
        }
    }

    @Test
    void restoresEvenWhenBodyThrows() {
        try (MockedStatic<TenantHelper> th = Mockito.mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getDynamic).thenReturn("123456");
            th.when(() -> TenantHelper.dynamic(eq("456829"), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());

            assertThatThrownBy(() -> AssistantTenantScope.<Object>call("456829", () -> {
                throw new IllegalStateException("boom");
            })).isInstanceOf(IllegalStateException.class).hasMessage("boom");

            // 抛异常也不能留在别人的租户宇宙里
            th.verify(() -> TenantHelper.setDynamic("123456", true));
        }
    }

    @Test
    void noRestoreWhenThereWasNoPreviousTenant() {
        try (MockedStatic<TenantHelper> th = Mockito.mockStatic(TenantHelper.class)) {
            th.when(TenantHelper::getDynamic).thenReturn(null);
            th.when(() -> TenantHelper.dynamic(eq("456829"), any(Supplier.class)))
                .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(1)).get());

            assertThat(AssistantTenantScope.call("456829", () -> "v")).isEqualTo("v");
            // 本来就没有「切换单位」态，就不该凭空写一个（写 null 等于伪造上下文）
            th.verify(() -> TenantHelper.setDynamic(anyString(), anyBoolean()), Mockito.never());
        }
    }

    @Test
    void blankTenantRunsDirectlyWithoutTouchingTenantHelper() {
        try (MockedStatic<TenantHelper> th = Mockito.mockStatic(TenantHelper.class)) {
            assertThat(AssistantTenantScope.call("  ", () -> "直读")).isEqualTo("直读");
            th.verifyNoInteractions();
        }
    }
}
