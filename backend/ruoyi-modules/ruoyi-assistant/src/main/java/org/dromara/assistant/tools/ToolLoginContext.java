package org.dromara.assistant.tools;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import cn.dev33.satoken.stp.parameter.SaLoginParameter;
import org.dromara.common.core.constant.GlobalConstants;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.redis.utils.RedisUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.function.Supplier;

/**
 * 工具内登录态重建器（批B B1，2026-10-04；配方=P04ContextRebuildTest.runAs 完整形态）。
 *
 * <p>agent 工具在响应式线程池执行，无 Sa-Token 上下文与请求属性；而 DCS 服务层
 * 自取 LoginHelper.getUserId()/getTenantId()，登录链还会读请求头（无请求属性
 * =ServletUtils.getRequest() NPE，B1 stub 测实证）。本件把身份快照重建为同线程
 * 完整登录态再调服务：mock 上下文+mock 请求 → 动态租户 → LoginHelper.login →
 * 业务调用 → finally 全量清理（登出/清租户/清请求属性）。</p>
 *
 * <p>安全口径：identity 来自 resolver 身份链（同请求传递），不是工具参数——
 * 模型给什么参数都改不了"以谁的身份查"。</p>
 *
 * <p>三审回修（dspro P1-1 / glm53 P1-2、P2-5）：①clearDynamic 会删掉**调用者**
 * 的全局动态租户 Redis 键（超管 UI 切租户后问一句 AI 即被静默切回），进前快照、
 * finally 还原；②登录带独立 deviceType，UserActionListener 对 AI-TOOL 分支跳过
 * 刷 login_ip/登录日志/在线表（否则真实登录痕迹被 127.0.0.1 冲掉）；③finally
 * 三步各自 try-catch，防 Redis 抖动短路后续清理。</p>
 */
public final class ToolLoginContext {

    /** AI 工具线程登录的伪设备标识（UserActionListener 据此跳过登录审计污染） */
    public static final String DEVICE_TYPE = "AI-TOOL";

    private ToolLoginContext() {
    }

    @SuppressWarnings("unchecked")
    public static <T> T runAs(LoginUser user, Supplier<T> body) {
        Object[] out = new Object[1];
        SaTokenContextMockUtil.setMockContext(() -> {
            MockHttpServletRequest servletReq = new MockHttpServletRequest();
            servletReq.addHeader("User-Agent", "DCS-Assistant/1.0");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletReq));
            // 与 TenantHelper.DYNAMIC_TENANT_KEY 同式（私有常量，只能字面复刻）：
            // 记下调用者是否开着全局动态租户（超管租户切换），finally 里还原
            String dynKey = GlobalConstants.GLOBAL_REDIS_KEY + "dynamicTenant:" + user.getUserId();
            String prevGlobalDynamic = RedisUtils.getCacheObject(dynKey);
            try {
                TenantHelper.setDynamic(user.getTenantId());
                LoginHelper.login(user, new SaLoginParameter().setDeviceType(DEVICE_TYPE));
                out[0] = body.get();
            } finally {
                try {
                    TenantHelper.clearDynamic();
                    if (prevGlobalDynamic != null) {
                        RedisUtils.setCacheObject(dynKey, prevGlobalDynamic);
                    }
                } catch (Exception e) {
                    // 清理失败不阻断后续登出/请求属性清理
                }
                try {
                    StpUtil.logout();
                } catch (Exception ignored) {
                    // Redis 抖动等：登录链已入 mock 上下文，随上下文销毁失效
                }
                try {
                    RequestContextHolder.resetRequestAttributes();
                } catch (Exception ignored) {
                    // 最后一步，失败也无可恢复动作
                }
            }
        });
        return (T) out[0];
    }
}
