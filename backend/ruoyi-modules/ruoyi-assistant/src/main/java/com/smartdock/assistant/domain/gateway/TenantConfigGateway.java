package com.smartdock.assistant.domain.gateway;

import java.math.BigDecimal;
import java.util.List;

/**
 * 批 8B 红桥（2026-11-21 板 Q3 ★案1）：自动化/摘要内核搬进新四层后，
 * 租户助手配置仍住在 legacy（AssistantConfigService）——内核只许经本桥按语义取用，
 * legacy 类型（AssistantConfig / EffectiveConfig）一律不得越过桥面。
 *
 * <p>五个方法对应内核原来的 7 处直连：assertOpen / dailyBudget（Runner 四道闸）、
 * digestEnabled（内置项状态）、digestEnabledTenantIds（08:00 摘要扫描，只给 ID 不给配置行）、
 * setDigestEnabled（内置项开关，原 raw→new→update 舞步整体搬进桥实现）。</p>
 */
public interface TenantConfigGateway {

    /** 租户助手总开关关闭时抛 ServiceException（文案沿用配置服务原口径） */
    void assertOpen(String tenantId);

    /** 日预算（对应 EffectiveConfig.dailyBudgetAmount） */
    BigDecimal dailyBudget(String tenantId);

    /** 内置「每日未交摘要」开关（租户配置 digest_enabled = "1"） */
    boolean digestEnabled(String tenantId);

    /** 打开了摘要开关的租户 ID 清单（08:00 job 扫描用） */
    List<String> digestEnabledTenantIds();

    /** 翻译摘要开关（租户无配置行时先落一行默认配置再改） */
    void setDigestEnabled(String tenantId, boolean enabled);
}
