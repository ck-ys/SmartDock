package org.dromara.web.adapter;

import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;

/**
 * 适配器专用的「线程内临时租户」作用域（批4 回修，2026-10-18）。
 *
 * <p>不能直接用 {@link TenantHelper#dynamic}：其 finally 的 clearDynamic 在登录线程上
 * 会连删 Redis/SaHolder 里的超管「租户切换」持久态（三审 dspro P1-2 / glm53 P2-2）——
 * 审一次文件就把超管静默弹回默认租户视角，且上游后续取租户会错账。这里按仓内既有
 * 范式（SysLogininforController 先存后还）保存旧动态租户，退出时恢复。</p>
 */
final class TenantScopes {

    private TenantScopes() {
    }

    /**
     * 在指定租户上下文内执行（线程内生效），退出时恢复进入前的动态租户态。
     *
     * @param tenantId 目标租户（空则原样执行，不切换）
     * @param body     业务体
     */
    static void runInTenant(String tenantId, Runnable body) {
        if (StringUtils.isBlank(tenantId)) {
            body.run();
            return;
        }
        String prevDynamic = TenantHelper.getDynamic();
        try {
            TenantHelper.dynamic(tenantId, body);
        } finally {
            if (StringUtils.isNotBlank(prevDynamic)) {
                TenantHelper.setDynamic(prevDynamic, true);
            }
        }
    }

    /**
     * {@link #runInTenant} 的带返回值版本。
     *
     * @param tenantId 目标租户（空则原样执行，不切换）
     * @param body     业务体
     * @return 业务体返回值
     */
    static <T> T callInTenant(String tenantId, java.util.function.Supplier<T> body) {
        if (StringUtils.isBlank(tenantId)) {
            return body.get();
        }
        String prevDynamic = TenantHelper.getDynamic();
        try {
            return TenantHelper.dynamic(tenantId, body);
        } finally {
            if (StringUtils.isNotBlank(prevDynamic)) {
                TenantHelper.setDynamic(prevDynamic, true);
            }
        }
    }
}
