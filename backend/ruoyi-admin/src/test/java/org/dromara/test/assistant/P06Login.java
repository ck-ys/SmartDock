package org.dromara.test.assistant;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.satoken.utils.LoginHelper;

import java.util.concurrent.atomic.AtomicReference;

/**
 * P06 进程内登录配方提炼（B3 起 E2E 复用件，2026-10-04）。
 *
 * <p>铸出的 token 写入共享 dev Redis，本地/远程后端均可验——测试 JVM 与运行中
 * 后端跨进程共用会话。登录不即时登出，token 供 HTTP 调用，调用方负责收尾
 * StpUtil.logoutByTokenValue。</p>
 */
final class P06Login {

    static final String CLIENT_ID = "e5cd7e4891bf95d1d19206ce24a7b32e";

    private P06Login() {
    }

    static String mint(String tenantId, Long userId, String username) {
        AtomicReference<String> tokenRef = new AtomicReference<>();
        SaTokenContextMockUtil.setMockContext(() -> {
            // User-Agent 头必须给：UserActionListener.doLogin 解析浏览器信息，缺失=NPE
            org.springframework.mock.web.MockHttpServletRequest servletReq =
                new org.springframework.mock.web.MockHttpServletRequest();
            servletReq.addHeader("User-Agent", "DCS-Assistant/1.0");
            org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(servletReq));
            try {
                LoginUser user = new LoginUser();
                user.setTenantId(tenantId);
                user.setUserId(userId);
                user.setUserType("00");
                user.setDeptId(0L);
                user.setUsername(username);
                cn.dev33.satoken.stp.parameter.SaLoginParameter p =
                    new cn.dev33.satoken.stp.parameter.SaLoginParameter();
                p.setExtra(LoginHelper.CLIENT_KEY, CLIENT_ID);
                LoginHelper.login(user, p);
                tokenRef.set(StpUtil.getTokenValue());
            } finally {
                org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
            }
        });
        return tokenRef.get();
    }
}
