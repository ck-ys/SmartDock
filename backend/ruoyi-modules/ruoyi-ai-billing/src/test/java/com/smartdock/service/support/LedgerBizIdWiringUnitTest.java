package com.smartdock.service.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.smartdock.domain.TenantWallet;
import com.smartdock.domain.TenantWalletLedger;
import com.smartdock.mapper.ServiceChargeRuleMapper;
import com.smartdock.mapper.TenantWalletLedgerMapper;
import com.smartdock.mapper.TenantWalletMapper;
import com.smartdock.mapper.WalletAlertStateMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 扣费流水 bizId 接线单测(glm53 终审 P1, 2026-09-24):
 * deductForAiUsage 必须把 usage_id 写进 tenant_wallet_ledger.biz_id——
 * 此前 doDeduct 硬编码 null, "bizId 回填"承诺只兑现一半(DB 实证 DEDUCT 行 biz_id 恒空)。
 * 该列是 G-7 对账 job(钱包 vs 流水 vs 用量三表对账)直连单据的唯一外键。
 *
 * @author smartdock
 */
@ExtendWith(MockitoExtension.class)
class LedgerBizIdWiringUnitTest {

    @BeforeAll
    static void initTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "wallet-bizid-test");
        TableInfoHelper.initTableInfo(assistant, TenantWallet.class);
        TableInfoHelper.initTableInfo(assistant, TenantWalletLedger.class);
    }

    @Mock
    private TenantWalletMapper walletMapper;
    @Mock
    private TenantWalletLedgerMapper ledgerMapper;
    @Mock
    private ServiceChargeRuleMapper ruleMapper;
    @Mock
    private WalletAlertStateMapper alertStateMapper;

    @InjectMocks
    private TenantWalletService service;

    @Test
    @Tag("dev")
    void deductForAiUsageWritesUsageIdIntoLedgerBizId() {
        TenantWallet wallet = new TenantWallet();
        wallet.setWalletId(1L);
        wallet.setTenantId("000000");
        wallet.setWalletType("MAIN");
        wallet.setStatus("ACTIVE");
        wallet.setBalanceAmount(new BigDecimal("100.0000"));
        wallet.setCurrency("CNY");
        wallet.setVersion(0);
        when(walletMapper.selectOne(any())).thenReturn(wallet);
        when(walletMapper.update(any(), any())).thenReturn(1);
        when(ledgerMapper.selectOne(any())).thenReturn(null);

        service.deductForAiUsage("000000", new BigDecimal("0.5000"), "AI_PROFILE_SCORE",
            123456789L, "ai-usage:000000:1:123456789");

        ArgumentCaptor<TenantWalletLedger> captor = ArgumentCaptor.forClass(TenantWalletLedger.class);
        verify(ledgerMapper).insert(captor.capture());
        TenantWalletLedger ledger = captor.getValue();
        assertThat(ledger.getBizId()).isEqualTo(123456789L);
        assertThat(ledger.getBizType()).isEqualTo("DEDUCT");
        assertThat(ledger.getDirection()).isEqualTo("OUT");
        assertThat(ledger.getAmount()).isEqualByComparingTo("0.5");
        assertThat(ledger.getIdempotencyKey()).isEqualTo("ai-usage:000000:1:123456789");
        assertThat(ledger.getBalanceBefore()).isEqualByComparingTo("100");
        assertThat(ledger.getBalanceAfter()).isEqualByComparingTo("99.5");
    }
}
