package org.dromara.common.web.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.common.web.filter.RepeatedlyRequestWrapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-18 上游队列验收第 10 项：坏 JSON 不能在拦截器里抛 500。
 *
 * <p>拦截器只负责调用耗时统计和请求日志，请求体解析失败时应放行给 Controller；
 * /agui/queue 的 {@code @RequestBody String} 会拿到原文并由 AguiQueueController 返回 400。
 * 修前这里会抛 JsonProcessingException，测试失败；修后放行。</p>
 */
@Tag("local")
class PlusWebInvokeTimeInterceptorJsonTest {

    @Test
    void malformedJsonDoesNotThrowBeforeController() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/agui/queue/th-json");
        request.setContentType("application/json");
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContent("{not-json".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        RepeatedlyRequestWrapper wrapper = new RepeatedlyRequestWrapper(request, response);

        boolean proceed = new PlusWebInvokeTimeInterceptor(new ObjectMapper()).preHandle(wrapper, response, new Object());

        assertTrue(proceed, "坏 JSON 不应在拦截器里抛出/中断，应交给 Controller 判定 400");
    }
}
