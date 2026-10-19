package com.smartdock.service.support;

import com.smartdock.config.WalletReconcileProperties;
import com.smartdock.domain.vo.WalletReconcileChainBreakVo;
import com.smartdock.domain.vo.WalletReconcileDeductNullVo;
import com.smartdock.domain.vo.WalletReconcileNetVo;
import com.smartdock.mapper.WalletReconcileMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 钱包对账巡检单测(批C 2026-09-24 上线; 批1 2026-09-25 基线配置化重构):
 * ① 三项基线过滤纯函数(dev 基线/交付零基线两套语义);
 * ② 编排路径(全干净零告警/差异按租户聚合成告警且 extra 计数同桶/巡检失败不外抛)。
 *
 * @author smartdock
 */
@ExtendWith(MockitoExtension.class)
class WalletReconcileJobUnitTest {

    private static final Long SEED_WALLET = 900000000000004001L;

    /** 开发库基线(与 application-dev.yml dcs.wallet.reconcile 一致) */
    private static final Map<Long, BigDecimal> DEV_BALANCE_DIFFS =
        Map.of(SEED_WALLET, new BigDecimal("0.0001"));
    private static final Set<Long> DEV_CHAIN_BREAK_IDS =
        Set.of(900000000000005001L, 2075251733967265793L);
    private static final Map<String, Long> DEV_DEDUCT_BASELINE = Map.of("000000", 1306L);

    @Mock
    private WalletReconcileMapper reconcileMapper;
    @Mock
    private WalletAlertService walletAlertService;

    private WalletReconcileJob job() {
        WalletReconcileProperties props = new WalletReconcileProperties();
        props.setBalanceDiffs(new java.util.LinkedHashMap<>(DEV_BALANCE_DIFFS));
        props.setChainBreakLedgerIds(new java.util.LinkedHashSet<>(DEV_CHAIN_BREAK_IDS));
        props.setDeductNullBaseline(new java.util.LinkedHashMap<>(DEV_DEDUCT_BASELINE));
        return new WalletReconcileJob(reconcileMapper, walletAlertService, props);
    }

    private WalletReconcileNetVo net(long walletId, String tenantId, String balance, String netSum) {
        WalletReconcileNetVo vo = new WalletReconcileNetVo();
        vo.setWalletId(walletId);
        vo.setTenantId(tenantId);
        vo.setBalanceAmount(new BigDecimal(balance));
        vo.setLedgerNet(new BigDecimal(netSum));
        return vo;
    }

    @Test
    @Tag("dev")
    void balanceDiffBaselineOnlyReportsNewOrDrifted() {
        // 差额0(含基线钱包自愈归零)与基线0.0001一致静默; 漂移/新钱包差额上报
        List<WalletReconcileNetVo> findings = WalletReconcileJob.filterNewBalanceDiffs(List.of(
            net(SEED_WALLET, "000000", "0.3200", "0.3200"),        // 基线钱包差额归零 → 静默
            net(SEED_WALLET, "000000", "0.3201", "0.3200"),        // 与基线0.0001一致 → 静默
            net(SEED_WALLET, "000000", "0.3202", "0.3200"),        // 漂移到0.0002 → 上报
            net(2087093116495503362L, "900001", "10.0000", "10.0000"), // 差额0 → 静默
            net(2099999999999999999L, "900001", "8.0000", "3.0000")), // 新钱包差额5 → 上报
            DEV_BALANCE_DIFFS);
        assertThat(findings).extracting(WalletReconcileNetVo::getWalletId)
            .containsExactly(SEED_WALLET, 2099999999999999999L);
    }

    @Test
    @Tag("dev")
    void chainBreakBaselineSilencesHistoricalBreaks() {
        WalletReconcileChainBreakVo historic = breakRow(900000000000005001L);
        WalletReconcileChainBreakVo fresh = breakRow(2088000000000000001L);
        assertThat(WalletReconcileJob.filterNewChainBreaks(List.of(historic, fresh), DEV_CHAIN_BREAK_IDS))
            .extracting(WalletReconcileChainBreakVo::getLedgerId)
            .containsExactly(2088000000000000001L);
    }

    @Test
    @Tag("dev")
    void deductNullBaselineDriftInEitherDirectionReports() {
        // 与基线一致静默; 增加/减少/新租户出现都算新异常
        assertThat(WalletReconcileJob.filterNewDeductNulls(List.of(
                deduct("000000", 1306L), deduct("000000", 1307L),
                deduct("000000", 1300L), deduct("900001", 1L)), DEV_DEDUCT_BASELINE))
            .extracting(WalletReconcileDeductNullVo::getTenantId)
            .containsExactly("000000", "000000", "900001");
    }

    @Test
    @Tag("dev")
    void deductNullVanishedToZeroIsReportedByReverseCheck() {
        // 基线租户从结果集整体消失(存量被一次性清零, GROUP BY 不产 count=0 行)反向兜住;
        // 900001 计数0与默认基线0一致静默
        List<WalletReconcileDeductNullVo> findings = WalletReconcileJob.filterNewDeductNulls(
            List.of(deduct("900001", 0L)), DEV_DEDUCT_BASELINE);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getTenantId()).isEqualTo("000000");
        assertThat(findings.get(0).getNullBizCount()).isZero();
    }

    @Test
    @Tag("dev")
    void emptyBaselineMatchesDeliveredFreshInstallSemantics() {
        // 交付环境零基线(拍板Q1:A): 无钱包数据/无流水 → 全静默; 一旦出现非零差额立即上报
        assertThat(WalletReconcileJob.filterNewBalanceDiffs(List.of(), Map.of())).isEmpty();
        assertThat(WalletReconcileJob.filterNewChainBreaks(List.of(), Set.of())).isEmpty();
        assertThat(WalletReconcileJob.filterNewDeductNulls(List.of(), Map.of())).isEmpty();
        assertThat(WalletReconcileJob.filterNewBalanceDiffs(
            List.of(net(1L, "000000", "5.0000", "0.0000")), Map.of()))
            .extracting(WalletReconcileNetVo::getWalletId).containsExactly(1L);
        // 客户环境正常使用后: 差额0静默, 空值计数0与零基线一致静默
        assertThat(WalletReconcileJob.filterNewBalanceDiffs(
            List.of(net(1L, "000000", "100.0000", "100.0000")), Map.of())).isEmpty();
        assertThat(WalletReconcileJob.filterNewDeductNulls(
            List.of(deduct("000000", 0L)), Map.of())).isEmpty();
    }

    @Test
    @Tag("dev")
    void cleanBaselineSendsNoAlert() {
        when(reconcileMapper.selectWalletNet()).thenReturn(List.of(
            net(SEED_WALLET, "000000", "0.3201", "0.3200")));
        when(reconcileMapper.selectChainBreaks()).thenReturn(List.of());
        when(reconcileMapper.selectDeductNullByTenant()).thenReturn(List.of(deduct("000000", 1306L)));

        job().reconcile();

        verifyNoInteractions(walletAlertService);
    }

    @Test
    @Tag("dev")
    void newFindingsAggregatePerTenantIntoOneAlertWithTenantScopedCounts() {
        when(reconcileMapper.selectWalletNet()).thenReturn(List.of(
            net(SEED_WALLET, "000000", "0.3202", "0.3200")));   // 漂移0.0002
        when(reconcileMapper.selectChainBreaks()).thenReturn(List.of(
            breakRow(2088000000000000001L)));                    // 新断裂(900001)
        when(reconcileMapper.selectDeductNullByTenant()).thenReturn(List.of(deduct("000000", 1306L)));

        job().reconcile();

        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> extra = ArgumentCaptor.forClass(Map.class);
        verify(walletAlertService).publishReconcileAlert(eq("000000"), eq("钱包对账发现新差异"),
            content.capture(), extra.capture());
        assertThat(content.getValue()).contains("0.0002").contains("已知基线 0.0001");
        // extra 计数按租户分桶(批2 拍板Q7-⑤): 000000 只带自己的 1 条余额差额, 不掺 900001 的断裂
        assertThat(extra.getValue()).containsEntry("balanceDiffs", 1).containsEntry("chainBreaks", 0);
        verify(walletAlertService).publishReconcileAlert(eq("900001"), anyString(), anyString(), anyMap());
    }

    @Test
    @Tag("dev")
    void mapperFailureNeverPropagatesFromScheduledThread() {
        when(reconcileMapper.selectWalletNet()).thenThrow(new RuntimeException("db down"));

        assertThatCode(() -> job().reconcile()).doesNotThrowAnyException();
        verifyNoInteractions(walletAlertService);
    }

    private WalletReconcileChainBreakVo breakRow(long ledgerId) {
        WalletReconcileChainBreakVo vo = new WalletReconcileChainBreakVo();
        vo.setLedgerId(ledgerId);
        vo.setWalletId(ledgerId == 900000000000005001L ? SEED_WALLET : 2087093116495503362L);
        vo.setTenantId(ledgerId == 900000000000005001L ? "000000" : "900001");
        vo.setBalanceBefore(new BigDecimal("7.1825"));
        vo.setPrevAfter(new BigDecimal("4.3650"));
        return vo;
    }

    private WalletReconcileDeductNullVo deduct(String tenantId, long count) {
        WalletReconcileDeductNullVo vo = new WalletReconcileDeductNullVo();
        vo.setTenantId(tenantId);
        vo.setNullBizCount(count);
        return vo;
    }
}
