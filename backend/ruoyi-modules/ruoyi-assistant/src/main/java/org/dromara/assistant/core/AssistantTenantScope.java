package org.dromara.assistant.core;

import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;

import java.util.function.Supplier;

/**
 * 切租户读参数的安全包装（2026-11-11 下一步批 STEP02）。
 *
 * <p>为什么不能裸用 {@link TenantHelper#dynamic}：它的 finally 会调 {@code clearDynamic()}，
 * 而 {@code clearDynamic()} 在<b>已登录线程</b>上不只清线程变量，还会删掉该用户持久化的
 * 「切换单位」态（Redis + SaStorage）。超管切到 A 单位之后，只要有人在这条线程上裸调一次，
 * 切换态就没了——下一次请求回落登录单位，页面上的数字「突然就不对了」。</p>
 *
 * <p>与 {@code AssistantStatusController.callInTenant} / {@code AssistantEvictionThreshold.readInTenant}
 * 同一口径（先存后还）。抽出来是为了让还没护栏的调用点不必各自记着这件事。</p>
 */
public final class AssistantTenantScope {

    private AssistantTenantScope() {
    }

    /**
     * 在指定租户内执行并返回结果；租户为空直接执行（不切、也不还）。
     *
     * <p>异常路径同样恢复：body 抛异常时先还原再向上抛，避免留在别人的租户宇宙里。</p>
     */
    public static <T> T call(String tenantId, Supplier<T> body) {
        if (StringUtils.isBlank(tenantId)) {
            return body.get();
        }
        String prev = TenantHelper.getDynamic();
        try {
            return TenantHelper.dynamic(tenantId, body);
        } finally {
            if (StringUtils.isNotBlank(prev)) {
                TenantHelper.setDynamic(prev, true);
            }
        }
    }

    /** 在指定租户内执行（无返回值），语义同 {@link #call}。 */
    public static void run(String tenantId, Runnable body) {
        call(tenantId, () -> {
            body.run();
            return null;
        });
    }
}
