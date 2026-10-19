package org.dromara.system.service;

import org.dromara.common.core.constant.SystemConstants;
import org.dromara.common.core.constant.TenantConstants;
import org.dromara.system.domain.vo.SysTenantVo;

import java.util.Date;

/**
 * 单位「可登录」统一口径（2026-09-26）
 *
 * <p>登录侧 {@code SysLoginService.loginable}、邀请注册 {@code DcsInviteLinkServiceImpl}、
 * 忘记密码 {@code SysPwdResetService} 原本各抄一份且已现边界漂移（一处 !before 两处 !after），
 * 收敛到此公共谓词，任何新增匿名/登录通道须复用。</p>
 *
 * <p>口径：000000 管理租户豁免；未停用 且（永不过期 或 尚未到期，expireTime 相等瞬间放行统一 !after）。</p>
 *
 * @author smartdock
 */
public final class SysTenantLoginChecker {

    private SysTenantLoginChecker() {
    }

    /**
     * 单位当前是否可登录
     *
     * @param tenant 单位（null 视为不可登录）
     * @param now    判定时点
     */
    public static boolean isLoginable(SysTenantVo tenant, Date now) {
        if (tenant == null) {
            return false;
        }
        if (TenantConstants.DEFAULT_TENANT_ID.equals(tenant.getTenantId())) {
            return true;
        }
        return !SystemConstants.DISABLE.equals(tenant.getStatus())
            && (tenant.getExpireTime() == null || !now.after(tenant.getExpireTime()));
    }
}
