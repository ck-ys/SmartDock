package org.dromara.test.adapter;

import com.smartdock.profile.ProfileEventPublisher;
import com.smartdock.profile.event.ProfileEventBo;
import org.dromara.common.core.service.UserService;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.web.adapter.RiskBehaviorSinkAdapter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 风控行为埋点适配器单测（批4 补测，2026-10-18）。
 *
 * <p>契约：record() → source=risk 画像事件；空租户/空用户丢弃并留 warn；
 * 事件发布失败不抛（埋点不属于业务主事务）。</p>
 */
@Tag("local")
class RiskBehaviorSinkAdapterTest {

    private final ProfileEventPublisher publisher = mock(ProfileEventPublisher.class);
    private final UserService userService = mock(UserService.class);
    private final RiskBehaviorSinkAdapter adapter = new RiskBehaviorSinkAdapter(publisher, userService);

    private static MockedStatic<TenantHelper> tenantPassThrough() {
        MockedStatic<TenantHelper> t = mockStatic(TenantHelper.class);
        // 两个重载都要桩: publish 返回 boolean 时 lambda 落在 Supplier 版, 空体 lambda 落在 Runnable 版
        t.when(() -> TenantHelper.dynamic(anyString(), any(Runnable.class)))
            .thenAnswer(inv -> {
                ((Runnable) inv.getArgument(1)).run();
                return null;
            });
        t.when(() -> TenantHelper.dynamic(anyString(), any(java.util.function.Supplier.class)))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        return t;
    }

    @Test
    void recordPublishesRiskSourcedProfileEvent() {
        try (MockedStatic<TenantHelper> ignored = tenantPassThrough()) {
            adapter.record("000000", 9L, "RISK", new Date(1700000000000L), "1.2.3.4", 5L, 6L, "{\"k\":1}");
        }
        ArgumentCaptor<ProfileEventBo> cap = ArgumentCaptor.forClass(ProfileEventBo.class);
        verify(publisher).publish(cap.capture());
        ProfileEventBo e = cap.getValue();
        assertEquals(com.smartdock.profile.event.ProfileSources.UNIFIED, e.getSource());
        assertEquals("RISK", e.getEventType());
        assertEquals("9", e.getSubjectId());
        assertTrue(e.getEventUid().startsWith("risk:RISK:9:"));
        assertEquals(5L, e.getProps().get("missionId"));
    }

    @Test
    void blankTenantDropsEventWithoutPublish() {
        adapter.record("  ", 9L, "RISK", null, null, null, null, null);
        verify(publisher, never()).publish(any(ProfileEventBo.class));
    }

    @Test
    void nullUserDropsEventWithoutPublish() {
        adapter.record("000000", null, "RISK", null, null, null, null, null);
        verify(publisher, never()).publish(any(ProfileEventBo.class));
    }

    @Test
    void publishFailureIsWarnSoft() {
        when(publisher.publish(any(ProfileEventBo.class))).thenThrow(new RuntimeException("boom"));
        try (MockedStatic<TenantHelper> ignored = tenantPassThrough()) {
            assertDoesNotThrow(() -> adapter.record("000000", 9L, "RISK", null, null, null, null, null));
        }
    }
}
