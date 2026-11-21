package com.smartdock.assistant.infrastructure;

import com.smartdock.assistant.domain.gateway.TenantConfigGateway;
import com.smartdock.assistant.legacy.domain.DcsAssistantConfig;
import com.smartdock.assistant.legacy.service.AssistantConfigService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批 8B 红桥实现：包一层 legacy {@link AssistantConfigService}，把 5 个语义动作翻译过去。
 * legacy 类型只活在本类方法体内，不越过桥面（effective() 的 digestEnabled 在业务写入下恒为 "1"/"0"
 * ——DDL 无 CHECK，手工改库属例外；双分支都有平台默认，无行不落 null）。
 */
@Component
@RequiredArgsConstructor
public class TenantConfigGatewayImpl implements TenantConfigGateway {

    private final AssistantConfigService configService;

    @Override
    public void assertOpen(String tenantId) {
        configService.assertOpen(tenantId);
    }

    @Override
    public BigDecimal dailyBudget(String tenantId) {
        return configService.effective(tenantId).dailyBudgetAmount();
    }

    @Override
    public boolean digestEnabled(String tenantId) {
        return "1".equals(configService.effective(tenantId).digestEnabled());
    }

    @Override
    public List<String> digestEnabledTenantIds() {
        return configService.digestEnabledTenants().stream()
            .map(DcsAssistantConfig::getTenantId)
            .toList();
    }

    @Override
    public void setDigestEnabled(String tenantId, boolean enabled) {
        // 原 toggleBuiltin 内联舞步（批 8B 前 Service:172-178）原样搬来：无行先落一行
        DcsAssistantConfig cfg = configService.raw(tenantId);
        if (cfg == null) {
            cfg = new DcsAssistantConfig();
            cfg.setTenantId(tenantId);
        }
        cfg.setDigestEnabled(enabled ? "1" : "0");
        configService.update(cfg);
    }
}
