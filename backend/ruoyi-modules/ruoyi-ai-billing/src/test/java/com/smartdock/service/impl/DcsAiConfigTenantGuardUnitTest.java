package com.smartdock.service.impl;

import org.dromara.common.core.exception.ServiceException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AI 配置 queryById/testConnection 跨租户校验 (F9, 见 document/DCS_AUDIT_2026_06_22.md)。
 *
 * <p>背景: queryById/testConnection 用 TenantHelper.ignore 跨租户读, 普通租户管理员
 * 可枚举 configId 读他人配置的 baseUrl/modelName/remark (apiKey 已 mask), testConnection
 * 还会消耗他人 quota。修复: 抽纯函数校验"当前用户是否可访问该 config"。</p>
 *
 * <p>语义: 超管(superadmin/tenantAdmin)可跨租户维护; 普通租户管理员只能访问
 * tenantId 匹配自己的 config。</p>
 */
class DcsAiConfigTenantGuardUnitTest {

    @Test
    @Tag("dev")
    void superAdminCanAccessAnyConfig() {
        assertThatCode(() ->
            DcsAiConfigServiceImpl.ensureCanAccessAiConfig("000000", "999999", true))
            .doesNotThrowAnyException();
    }

    @Test
    @Tag("dev")
    void sameTenantNonSuperAdminCanAccess() {
        assertThatCode(() ->
            DcsAiConfigServiceImpl.ensureCanAccessAiConfig("000000", "000000", false))
            .doesNotThrowAnyException();
    }

    @Test
    @Tag("dev")
    void crossTenantNonSuperAdminRejected() {
        // 普通租户管理员访问他人租户的 config → 拒绝
        assertThatThrownBy(() ->
            DcsAiConfigServiceImpl.ensureCanAccessAiConfig("000000", "999999", false))
            .isInstanceOf(ServiceException.class)
            .hasMessageContaining("租户");
    }

    @Test
    @Tag("dev")
    void nullConfigTenantRejectedForNonSuperAdmin() {
        // config 没有 tenantId(异常数据), 普通用户拒绝访问(保守)
        assertThatThrownBy(() ->
            DcsAiConfigServiceImpl.ensureCanAccessAiConfig("000000", null, false))
            .isInstanceOf(ServiceException.class);
    }

    @Test
    @Tag("dev")
    void nullConfigTenantAllowedForSuperAdmin() {
        // 超管可以看异常数据(用于排查)
        assertThatCode(() ->
            DcsAiConfigServiceImpl.ensureCanAccessAiConfig("000000", null, true))
            .doesNotThrowAnyException();
    }

    @Test
    @Tag("dev")
    void nullMyTenantRejected() {
        // 当前用户没 tenantId 上下文(异常), 一律拒绝(防绕过)
        assertThatThrownBy(() ->
            DcsAiConfigServiceImpl.ensureCanAccessAiConfig(null, "000000", false))
            .isInstanceOf(ServiceException.class);
    }
}
