package com.smartdock.service.support;

import com.smartdock.domain.DcsAiCreditSubscription;
import com.smartdock.mapper.DcsAiCreditSubscriptionMapper;
import com.smartdock.service.IDcsAiCreditService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 积分侧告警扫描单测（AI 缺口批 Q1/Q2，2026-10-14 拍板全A）：
 * ① 到期分档纯函数（&gt;3 不告 / 1~3 即将到期 / ≤0 到期）；
 * ② 即将到期与到期各一次（键 creditSubExpiring/creditSubExpired:{subId}）；
 * ③ 周池用完每周一告（仅消费方式 CREDIT 且 weeklyUsed&gt;0）；
 * ④ 加油包将罄每月一告（0&lt;余额&lt;10）；充足/有余额不告；
 * ⑤ 单租户失败不断当日扫描（per-tenant 兜底）。
 */
@ExtendWith(MockitoExtension.class)
class CreditAlertSchedulerUnitTest {

    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");

    @Mock
    private DcsAiCreditSubscriptionMapper subscriptionMapper;
    @Mock
    private IDcsAiCreditService creditService;
    @Mock
    private WalletAlertService walletAlertService;

    private CreditAlertScheduler scheduler() {
        return new CreditAlertScheduler(subscriptionMapper, creditService, walletAlertService);
    }

    private static Date atDate(LocalDate d) {
        return Date.from(d.atStartOfDay(BEIJING).toInstant());
    }

    private static DcsAiCreditSubscription sub(long id, String tenantId, LocalDate endDate) {
        DcsAiCreditSubscription s = new DcsAiCreditSubscription();
        s.setId(id);
        s.setTenantId(tenantId);
        s.setPlanCode("STD");
        s.setPlanName("标准档");
        s.setStartTime(atDate(endDate.minusDays(28)));
        s.setEndTime(atDate(endDate));
        s.setStatus("1");
        return s;
    }

    private static IDcsAiCreditService.CreditSummary summary(long weeklyUsed, long weeklyCap,
                                                             long tankBalance, Date weekStart) {
        // 业务反馈批1015b Q12：CreditSummary 加 totalQuota/termUsed 两尾参（总额展示口径，预警调度不消费 → 0 占位）
        return new IDcsAiCreditService.CreditSummary(true, "STD", "标准档",
            atDate(LocalDate.now(BEIJING).plusDays(20)), weeklyUsed, weeklyCap, 0, 100, 5,
            tankBalance, weekStart, null, weeklyUsed, weeklyUsed, 0, 0);
    }

    @Test
    @Tag("dev")
    void 到期分档纯函数_窗口内外各归其位() {
        assertThat(CreditAlertScheduler.classifyExpiry(5)).isNull();
        assertThat(CreditAlertScheduler.classifyExpiry(4)).isNull();
        assertThat(CreditAlertScheduler.classifyExpiry(3)).isEqualTo("EXPIRING");
        assertThat(CreditAlertScheduler.classifyExpiry(1)).isEqualTo("EXPIRING");
        assertThat(CreditAlertScheduler.classifyExpiry(0)).isEqualTo("EXPIRED");
        assertThat(CreditAlertScheduler.classifyExpiry(-2)).isEqualTo("EXPIRED");
    }

    @Test
    @Tag("dev")
    void 即将到期_每订阅一次_键与文案落位() {
        DcsAiCreditSubscription s = sub(123L, "T1", LocalDate.now(BEIJING).plusDays(2));
        when(subscriptionMapper.selectList(any()))
            .thenReturn(List.of(s))
            .thenReturn(Collections.emptyList()); // 第二次查询(活跃池扫描)为空
        scheduler().scanNow("test");
        verify(walletAlertService).publishCreditAlert(eq("T1"), eq("CREDIT_SUBSCRIPTION_EXPIRING"),
            eq("AI 积分套餐即将到期"), anyString(), eq("/tenant/ai-credit"), any(),
            eq("creditSubExpiring:123"));
    }

    @Test
    @Tag("dev")
    void 今日到期与已过期共用一键_每订阅至多两告() {
        DcsAiCreditSubscription today = sub(200L, "T1", LocalDate.now(BEIJING));
        when(subscriptionMapper.selectList(any()))
            .thenReturn(List.of(today))
            .thenReturn(Collections.emptyList());
        scheduler().scanNow("test");
        verify(walletAlertService).publishCreditAlert(eq("T1"), eq("CREDIT_SUBSCRIPTION_EXPIRING"),
            eq("AI 积分套餐今日到期"), anyString(), anyString(), any(), eq("creditSubExpired:200"));

        DcsAiCreditSubscription past = sub(201L, "T1", LocalDate.now(BEIJING).minusDays(4));
        org.mockito.Mockito.clearInvocations(walletAlertService);
        when(subscriptionMapper.selectList(any()))
            .thenReturn(List.of(past))
            .thenReturn(Collections.emptyList());
        scheduler().scanNow("test");
        verify(walletAlertService).publishCreditAlert(eq("T1"), eq("CREDIT_SUBSCRIPTION_EXPIRING"),
            eq("AI 积分套餐已到期"), anyString(), anyString(), any(), eq("creditSubExpired:201"));
    }

    @Test
    @Tag("dev")
    void 周池用完_每周一告_键含weekStart() {
        LocalDate weekStart = LocalDate.now(BEIJING).minusDays(3);
        DcsAiCreditSubscription s = sub(300L, "T9", LocalDate.now(BEIJING).plusDays(20));
        when(subscriptionMapper.selectList(any()))
            .thenReturn(Collections.emptyList()) // 到期扫描为空
            .thenReturn(List.of(s));
        when(creditService.consumeMode("T9")).thenReturn("CREDIT");
        when(creditService.summary("T9")).thenReturn(summary(500, 500, 0, atDate(weekStart)));
        scheduler().scanNow("test");
        String weekKey = weekStart.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        verify(walletAlertService).publishCreditAlert(eq("T9"), eq("CREDIT_WEEK_EXHAUSTED"),
            eq("AI 周池积分已用完"), anyString(), anyString(), any(),
            eq("creditWeekExhausted:T9:" + weekKey));
    }

    @Test
    @Tag("dev")
    void 钱包模式跳过_积分余量不告() {
        DcsAiCreditSubscription s = sub(301L, "T9", LocalDate.now(BEIJING).plusDays(20));
        when(subscriptionMapper.selectList(any()))
            .thenReturn(Collections.emptyList())
            .thenReturn(List.of(s));
        when(creditService.consumeMode("T9")).thenReturn("WALLET");
        scheduler().scanNow("test");
        verifyNoInteractions(walletAlertService);
    }

    @Test
    @Tag("dev")
    void 加油包将罄_每月一告_充足或有周池余额不告() {
        DcsAiCreditSubscription s = sub(302L, "T9", LocalDate.now(BEIJING).plusDays(20));
        when(subscriptionMapper.selectList(any()))
            .thenReturn(Collections.emptyList())
            .thenReturn(List.of(s));
        when(creditService.consumeMode("T9")).thenReturn("CREDIT");
        when(creditService.summary("T9")).thenReturn(summary(100, 500, 5, atDate(LocalDate.now(BEIJING))));
        scheduler().scanNow("test");
        String monthKey = LocalDate.now(BEIJING).format(DateTimeFormatter.ofPattern("yyyyMM"));
        verify(walletAlertService).publishCreditAlert(eq("T9"), eq("CREDIT_TANK_LOW"),
            eq("AI 加油包余额将罄"), anyString(), anyString(), any(),
            eq("creditTankLow:T9:" + monthKey));

        // 健康(周池有余额+加油包充足)→零告警
        org.mockito.Mockito.clearInvocations(walletAlertService);
        when(creditService.summary("T9")).thenReturn(summary(100, 500, 300, atDate(LocalDate.now(BEIJING))));
        scheduler().scanNow("test");
        verify(walletAlertService, never()).publishCreditAlert(anyString(), anyString(), anyString(),
            anyString(), anyString(), any(), anyString());
    }

    @Test
    @Tag("dev")
    void 单租户失败不断扫_其余租户照告() {
        DcsAiCreditSubscription bad = sub(400L, "T_BAD", LocalDate.now(BEIJING).plusDays(20));
        DcsAiCreditSubscription good = sub(401L, "T_OK", LocalDate.now(BEIJING).plusDays(20));
        when(subscriptionMapper.selectList(any()))
            .thenReturn(Collections.emptyList())
            .thenReturn(List.of(bad, good));
        when(creditService.consumeMode("T_BAD")).thenThrow(new RuntimeException("db down"));
        when(creditService.consumeMode("T_OK")).thenReturn("CREDIT");
        LocalDate ws = LocalDate.now(BEIJING);
        when(creditService.summary("T_OK")).thenReturn(summary(999, 999, 0, atDate(ws)));
        scheduler().scanNow("test");
        verify(walletAlertService).publishCreditAlert(eq("T_OK"), eq("CREDIT_WEEK_EXHAUSTED"),
            anyString(), anyString(), anyString(), any(), anyString());
    }
}
