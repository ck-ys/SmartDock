package org.dromara.test.assistant;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.agui.runtime.AguiRuntimeContextRequest;
import org.dromara.assistant.core.AssistantAgentFactory;
import org.dromara.assistant.core.AssistantRuntimeContextResolver;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.satoken.utils.LoginHelper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A2 官方身份钩子单测（批A Task A2，P1 计划 2026-10-03）。
 *
 * <p>判据（计划三守卫）：①resolver 把合法 token 映射为租户作用域 userId（t:tenant:uid，spec §5.2）；
 * ②无/坏 token 时 resolver 抛异常（禁返回 null——null 会落框架 ANON_USER 共享会话=越权面）；
 * ③工厂按登录用户建命名 agent；④同 threadId 双用户会话隔离。</p>
 */
@Tag("p0probe")
@SpringBootTest
class A2IdentityTest {

    private static final String CLIENT_ID = "e5cd7e4891bf95d1d19206ce24a7b32e";
    /** P06 同款测试用户（978311 租户真实成员） */
    private static final Long USER_ID = 2104824918278434818L;
    private static final AtomicReference<String> TOKEN_HOLDER = new AtomicReference<>();

    @Autowired
    AssistantRuntimeContextResolver resolver;
    @Autowired
    AssistantAgentFactory agentFactory;

    @AfterAll
    static void cleanup() {
        String token = TOKEN_HOLDER.get();
        if (token != null) {
            cn.dev33.satoken.context.mock.SaTokenContextMockUtil.setMockContext(() ->
                cn.dev33.satoken.stp.StpUtil.logoutByTokenValue(token));
        }
    }

    /** P06 登录配方（进程内登录拿 token，共享 Redis 真登录态） */
    private String loginAsTestUser() {
        cn.dev33.satoken.context.mock.SaTokenContextMockUtil.setMockContext(() -> {
            MockHttpServletRequest servletReq = new MockHttpServletRequest();
            servletReq.addHeader("User-Agent", "DCS-Assistant/1.0");
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletReq));
            try {
                LoginUser user = new LoginUser();
                user.setTenantId("978311");
                user.setUserId(USER_ID);
                user.setUserType("00");
                user.setDeptId(0L);
                user.setUsername("13800001111");
                cn.dev33.satoken.stp.parameter.SaLoginParameter p =
                    new cn.dev33.satoken.stp.parameter.SaLoginParameter();
                p.setExtra(LoginHelper.CLIENT_KEY, CLIENT_ID);
                LoginHelper.login(user, p);
                TOKEN_HOLDER.set(cn.dev33.satoken.stp.StpUtil.getTokenValue());
            } finally {
                RequestContextHolder.resetRequestAttributes();
            }
        });
        return TOKEN_HOLDER.get();
    }

    private AguiRuntimeContextRequest<Void> requestWithAuth(String token) {
        return AguiRuntimeContextRequest.<Void>builder()
            .input(io.agentscope.core.agui.model.RunAgentInput.builder()
                .runId("test-run-a2").threadId("thread-a2").build())
            .method("POST").path("/agui/run/dcs-assistant")
            .headers(token == null ? Map.of() : Map.of("Authorization", List.of("Bearer " + token)))
            .build();
    }

    @Test
    void resolverMapsTokenToTenantScopedUserId() {
        String token = loginAsTestUser();
        assertNotNull(token, "进程内登录应取得 token");
        RuntimeContext rc = resolver.resolve(requestWithAuth(token));
        assertEquals("t:978311:" + USER_ID, rc.getUserId(),
            "userId 必须是 t:{tenantId}:{uid} 租户作用域格式（spec §5.2）");
    }

    @Test
    void resolverThrowsWithoutValidToken() {
        assertThrows(Exception.class, () -> resolver.resolve(requestWithAuth(null)),
            "无 Authorization 头必须抛异常——禁返回 null（null→框架 ANON_USER 共享会话=越权面）");
        assertThrows(Exception.class, () -> resolver.resolve(requestWithAuth("bad-token-value")),
            "坏 token 必须抛异常");
    }

    @Test
    void factoryCreatesNamedAgentForUser() {
        String token = loginAsTestUser();
        LoginUser user = LoginHelper.getLoginUser(token);
        assertNotNull(user, "token 应能取回登录用户");
        ReActAgent agent = agentFactory.create(user);
        assertNotNull(agent);
        assertEquals(AssistantAgentFactory.AGENT_ID, agent.getName(),
            "agent 必须命名（坑66：不命名=eviction/清洗路径 NPE 静默失败）");
    }

    @Test
    void createFromLoginViaIdentityBridge() {
        // 12机 冒烟真实路径：resolver 先行（设桥）→ 同线程 supplier 消费——全程无 Sa-Token 上下文
        String token = loginAsTestUser();
        resolver.resolve(requestWithAuth(token));
        ReActAgent agent = agentFactory.createFromLogin();
        assertEquals(AssistantAgentFactory.AGENT_ID, agent.getName(),
            "身份桥应把 resolver 解析的用户传给工厂 supplier");
    }

    @Test
    void createFromLoginWithoutBridgeThrows401Shape() {
        // 桥空（未先过 resolver / 线程换乘）必须 fail-closed，不得回落匿名或他人；
        // 先消耗一次清除可能残留的前测身份，保证断言确定性
        try {
            agentFactory.createFromLogin();
        } catch (Exception ignored) {
            // 残留身份被本调用消耗，或本就为空——都进入下面的干净断言
        }
        assertThrows(Exception.class, () -> agentFactory.createFromLogin(),
            "无身份桥=拒绝创建（fail-closed，绝不禁默回落）");
    }

    @Test
    void factoryReturnsFreshInstancePerCall() {
        Object a1 = new Object();
        Object a2 = new Object();
        // 工厂非单例（会话隔离前提）；跨用户隔离语义由 B3 跨租户 E2E 钉死
        String token = loginAsTestUser();
        LoginUser user = LoginHelper.getLoginUser(token);
        io.agentscope.core.agent.Agent agent1 = agentFactory.create(user);
        io.agentscope.core.agent.Agent agent2 = agentFactory.create(user);
        assertNotSame(agent1, agent2, "工厂每调用建新实例（每请求/每会话独立，禁共享单例）");
    }
}
