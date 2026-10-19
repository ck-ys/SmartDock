package com.smartdock.service.support;

import com.smartdock.mapper.DcsWalletAlertStateMapper;
import com.smartdock.mapper.WalletAlertRecipientMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对账告警发布单测(批C 2026-09-24, glm53 P1-1/P2-4 补; 批2 2026-09-25 Q6:A/Q7-⑨ 刷新):
 * ① 默认租户超管直收(事件字段契约+noticeType字面量+每日去重键);
 * ② 无 admin 角色用户的租户(如 900001 仅普通用户)降级平台超管: 通知挂 000000 租户+内容标注真实租户,
 *    去重键仍按真实业务租户(降级收件不影响"每租户每日一告");
 * ③ 发布失败吞异常不外抛(定时链路不中断)。
 *
 * @author smartdock
 */
@ExtendWith(MockitoExtension.class)
class WalletAlertServicePublishReconcileUnitTest {

    @Mock
    private DcsWalletAlertStateMapper alertStateMapper;
    @Mock
    private ApplicationEventPublisher eventPublisher;
    @Mock
    private WalletAlertRecipientMapper recipientMapper;

    private WalletAlertService service() {
        return new WalletAlertService(alertStateMapper, eventPublisher, recipientMapper);
    }

    @Test
    @Tag("dev")
    void defaultTenantSuperAdminReceivesDirectly() {
        when(recipientMapper.selectTenantAdminUserIds(eq("000000"))).thenReturn(List.of(1L));

        service().publishReconcileAlert("000000", "钱包对账发现新差异", "明细", Map.of("k", 1));

        ArgumentCaptor<WalletAlertEvent> captor = ArgumentCaptor.forClass(WalletAlertEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        WalletAlertEvent event = captor.getValue();
        assertThat(event.getTenantId()).isEqualTo("000000");
        assertThat(event.getRecipientUserIds()).containsExactly(1L);
        assertThat(event.getNoticeType()).isEqualTo("WALLET_RECONCILE_MISMATCH");
        assertThat(event.getRefUrl()).isEqualTo("/tenant/tenant-wallet");
        assertThat(event.getContent()).isEqualTo("明细");
        // Q6:A 每租户每日一告幂等键: walletReconcile:{tenantId}:{yyyyMMdd 北京日}
        assertThat(event.getBusinessDedupKey()).matches("walletReconcile:000000:\\d{8}");
    }

    @Test
    @Tag("dev")
    void tenantWithoutAdminFallsBackToPlatformSuperAdmin() {
        // glm53 P1-1(批C): 900001 无 admin 角色用户, 原逻辑跳过=无人接收;
        // 批2 Q7-⑨ 根治后口径: sys_user_role 真角色解析, 空→降级收件人=平台超管1,
        // 通知挂 000000(超管查询上下文), 内容前缀标注真实租户; 去重键用真实租户
        when(recipientMapper.selectTenantAdminUserIds(eq("900001"))).thenReturn(List.of());

        service().publishReconcileAlert("900001", "钱包对账发现新差异", "明细", Map.of());

        ArgumentCaptor<WalletAlertEvent> captor = ArgumentCaptor.forClass(WalletAlertEvent.class);
        verify(eventPublisher).publishEvent(captor.capture());
        WalletAlertEvent event = captor.getValue();
        assertThat(event.getTenantId()).isEqualTo("000000");
        assertThat(event.getRecipientUserIds()).containsExactly(1L);
        assertThat(event.getContent()).startsWith("【租户 900001】明细");
        assertThat(event.getBusinessDedupKey()).matches("walletReconcile:900001:\\d{8}");
    }

    @Test
    @Tag("dev")
    void publishFailureIsSwallowedNotThrown() {
        when(recipientMapper.selectTenantAdminUserIds(eq("000000"))).thenReturn(List.of(1L));
        doThrow(new RuntimeException("outbox down")).when(eventPublisher).publishEvent(any(WalletAlertEvent.class));

        assertThatCode(() -> service().publishReconcileAlert("000000", "t", "c", Map.of()))
            .doesNotThrowAnyException();
    }
}
