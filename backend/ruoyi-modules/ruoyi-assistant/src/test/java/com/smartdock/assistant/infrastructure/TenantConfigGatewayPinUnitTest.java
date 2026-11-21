package com.smartdock.assistant.infrastructure;

import com.smartdock.assistant.domain.gateway.TenantConfigGateway;
import com.smartdock.assistant.domain.model.AssistantConfig;
import com.smartdock.assistant.service.AssistantConfigService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 批 8B（2026-11-21）：红桥 {@link TenantConfigGateway} 实现的钉子。
 *
 * <p>内核（自动化 Service/Runner、每日摘要 job）换根后只认桥面 5 个语义动作；
 * 本类钉三件事：① digestEnabled 的 "1"/"0" → boolean 翻译逐位等价（effective 双分支
 * 都有默认值，桥面不引入新的 null 面）；② digestEnabledTenantIds 只透出租户 ID
 * （legacy 配置行绝不越过桥面）；③ setDigestEnabled 的 raw→new→update 舞步
 * 与原 toggleBuiltin 内联实现逐行一致（无行先落一行带 tenantId 的默认配置再改）。</p>
 */
@Tag("local")
class TenantConfigGatewayPinUnitTest {

    private static final String TENANT = "000000";

    private static AssistantConfigService.EffectiveConfig eff(String digestEnabled) {
        return new AssistantConfigService.EffectiveConfig(true, true, 50,
            new BigDecimal("100"), "1", 3000, 10, digestEnabled, "助手",
            java.util.Map.of());
    }

    // ── 钉 ① digestEnabled "1"/"0" → boolean ──────────────────────

    @Test
    void digestEnabled翻译为布尔() {
        AssistantConfigService on = mock(AssistantConfigService.class);
        when(on.effective(TENANT)).thenReturn(eff("1"));
        AssistantConfigService off = mock(AssistantConfigService.class);
        when(off.effective(TENANT)).thenReturn(eff("0"));

        TenantConfigGateway gwOn = new TenantConfigGatewayImpl(on);
        TenantConfigGateway gwOff = new TenantConfigGatewayImpl(off);
        assertTrue(gwOn.digestEnabled(TENANT), "digest_enabled=1 → true");
        assertFalse(gwOff.digestEnabled(TENANT), "digest_enabled=0 → false");
    }

    @Test
    void dailyBudget原样透传() {
        AssistantConfigService svc = mock(AssistantConfigService.class);
        BigDecimal budget = new BigDecimal("88.50");
        when(svc.effective(TENANT)).thenReturn(new AssistantConfigService.EffectiveConfig(
            true, true, 50, budget, "1", 3000, 10, "1", "助手", java.util.Map.of()));

        assertEquals(budget, new TenantConfigGatewayImpl(svc).dailyBudget(TENANT));
    }

    // ── 钉 ② 租户清单只透出 ID ─────────────────────────────────────

    @Test
    void digestEnabledTenantIds只给ID不给配置行() {
        AssistantConfigService svc = mock(AssistantConfigService.class);
        AssistantConfig a = new AssistantConfig();
        a.setTenantId("000000");
        AssistantConfig b = new AssistantConfig();
        b.setTenantId("880001");
        when(svc.digestEnabledTenants()).thenReturn(List.of(a, b));

        assertEquals(List.of("000000", "880001"),
            new TenantConfigGatewayImpl(svc).digestEnabledTenantIds());
    }

    // ── 钉 ③ setDigestEnabled 舞步与原实现一致 ────────────────────

    @Test
    void setDigestEnabled无行时先落一行默认配置() {
        AssistantConfigService svc = mock(AssistantConfigService.class);
        when(svc.raw(TENANT)).thenReturn(null);

        new TenantConfigGatewayImpl(svc).setDigestEnabled(TENANT, true);

        org.mockito.ArgumentCaptor<AssistantConfig> cap =
            org.mockito.ArgumentCaptor.forClass(AssistantConfig.class);
        verify(svc).update(cap.capture());
        AssistantConfig saved = cap.getValue();
        assertEquals(TENANT, saved.getTenantId(), "新落的一行必须带上租户 ID");
        assertEquals("1", saved.getDigestEnabled());
    }

    @Test
    void setDigestEnabled有行时改原行不另起一行() {
        AssistantConfigService svc = mock(AssistantConfigService.class);
        AssistantConfig existing = new AssistantConfig();
        existing.setTenantId(TENANT);
        existing.setAssistantName("已有名字");
        when(svc.raw(TENANT)).thenReturn(existing);

        new TenantConfigGatewayImpl(svc).setDigestEnabled(TENANT, false);

        verify(svc).update(existing);
        assertEquals("0", existing.getDigestEnabled());
        assertEquals("已有名字", existing.getAssistantName(), "只翻译开关，同行其它字段不动");
    }

    @Test
    void assertOpen直传配置服务() {
        AssistantConfigService svc = mock(AssistantConfigService.class);
        new TenantConfigGatewayImpl(svc).assertOpen(TENANT);
        verify(svc).assertOpen(eq(TENANT));
        verify(svc, org.mockito.Mockito.never()).update(any());
    }
}
