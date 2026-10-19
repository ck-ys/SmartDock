package org.dromara.test.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.dromara.assistant.core.AssistantResumeUnlockFilter;
import org.dromara.assistant.domain.DcsAssistantConversation;
import org.dromara.assistant.mapper.AssistantConversationMapper;
import org.dromara.assistant.persist.AssistantPendingInterruptCache;
import org.dromara.assistant.persist.AssistantPendingInterruptCache.PendingInterrupt;
import org.dromara.assistant.persist.AssistantRunRecorder;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.satoken.utils.LoginHelper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 二站 T2 入口自动解锁过滤器单测（纯 MockHttpServletRequest，不起 Spring；
 * 审计支路在无 Sa-Token 上下文下自吞，只验注入语义）。
 *
 * <p>判据（计划 §3.2 α 形态）：无 resume+threadId 命中缓存+<b>请求者是 thread 属主</b>
 * （G7 批G 1008）→ 注入全量 resume=cancelled（T0 实测须全覆盖）；body 带 resume /
 * 缓存 miss / 非 JSON / 非 POST / 非属主 → 原样直通；已读 body 场景下游必须拿到回放包装。</p>
 */
@Tag("p0probe")
class H2ResumeUnlockFilterTest {

    private static final String TH = "th-h2";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AssistantPendingInterruptCache cache = new AssistantPendingInterruptCache();
    private final AssistantConversationMapper conversationMapper = mock(AssistantConversationMapper.class);
    /** 三席审查 P1-1：cancelled 注入后须同步回收 recorder 保留的 INTERRUPTED 缓冲（mock 断言调用） */
    private final AssistantRunRecorder runRecorder = mock(AssistantRunRecorder.class);
    private final AssistantResumeUnlockFilter filter =
        new AssistantResumeUnlockFilter(cache, conversationMapper, runRecorder);

    /**
     * RedisUtils 静态初始化块(SpringUtils.getBean(RedissonClient.class))无 Spring 上下文会炸，
     * static mock 前先给 Hutool SpringUtil 注入返回 mock RedissonClient 的假上下文
     * （DcsMissionComboMatrixUnitTest 同款配方）。
     */
    @BeforeAll
    static void primeSpringContext() {
        try {
            java.lang.reflect.Field f = cn.hutool.extra.spring.SpringUtil.class.getDeclaredField("applicationContext");
            f.setAccessible(true);
            if (f.get(null) == null) {
                org.springframework.context.ApplicationContext fake =
                    mock(org.springframework.context.ApplicationContext.class);
                org.redisson.api.RedissonClient mockClient = mock(org.redisson.api.RedissonClient.class);
                when(fake.getBean(org.redisson.api.RedissonClient.class)).thenReturn(mockClient);
                f.set(null, fake);
            }
        } catch (Exception ignore) {
            // 反射失败时后续 mockStatic 会暴露具体原因
        }
        try {
            Class.forName("org.dromara.common.redis.utils.RedisUtils");
        } catch (ClassNotFoundException ignore) {
        }
    }

    /** 会话属主行（thread 首绑=用户 1@000000） */
    private DcsAssistantConversation ownerConv(long userId) {
        DcsAssistantConversation conv = new DcsAssistantConversation();
        conv.setTenantId("000000");
        conv.setUserId(userId);
        conv.setThreadId(TH);
        return conv;
    }

    private LoginUser user(long userId) {
        LoginUser u = mock(LoginUser.class);
        when(u.getUserId()).thenReturn(userId);
        when(u.getTenantId()).thenReturn("000000");
        return u;
    }

    private MockHttpServletRequest post(String body) {
        MockHttpServletRequest req = postNoAuth(body);
        req.addHeader("Authorization", "Bearer tok");
        return req;
    }

    private MockHttpServletRequest postNoAuth(String body) {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setMethod("POST");
        req.setRequestURI("/agui/run/dcs-assistant");
        req.setContentType("application/json");
        req.setContent(body.getBytes(StandardCharsets.UTF_8));
        return req;
    }

    /** 最近一次进入下游过滤链的请求（Content-Length 口径断言用） */
    private jakarta.servlet.http.HttpServletRequest lastDownstream;

    /** 属主（用户 1）视角跑过滤链：静态 mock LoginHelper 解析 token */
    private JsonNode run(MockHttpServletRequest req) throws Exception {
        return run(req, user(1L));
    }

    private JsonNode run(MockHttpServletRequest req, LoginUser requester) throws Exception {
        // 审计支路会触碰 RedisUtils（动态租户快照键）——纯单测无 Redis，静态打桩
        // （项目既有配方：mockStatic(RedisUtils)+getCacheObject→null）
        try (var mocked = mockStatic(LoginHelper.class);
             var redis = mockStatic(org.dromara.common.redis.utils.RedisUtils.class)) {
            mocked.when(() -> LoginHelper.getLoginUser("tok")).thenReturn(requester);
            redis.when(() -> org.dromara.common.redis.utils.RedisUtils.getCacheObject(org.mockito.ArgumentMatchers.any()))
                .thenReturn(null);
            return doRun(req);
        }
    }

    private JsonNode doRun(MockHttpServletRequest req) throws Exception {
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        // 下游拿到的请求体（过滤链没继续走=shouldNotFilter 直通原对象）
        if (chain.getRequest() == null) {
            return null;
        }
        lastDownstream = (jakarta.servlet.http.HttpServletRequest) chain.getRequest();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        lastDownstream.getInputStream().transferTo(out);
        return MAPPER.readTree(out.toString(StandardCharsets.UTF_8));
    }

    private void hang(String threadId, String... toolCallIds) {
        long at = System.currentTimeMillis();
        cache.store(threadId, java.util.Arrays.stream(toolCallIds)
            .map(tc -> new PendingInterrupt(threadId + ":" + tc, tc, "reason", null, at))
            .toList());
    }

    @Test
    void injectsCancelledResumeForAllPendingInterrupts() throws Exception {
        hang(TH, "call_00_aaa", "call_01_bbb");
        when(conversationMapper.selectByThread("000000", TH)).thenReturn(ownerConv(1L));
        String orig = "{\"threadId\":\"" + TH + "\",\"messages\":[],\"state\":{}}";
        JsonNode out = run(post(orig));

        assertNotNull(out, "属主命中悬挂必须注入并继续过滤链");
        JsonNode resume = out.get("resume");
        assertTrue(resume != null && resume.isArray() && resume.size() == 2,
            "一 run 挂 2 个必须全量注入（T0：部分覆盖被拒）");
        assertEquals(TH + ":call_00_aaa", resume.get(0).get("interruptId").asText());
        assertEquals("cancelled", resume.get(0).get("status").asText());
        assertEquals("cancelled", resume.get(1).get("status").asText());
        // 原有字段原样保留
        assertEquals(TH, out.get("threadId").asText());
        assertTrue(out.has("messages"));
        // E2E 破案回归锁：注入变长后 Content-Length 三口径（int/long/头）必须=回放体长度，
        // 按旧头截断读流=JSON 解析失败 400
        jakarta.servlet.http.HttpServletRequest down = lastDownstream;
        assertNotNull(down, "下游请求应是被包装对象");
        String injected = MAPPER.writeValueAsString(out);
        assertEquals(injected.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
            down.getContentLength(), "getContentLength=注入后长度");
        assertEquals(injected.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
            down.getContentLengthLong(), "getContentLengthLong=注入后长度");
        assertEquals(String.valueOf(injected.getBytes(java.nio.charset.StandardCharsets.UTF_8).length),
            down.getHeader("Content-Length"), "Content-Length 头=注入后长度");
        assertTrue(down.getContentLength() > orig.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
            "注入后必变长（校验确实改写了）");
        // 三席审查 P1-1：注入成功=弃卡改问，旧回合步骤/思考缓冲必须同步回收
        // （不回收则 run B 同 thread 快照把 A 的幽灵步骤并入新消息 tool_steps）
        verify(runRecorder).evictStepBuffers(java.util.Collections.singletonList(TH));
    }

    /** G7(批G 1008)：非属主（会话属主是用户 2，请求者是用户 1）→ 不注入原样直通 */
    @Test
    void rejectsForeignOwner() throws Exception {
        hang(TH, "call_00_aaa");
        when(conversationMapper.selectByThread("000000", TH)).thenReturn(ownerConv(2L));
        JsonNode out = run(post("{\"threadId\":\"" + TH + "\",\"messages\":[]}"));
        assertNull(out.get("resume"), "非属主不得触发自动解锁注入（DoS 堵口）");
        assertTrue(out.has("messages"), "body 原样保留");
        // 不注入=卡仍在，旧回合缓冲必须保留（resume 续接还依赖它）
        verify(runRecorder, never()).evictStepBuffers(anyCollection());
    }

    /** G7(批G 1008)：匿名/身份解析失败（无 Authorization）→ 不注入 */
    @Test
    void rejectsAnonymous() throws Exception {
        hang(TH, "call_00_aaa");
        JsonNode out = doRun(postNoAuth("{\"threadId\":\"" + TH + "\",\"messages\":[]}"));
        assertNull(out.get("resume"), "解析不出身份=跳过注入（下游 401 兜底）");
        assertTrue(out.has("messages"), "body 原样保留");
    }

    @Test
    void passesThroughWhenResumePresent() throws Exception {
        hang(TH, "call_00_aaa");
        JsonNode out = run(post("{\"threadId\":\"" + TH
            + "\",\"resume\":[{\"interruptId\":\"x\",\"status\":\"resolved\"}]}"));
        assertEquals("resolved", out.get("resume").get(0).get("status").asText(),
            "前端确认卡正常流绝不打扰");
    }

    @Test
    void treatsEmptyResumeArrayAsAbsent() throws Exception {
        hang(TH, "call_00_aaa");
        when(conversationMapper.selectByThread("000000", TH)).thenReturn(ownerConv(1L));
        JsonNode out = run(post("{\"threadId\":\"" + TH + "\",\"resume\":[]}"));
        assertEquals(1, out.get("resume").size(), "空数组视为缺席——注入补全才是出路");
        assertEquals("cancelled", out.get("resume").get(0).get("status").asText());
    }

    @Test
    void passesThroughOnCacheMissOrMalformed() throws Exception {
        // 缓存空快路径：shouldNotFilter=true 时 OncePerRequestFilter 直接放行原对象（不包装）
        cache.clear(TH);
        MockHttpServletRequest req = post("{\"threadId\":\"other-thread\"}");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, new MockHttpServletResponse(), chain);
        assertSame(req, chain.getRequest(), "缓存空应零成本直通原对象");

        // 有悬挂但 threadId 不匹配（属主检查之前就 miss）
        hang(TH, "call_00_aaa");
        JsonNode out = run(post("{\"threadId\":\"another\",\"messages\":[]}"));
        assertNull(out.get("resume"), "缓存 miss 不注入");

        // 非 JSON body：原样回放直通（不能丢 body）
        MockHttpServletRequest raw = post("not-json-at-all");
        MockFilterChain rawChain = new MockFilterChain();
        filter.doFilter(raw, new MockHttpServletResponse(), rawChain);
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        rawChain.getRequest().getInputStream().transferTo(body);
        assertEquals("not-json-at-all", body.toString(StandardCharsets.UTF_8),
            "解析失败的 body 必须原样回放给下游");
    }

    @Test
    void skipsNonAguiOrNonPost() throws Exception {
        hang(TH, "call_00_aaa");
        // GET → 直通原对象
        MockHttpServletRequest get = new MockHttpServletRequest();
        get.setMethod("GET");
        get.setRequestURI("/agui/run/dcs-assistant");
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(get, new MockHttpServletResponse(), chain);
        assertSame(get, chain.getRequest(), "非 POST 不进过滤逻辑");

        // POST 但路径不在 /agui/run/* → 直通原对象
        MockHttpServletRequest other = post("{}");
        other.setRequestURI("/system/user/list");
        MockFilterChain chain2 = new MockFilterChain();
        filter.doFilter(other, new MockHttpServletResponse(), chain2);
        assertSame(other, chain2.getRequest(), "非 agui 路径不进过滤逻辑");
    }
}
