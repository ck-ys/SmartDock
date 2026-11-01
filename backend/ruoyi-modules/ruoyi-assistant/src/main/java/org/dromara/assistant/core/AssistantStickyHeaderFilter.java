package org.dromara.assistant.core;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 助手会话请求粘性头保护（多实例批 0）。
 *
 * <p>{@code X-Thread-Id} 是负载均衡按会话哈希的锚点，不是权限凭证；权限仍由
 * Sa-Token + 会话归属校验负责。多实例模式（require-header=true）下缺头直接 400，
 * 避免请求被当成某个合法会话落到错误实例；单机 local 模式默认不拦，只回显实例 id。</p>
 */
@Slf4j
@Component
public class AssistantStickyHeaderFilter extends OncePerRequestFilter {

    private static final String HEADER = "X-Thread-Id";
    private static final String INSTANCE_HEADER = "X-Assistant-Instance";
    private static final String[] SESSION_PREFIXES = {
        "/agui/",
        // 会话 CRUD（list / messages / archive / rename / restore）不粘实例，任何实例都能答；
        // 只有跟“正在跑的这一轮”绑定的端点才要求 X-Thread-Id
        "/dcs/assistant/conversation/attach",
        "/dcs/assistant/conversation/stop",
        "/dcs/assistant/conversation/todo",
        "/dcs/assistant/conversation/followups",
        "/dcs/assistant/plan/",
        "/dcs/assistant/ask/",
        "/dcs/assistant/elicitation/",
        "/dcs/assistant/hitl/",
        "/dcs/assistant/attachment/"
    };

    private final AssistantInstanceContext instanceContext;
    private final boolean requireHeader;

    public AssistantStickyHeaderFilter(AssistantInstanceContext instanceContext,
                                       @Value("${dcs.assistant.sticky.require-header:false}") boolean requireHeader) {
        this.instanceContext = instanceContext;
        this.requireHeader = requireHeader;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI().substring(request.getContextPath().length());
        }
        for (String prefix : SESSION_PREFIXES) {
            if (path.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        response.setHeader(INSTANCE_HEADER, instanceContext.current());
        String threadId = request.getHeader(HEADER);
        if (threadId == null || threadId.isBlank()) {
            if (requireHeader) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(
                    "{\"code\":400,\"msg\":\"缺少会话标识 X-Thread-Id，请刷新页面后重试\",\"data\":null}");
                return;
            }
            log.debug("[assistant-sticky] 请求未带 {}（local 模式放行）path={}", HEADER, request.getRequestURI());
        }
        chain.doFilter(request, response);
    }
}
