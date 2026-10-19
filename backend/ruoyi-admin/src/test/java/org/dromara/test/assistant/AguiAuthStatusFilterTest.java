package org.dromara.test.assistant;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.satoken.utils.LoginHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 批F Task1：/agui 路径级 401 小过滤器（拍板③技术形态订正——nginx 不能按 body 改状态码，
 * 落地=路径级小过滤器，仅监控与前端简化用，不动全站契约）。
 * <p>三判据：</p>
 * <ul>
 *   <li>①无 token 打 /agui/* → 真实 HTTP 401（当前=200+code401 包裹，监控/前端难判）；</li>
 *   <li>②带 token → 过滤器零行为变化（SSE 正常起步 RUN_STARTED，NeverAgent 零模型成本）；</li>
 *   <li>③非 /agui 路径维持全站契约（200+code401 包裹不迁移——回归护栏）。</li>
 * </ul>
 */
@Tag("p0probe")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"agentscope.agui.server-side-memory=true"})
class AguiAuthStatusFilterTest {

    private static final String CLIENT_ID = "e5cd7e4891bf95d1d19206ce24a7b32e";

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    AguiAgentRegistry registry;

    @BeforeEach
    void socketReadGuard() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(30_000);
        factory.setConnectTimeout(5_000);
        rest.getRestTemplate().setRequestFactory(factory);
    }

    @Test
    void bareAguiGetsRealHttp401() {
        HttpHeaders bare = new HttpHeaders();
        bare.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> unauth = rest.exchange("/agui/run/bf-never", HttpMethod.POST,
            new HttpEntity<>("{}", bare), String.class);
        System.out.println("[批F-T1] 无 token /agui -> HTTP " + unauth.getStatusCode().value()
            + " body=" + (unauth.getBody() == null ? "null" : unauth.getBody().replaceAll("\\s+", "").substring(0,
                Math.min(80, unauth.getBody().length()))));
        assertEquals(401, unauth.getStatusCode().value(),
            "/agui 未登录应为真实 HTTP 401（当前包裹 200 形态即本过滤器要修的靶）");
    }

    @Test
    void authedAguiPassesThroughUntouched() throws Exception {
        String token = loginAsTestUser();
        registry.register("bf-never", new NeverAgent());
        try {
            List<String> frameTypes = new ArrayList<>();
            String body = "{\"threadId\":\"bf-t1-" + System.currentTimeMillis() + "\","
                + "\"runId\":\"r-" + System.nanoTime() + "\","
                + "\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"hi\"}],"
                + "\"state\":{},\"forwardedProps\":{}}";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + token);
            headers.set("clientid", CLIENT_ID);
            long t0 = System.currentTimeMillis();
            rest.execute("/agui/run/bf-never", HttpMethod.POST, req -> {
                    req.getHeaders().putAll(headers);
                    req.getBody().write(body.getBytes(StandardCharsets.UTF_8));
                }, resp -> {
                    assertEquals(200, resp.getStatusCode().value(), "带 token 应正常起步 SSE（过滤器零改动）");
                    StringBuilder rawHead = new StringBuilder();
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(resp.getBody(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null
                                && System.currentTimeMillis() - t0 < 10_000 && frameTypes.isEmpty()) {
                            if (rawHead.length() < 300) {
                                rawHead.append(line, 0, Math.min(line.length(), 120)).append('|');
                            }
                            if (line.startsWith("data:")) {
                                frameTypes.add(new ObjectMapper()
                                    .readTree(line.substring(5).trim()).path("type").asText("UNKNOWN"));
                            }
                        }
                    }
                    if (frameTypes.isEmpty()) {
                        System.out.println("[批F-T1] 0帧诊断 rawHead=" + rawHead);
                    }
                    return frameTypes;
                });
            System.out.println("[批F-T1] 带 token 首帧 = " + frameTypes);
            assertEquals(1, frameTypes.size(), "带 token 应收到 SSE 帧");
            assertEquals("RUN_STARTED", frameTypes.get(0), "首帧应为 RUN_STARTED（放行证明）");
        } finally {
            registry.unregister("bf-never");
            SaTokenContextMockUtil.setMockContext(() -> StpUtil.logoutByTokenValue(token));
        }
    }

    @Test
    void nonAguiPathKeepsLegacyWrapContract() {
        // 回归护栏：非 /agui 路径的未登录响应维持全站 200+code401 包裹契约，过滤器不得外溢
        ResponseEntity<String> unauth = rest.getForEntity("/dcs/assistant/status", String.class);
        System.out.println("[批F-T1] 无 token /dcs/assistant/status -> HTTP "
            + unauth.getStatusCode().value() + " body.code=" + extractCode(unauth.getBody()));
        assertNotEquals(401, unauth.getStatusCode().value(),
            "非 /agui 路径不得改为真实 401（全站包裹契约不动——本过滤器只管 /agui/*）");
    }

    // ==================== 工具（P15a 同款配方） ====================

    private String loginAsTestUser() {
        AtomicReference<String> tokenRef = new AtomicReference<>();
        SaTokenContextMockUtil.setMockContext(() -> {
            MockHttpServletRequest servletReq = new MockHttpServletRequest();
            servletReq.addHeader("User-Agent", "DCS-Assistant/1.0");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletReq));
            try {
                LoginUser user = new LoginUser();
                user.setTenantId("978311");
                user.setUserId(2104824918278434818L);
                user.setUserType("00");
                user.setDeptId(0L);
                user.setUsername("13800001111");
                cn.dev33.satoken.stp.parameter.SaLoginParameter p =
                    new cn.dev33.satoken.stp.parameter.SaLoginParameter();
                p.setExtra(LoginHelper.CLIENT_KEY, CLIENT_ID);
                LoginHelper.login(user, p);
                tokenRef.set(StpUtil.getTokenValue());
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        });
        String token = tokenRef.get();
        assertNotNull(token, "应取得 token");
        return token;
    }

    private static String extractCode(String body) {
        try {
            return body == null ? "null" : String.valueOf(new ObjectMapper().readTree(body).path("code").asInt());
        } catch (Exception e) {
            return "raw";
        }
    }

    /** 永不完成的 Agent（P15a 同款：接口多默认方法，仅补抽象项；零模型成本） */
    static final class NeverAgent implements Agent {
        @Override public String getAgentId() { return "bf-never"; }
        @Override public String getName() { return "bf-never"; }
        @Override public void interrupt() { }
        @Override public void interrupt(Msg msg) { }
        @Override public reactor.core.publisher.Mono<Msg> call(java.util.List<Msg> msgs) {
            return reactor.core.publisher.Mono.empty();
        }
        @Override public reactor.core.publisher.Mono<Msg> call(java.util.List<Msg> msgs, java.lang.Class<?> type) {
            return reactor.core.publisher.Mono.empty();
        }
        @Override public reactor.core.publisher.Mono<Msg> call(java.util.List<Msg> msgs,
                com.fasterxml.jackson.databind.JsonNode extra) {
            return reactor.core.publisher.Mono.empty();
        }
        @Override public reactor.core.publisher.Flux<Event> stream(java.util.List<Msg> msgs,
                StreamOptions opts) { return reactor.core.publisher.Flux.never(); }
        @Override public reactor.core.publisher.Flux<Event> stream(java.util.List<Msg> msgs,
                StreamOptions opts, java.lang.Class<?> type) { return reactor.core.publisher.Flux.never(); }
        @SuppressWarnings("deprecation")
        @Override public reactor.core.publisher.Flux<Event> stream(java.util.List<Msg> msgs,
                StreamOptions opts, com.fasterxml.jackson.databind.JsonNode extra) {
            return reactor.core.publisher.Flux.never();
        }
        @Override public reactor.core.publisher.Mono<Void> observe(Msg msg) {
            return reactor.core.publisher.Mono.empty();
        }
        @Override public reactor.core.publisher.Mono<Void> observe(java.util.List<Msg> msgs) {
            return reactor.core.publisher.Mono.empty();
        }
    }
}
