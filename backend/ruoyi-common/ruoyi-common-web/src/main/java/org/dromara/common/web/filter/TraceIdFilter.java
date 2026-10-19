package org.dromara.common.web.filter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求链路追踪 Filter: 为每个 HTTP 请求生成/透传 traceId 写入 MDC,
 * 供 logback 的 %X{traceId} pattern 在日志中输出, 方便按请求关联日志。
 *
 * <p>透传规则: 优先读取上游请求头 {@value #TRACE_ID_HEADER}, 没有则生成新 UUID(无连字符)。
 * 同时将 traceId 写回响应头, 方便前端/调用方关联。</p>
 *
 * <p>参考 middleware_service 的 SOFA-TraceId 方案, 但不引入 SOFA Tracer 重框架,
 * 仅用 SLF4J MDC 实现轻量级单应用链路追踪。</p>
 *
 * @author smartdock
 */
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC key + 请求/响应头名称 */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        // 优先透传上游 traceId, 没有则生成
        String traceId = request.getHeader(TRACE_ID_HEADER);
        if (traceId == null || traceId.isBlank()) {
            // 生成无连字符的 UUID (与 SOFA-TraceId 格式一致, 32 位十六进制)
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        MDC.put(MDC_KEY, traceId);
        // 写回响应头, 方便调用方关联
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            // 必须清理, 避免线程池复用导致 traceId 串
            MDC.remove(MDC_KEY);
        }
    }
}
