package org.dromara.assistant.core;

import jakarta.annotation.PostConstruct;
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

    /**
     * 四审回修（dspro-P1-4，护栏）：多实例共享态已开、粘性头却关着，是“HITL/续看/停止
     * 落到错误实例”的温床——本机悬挂缓存和框架协调器都在进程内，落错实例会出现
     * “确认必然失败/该 thread 在错实例上反复报错”。配置组合不强制改（本地/灰度仍可关），
     * 但必须在启动日志里大声说出来，不让运维靠猜。
     */
    @PostConstruct
    void warnOnUnsafeCombination() {
        if (instanceContext.redisEnabled() && !requireHeader) {
            log.error("[assistant-sticky] 危险配置：shared-state 已开（多实例）但 sticky.require-header=false。"
                + "必须保证负载均衡按 X-Thread-Id 做一致性哈希；否则 HITL/续看/停止会落到错误实例，"
                + "旧悬挂缓存还可能让该会话在错误实例上持续报错。建议 DCS_ASSISTANT_STICKY_REQUIRE_HEADER=true。");
        }
        if (!instanceContext.redisEnabled() && requireHeader) {
            log.error("[assistant-sticky] 危险配置：sticky.require-header=true 但 shared-state 仍是 local。"
                + "多实例必须全集群切 DCS_ASSISTANT_SHARED_STATE_MODE=redis；local 节点会把别台的"
                + "活 run 判死并收尾，混跑时用户会看到回答被中断。");
        }
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
