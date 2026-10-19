package org.dromara.test.adapter;

import com.smartdock.dcs.service.DcsNoticeService;
import org.dromara.common.tenant.helper.TenantHelper;
import com.smartdock.service.support.WalletAlertEvent;
import org.dromara.web.adapter.WalletAlertNoticeAdapter;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 钱包告警通知适配器单测（批4 端口补齐，2026-10-18）。
 *
 * <p>契约：同步消费 WalletAlertEvent，遍历收件人逐个落站内通知；
 * noticeType/title/content 逐字透传；空租户/空收件人丢弃留 warn。</p>
 */
@Tag("local")
class WalletAlertNoticeAdapterTest {

    private final DcsNoticeService noticeService = mock(DcsNoticeService.class);
    private final WalletAlertNoticeAdapter adapter = new WalletAlertNoticeAdapter(noticeService);

    private static MockedStatic<TenantHelper> tenantPassThrough() {
        MockedStatic<TenantHelper> t = mockStatic(TenantHelper.class);
        t.when(() -> TenantHelper.dynamic(anyString(), any(Runnable.class)))
            .thenAnswer(inv -> {
                ((Runnable) inv.getArgument(1)).run();
                return null;
            });
        t.when(() -> TenantHelper.dynamic(anyString(), any(java.util.function.Supplier.class)))
            .thenAnswer(inv -> ((java.util.function.Supplier<?>) inv.getArgument(1)).get());
        return t;
    }

    private static WalletAlertEvent event(String tenantId, List<Long> recipients) {
        return new WalletAlertEvent(tenantId, recipients, "WALLET_LOW_BALANCE",
            "钱包余额不足提醒", "余额低于阈值", "/tenant/tenant-wallet", Map.of("walletId", 1L), null);
    }

    @Test
    void notifiesEveryRecipientWithPassthroughFields() {
        try (MockedStatic<TenantHelper> ignored = tenantPassThrough()) {
            adapter.onWalletAlert(event("000000", List.of(1L, 2L)));
        }
        // 无键链统一走 notifyIdem(键=null 由服务层退化为普通 notify)
        verify(noticeService, times(2)).notifyIdem(anyLong(), eq("WALLET_LOW_BALANCE"),
            eq("钱包余额不足提醒"), eq("余额低于阈值"), isNull(), isNull());
    }

    @Test
    void dedupKeyRoutedToIdempotentNotify() {
        WalletAlertEvent withKey = new WalletAlertEvent("000000", List.of(1L), "CREDIT_TANK_LOW",
            "加油包低位", "余额低", "/tenant/tenant-wallet", Map.of(), "creditTankLow:000000:202610");
        try (MockedStatic<TenantHelper> ignored = tenantPassThrough()) {
            adapter.onWalletAlert(withKey);
        }
        // 频次闸(批4 回修): 键逐字透传给幂等通知, 不走普通 notify
        verify(noticeService).notifyIdem(eq(1L), eq("CREDIT_TANK_LOW"), anyString(), anyString(),
            isNull(), eq("creditTankLow:000000:202610"));
        verify(noticeService, never()).notify(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void nullRecipientSkippedOthersStillNotified() {
        try (MockedStatic<TenantHelper> ignored = tenantPassThrough()) {
            adapter.onWalletAlert(event("000000", java.util.Arrays.asList(1L, null, 3L)));
        }
        verify(noticeService, times(2)).notifyIdem(anyLong(), anyString(), anyString(), anyString(),
            any(), any());
    }

    @Test
    void blankTenantDropsWithoutNotify() {
        adapter.onWalletAlert(event("", List.of(1L)));
        verify(noticeService, never()).notifyIdem(anyLong(), anyString(), anyString(), anyString(),
            any(), any());
        verify(noticeService, never()).notify(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void emptyRecipientsDropsWithoutNotify() {
        adapter.onWalletAlert(event("000000", List.of()));
        verify(noticeService, never()).notifyIdem(anyLong(), anyString(), anyString(), anyString(),
            any(), any());
        verify(noticeService, never()).notify(anyLong(), anyString(), anyString(), anyString(), any());
    }
}
