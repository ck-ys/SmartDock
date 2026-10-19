package com.smartdock.service.support;

import com.smartdock.domain.DcsTenantWallet;
import com.smartdock.domain.DcsWalletAlertState;
import com.smartdock.mapper.DcsTenantWalletMapper;
import com.smartdock.mapper.DcsWalletAlertStateMapper;
import com.smartdock.mapper.WalletAlertRecipientMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 低余额告警生命周期单测(批1 2026-09-25, 拍板 Q4:B 四洞修复的行为锁):
 * ① 日历天判重(同日跳过/昨日可再发——毫秒竞态构造性消灭);
 * ② 通知成功后才消耗当日配额(发布失败状态不落库);
 * ③ 无 admin 角色租户降级平台超管接收(批2 Q7-⑨, 原静默黑洞关闭)且配额照常消耗;
 * ④ 启动补跑门槛纯函数 + 单钱包失败不断当日扫描。
 *
 * @author smartdock
 */
@ExtendWith(MockitoExtension.class)
class WalletAlertLifecycleUnitTest {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    @Mock
    private DcsWalletAlertStateMapper alertStateMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private WalletAlertRecipientMapper recipientMapper;
    @Mock
    private DcsTenantWalletMapper walletMapper;
    @Mock
    private WalletAlertService walletAlertService;

    private WalletAlertService service() {
        return new WalletAlertService(alertStateMapper, eventPublisher, recipientMapper);
    }

    private DcsTenantWallet wallet() {
        DcsTenantWallet wallet = new DcsTenantWallet();
        wallet.setWalletId(900000000000004001L);
        wallet.setTenantId("000000");
        wallet.setBalanceAmount(new BigDecimal("0.3201"));
        wallet.setFrozenAmount(BigDecimal.ZERO);
        return wallet;
    }

    private DcsWalletAlertState stateAt(LocalDate date) {
        DcsWalletAlertState state = new DcsWalletAlertState();
        state.setWalletId(900000000000004001L);
        state.setAlertType("LOW_BALANCE");
        state.setLastAlertTime(Date.from(date.atStartOfDay(BEIJING).plusHours(9).toInstant()));
        return state;
    }

    private void mockAdmin() {
        when(recipientMapper.selectTenantAdminUserIds(eq("000000"))).thenReturn(List.of(1L));
    }

    @Test
    @Tag("dev")
    void sameDayAlertIsSkippedWithoutEventOrStateWrite() {
        when(alertStateMapper.selectOne(any())).thenReturn(stateAt(LocalDate.now(BEIJING)));

        assertThat(service().tryAlert(wallet(), "LOW_BALANCE", BigDecimal.ONE)).isFalse();

        verify(eventPublisher, never()).publishEvent(any(WalletAlertEvent.class));
        verify(alertStateMapper, never()).updateById(any(DcsWalletAlertState.class));
    }

    @Test
    @Tag("dev")
    void yesterdayAlertAllowsTodayAndConsumesQuotaAfterPublish() {
        // 日历天判重: 上次告警在昨天(哪怕只差1毫秒跨天) → 今天可再发
        when(alertStateMapper.selectOne(any())).thenReturn(stateAt(LocalDate.now(BEIJING).minusDays(1)));
        mockAdmin();

        assertThat(service().tryAlert(wallet(), "LOW_BALANCE", BigDecimal.ONE)).isTrue();

        // 状态推进必须在事件发布之后(通知失败不消耗当日配额)
        InOrder order = inOrder(eventPublisher, alertStateMapper);
        order.verify(eventPublisher).publishEvent(any(WalletAlertEvent.class));
        order.verify(alertStateMapper).updateById(any(DcsWalletAlertState.class));
    }

    @Test
    @Tag("dev")
    void publishFailureNeverConsumesDailyQuota() {
        // 通知发布异常上抛(事务回滚语义) → 状态不落库 → 当日配额未消耗, 下次补跑可重试
        when(alertStateMapper.selectOne(any())).thenReturn(stateAt(LocalDate.now(BEIJING).minusDays(1)));
        mockAdmin();
        org.mockito.Mockito.doThrow(new RuntimeException("outbox down"))
            .when(eventPublisher).publishEvent(any(WalletAlertEvent.class));

        assertThatThrownBy(() -> service().tryAlert(wallet(), "LOW_BALANCE", BigDecimal.ONE))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("outbox down");
        verify(alertStateMapper, never()).updateById(any(DcsWalletAlertState.class));
        verify(alertStateMapper, never()).insert(any(DcsWalletAlertState.class));
    }

    @Test
    @Tag("dev")
    void adminLessTenantFallsBackToSuperAdminAndConsumesQuota() {
        // 批2 Q7-⑨: 无 admin 角色用户的租户(如 900001)降级平台超管接收——通知挂 000000+内容标注真实租户,
        // 原"无人接收静默消耗配额"的低余额告警黑洞关闭; 配额照常消耗(发了通知)
        DcsTenantWallet wallet = wallet();
        wallet.setTenantId("900001");
        when(alertStateMapper.selectOne(any())).thenReturn(null);
        when(recipientMapper.selectTenantAdminUserIds(eq("900001"))).thenReturn(List.of());

        assertThat(service().tryAlert(wallet, "LOW_BALANCE", BigDecimal.ONE)).isTrue();

        ArgumentCaptor<WalletAlertEvent> captor = ArgumentCaptor.forClass(WalletAlertEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        WalletAlertEvent event = captor.getValue();
        assertThat(event.getTenantId()).isEqualTo("000000");
        assertThat(event.getRecipientUserIds()).containsExactly(1L);
        assertThat(event.getContent()).startsWith("【租户 900001】");
        // 低余额链保持 legacy 去重键(拍板 Q6:A), DB 日历天判重兜底
        assertThat(event.getBusinessDedupKey()).isNull();
        verify(alertStateMapper).insert(any(DcsWalletAlertState.class));
    }

    @Test
    @Tag("dev")
    void catchUpGateOnlyOpensAfterDailyScanHour() {
        // 启动补跑门槛: 9点整及以后才补(未到时刻交给当日 tick), 8:59 不补
        assertThat(WalletAlertScheduler.shouldCatchUpNow(LocalDateTime.of(2026, 9, 24, 8, 59)))
            .isFalse();
        assertThat(WalletAlertScheduler.shouldCatchUpNow(LocalDateTime.of(2026, 9, 24, 9, 0)))
            .isTrue();
        assertThat(WalletAlertScheduler.shouldCatchUpNow(LocalDateTime.of(2026, 9, 24, 21, 34)))
            .isTrue();
    }

    @Test
    @Tag("dev")
    void scanContinuesAfterOneWalletFails() {
        // per-wallet 兜底: 第一个钱包告警抛异常, 第二个钱包照常处理(不中断当日扫描)
        DcsTenantWallet first = wallet();
        DcsTenantWallet second = wallet();
        second.setWalletId(2087093116495503362L);
        when(walletMapper.selectList(any())).thenReturn(List.of(first, second));
        org.mockito.Mockito.when(walletAlertService.tryAlert(any(), anyString(), any()))
            .thenThrow(new RuntimeException("wallet 1 down"))
            .thenReturn(true);

        WalletAlertScheduler scheduler = new WalletAlertScheduler(walletMapper, walletAlertService);
        scheduler.scanLowBalance();

        verify(walletAlertService, org.mockito.Mockito.times(2)).tryAlert(any(), anyString(), any());
    }
}
