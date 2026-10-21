package com.smartdock.profile.engine;

import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;

/**
 * 租户上下文 fail-fast(批11 Q4-A): 拿不到租户一律报错, 禁止回落默认租户——
 * 调度/异步线程丢上下文时静默挂到 000000 是跨租户数据污染源。
 * 合法无登录路径(采集口 siteKey 解析、定时任务、回放执行器)都必须
 * TenantHelper.dynamic(租户, ...) 显式包裹后再进引擎。
 *
 * @author smartdock
 */
public final class ProfileTenantContext {

    private ProfileTenantContext() {
    }

    /** 取当前租户; 动态租户 > 登录租户, 都没有则抛错。 */
    public static String require() {
        try {
            String t = TenantHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 无租户上下文
        }
        try {
            String t = LoginHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 非登录上下文
        }
        throw new ServiceException("缺少租户上下文, 拒绝按默认租户兜底(调度线程请用 TenantHelper.dynamic 显式传租户)");
    }
}
