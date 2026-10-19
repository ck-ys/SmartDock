package org.dromara.assistant.core;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * /agui 路径级未登录真 401 小过滤器（批F Task1，拍板③技术形态订正落地：
 * nginx 无法按 body 改状态码，落点=路径级过滤器——仅监控与前端简化用，
 * 不动全站契约）。现状=/agui 未登录走全局异常包装成 HTTP 200 + code 401
 * （RuoYi 前端拦截器约定），监控无法按状态码统计、前端 SSE 客户端也难判。
 * 本过滤器把 /agui/* 的未登录改成真实 HTTP 401；其余路径一律直通。
 *
 * <p><b>放行语义</b>：已登录请求零改动（SaInterceptor 照常做 clientid 比对等
 * 后续校验）；非 NotLoginException 异常（如上下文异常）也直通交给拦截器按
 * 全站包裹口径处理——宁可退回旧行为，不给正常流引入新 500。与
 * {@link AssistantResumeUnlockFilter} 同为 /agui 链上的路径级小件，互不感知。</p>
 */
@Slf4j
@Component
public class AguiAuthStatusFilter extends OncePerRequestFilter {

    /** 仅 /agui/* 生效（shouldNotFilter 快路径，其余请求零开销） */
    private static final String AGUI_PREFIX = "/agui/";

    /** 与全站包裹形态同构（code/msg/data），仅状态码不同——前端两种形态都好接 */
    private static final String UNAUTHORIZED_BODY = "{\"code\":401,\"msg\":\"未登录或登录已过期\",\"data\":null}";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri == null || !uri.substring(request.getContextPath().length()).startsWith(AGUI_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            StpUtil.checkLogin();
        } catch (NotLoginException e) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(UNAUTHORIZED_BODY);
            return;
        } catch (Exception e) {
            // 上下文异常等罕见形态：退回旧行为（交给 SaInterceptor 全站包裹），不引入新失败面
            log.warn("[dcs-assistant] AguiAuthStatusFilter 非常规异常直通: {}", e.getMessage());
        }
        chain.doFilter(request, response);
    }
}
