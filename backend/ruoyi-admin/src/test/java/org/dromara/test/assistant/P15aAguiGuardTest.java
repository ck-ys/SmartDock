package org.dromara.test.assistant;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.Event;
import io.agentscope.core.agent.StreamOptions;
import io.agentscope.core.message.Msg;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.mapper.DcsAiConfigMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-T15a 探针：/agui 兜底守卫四件（实测⑮之一，安全五连测卡1）。
 * <p>判据（计划 Task 15 Step1/Step4 顺带项）：</p>
 * <ul>
 *   <li>Step1 兜底 401（与 T3 联动收口）：不带 token 打 /agui/run/{agentId} → 业务码 401；
 *       带 token → SSE RUN_STARTED（DummyAgent 零模型成本）；</li>
 *   <li>Step4-顺带① SSE 并发上限：serverSideMemory=true + maxThread-sessions=2 下第 3 个并发
 *       thread 会话 → 断言【明确结局不挂起】（拒绝 RUN_ERROR / HTTP 拒绝 / 或准入+淘汰——
 *       实测语义如实记录，若为准入非拒绝=P1 须自建准入限流的实证依据）；</li>
 *   <li>Step4-顺带② 输入尺寸边界（X-13/S-03，8K~16K 校准）：框架无输入尺寸护栏
 *       （starter 配置 metadata 全表无 size/limit 属性——取证事实），P0 验证应用层
 *       16K 上限守卫 + 拒绝文案可行；</li>
 *   <li>Step5-顺带 emit-token-usage：显式开启后 SSE 帧含 token_usage 事件
 *       （默认 false 的阴性对照=P06 已落盘的 477 帧序列，见 06-agui-sse.md）。</li>
 * </ul>
 * <p>预算纪律：本卡真实付费调用 ×1（token_usage 帧）；其余 DummyAgent 零成本。</p>
 */
@Tag("p0probe")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
        "agentscope.agui.server-side-memory=true",
        "agentscope.agui.max-thread-sessions=2",
        "agentscope.agui.emit-token-usage=true"
    })
class P15aAguiGuardTest {

    private static final long CONFIG_ID = 9100000000003L;
    private static final String CLIENT_ID = "e5cd7e4891bf95d1d19206ce24a7b32e";
    private static final Path OUT = Path.of("target", "p15a");

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DcsAiConfigMapper aiConfigMapper;

    @Autowired
    AguiAgentRegistry registry;

    @BeforeAll
    static void ensureOutDir() throws Exception {
        Files.createDirectories(OUT);
    }

    @BeforeEach
    void socketReadGuard() {
        // SSE 读超时护栏：思考期实测静默最长 ~20s，30s SO_TIMEOUT 足够放行正常流，
        // 又能把"服务器不回帧"的挂死在 30s 内变成显式失败（否则默认可挂到 sseTimeout=600s）
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setReadTimeout(30_000);
        factory.setConnectTimeout(5_000);
        rest.getRestTemplate().setRequestFactory(factory);
    }

    /** 事件帧：类型 + 相对首帧毫秒 + data 原文 */
    record Frame(String type, long atMs, String data) {}

    /** 永不完成的 Agent（占住 serverSideMemory 会话；接口多默认方法，仅补抽象项——P07 同款） */
    static final class NeverAgent implements Agent {
        @Override public String getAgentId() { return "p15-never"; }
        @Override public String getName() { return "p15-never"; }
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

    /** Step1：无 token → 业务码 401；带 token → SSE RUN_STARTED（DummyAgent，零模型成本） */
    @Test
    void bare401AndAuthedSsePass() throws Exception {
        String token = loginAsTestUser();
        registry.register("p15-never", new NeverAgent());
        try {
            // 无 token：业务码 401（P06 精确断言口径复用）
            HttpHeaders bare = new HttpHeaders();
            bare.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> unauth = rest.exchange("/agui/run/p15-never", HttpMethod.POST,
                new HttpEntity<>("{}", bare), String.class);
            System.out.println("[P15a] 无 token -> HTTP " + unauth.getStatusCode().value()
                + " body.code=" + extractCode(unauth.getBody()));
            assertEquals("401", extractCode(unauth.getBody()),
                "未登录应被拦为业务码 401（HTTP " + unauth.getStatusCode().value() + "）");

            // 带 token：SSE 首帧 RUN_STARTED（serverSideMemory=true 生效形态；NeverAgent 永不完成——
            // 读到首帧即断开，不等待 RUN_FINISHED）
            List<Frame> frames = postSse(token, "p15-never", "p15a-s1-" + System.currentTimeMillis(),
                "你好", 10_000, 1);
            assertFalse(frames.isEmpty(), "带 token 应收到 SSE 帧");
            System.out.println("[P15a] 带 token 首帧 = " + frames.get(0).type() + " @ " + frames.get(0).atMs() + "ms");
            assertEquals("RUN_STARTED", frames.get(0).type(),
                "带 token 首帧应为 RUN_STARTED（serverSideMemory=true 形态）");
        } finally {
            registry.unregister("p15-never");
            SaTokenContextMockUtil.setMockContext(() -> StpUtil.logoutByTokenValue(token));
        }
    }

    /**
     * Step4-顺带①：max-thread-sessions=2 下第 3 个并发 thread 会话的结局。
     * 断言=不挂死（15s 内有明确结局：拒绝 or 准入）；准入/拒绝语义如实记录（证据文档回填）。
     */
    @Test
    void threadSessionCapThirdRunExplicitOutcome() throws Exception {
        String token = loginAsTestUser();
        registry.register("p15-never", new NeverAgent());
        try {
            long t1 = occupySession(token, "p15a-cap-1");
            long t2 = occupySession(token, "p15a-cap-2");
            System.out.println("[P15a] 会话1 首帧@" + t1 + "ms, 会话2 首帧@" + t2 + "ms（2 个 thread 会话已占满上限）");

            // 第 3 个会话：15s 内必须有明确结局（RUN_ERROR=拒绝 / RUN_STARTED=准入+淘汰）
            long t0 = System.currentTimeMillis();
            List<Frame> third = postSse(token, "p15-never", "p15a-cap-3", "第三问", 15_000, 1);
            long took = System.currentTimeMillis() - t0;
            assertFalse(third.isEmpty(),
                "第 3 个会话 15s 内应有明确结局（实测 " + took + "ms 无帧=挂死，违反'超限不挂起'）");
            String outcome = third.get(0).type();
            System.out.println("[P15a] 第 3 会话结局 = " + outcome + " @ " + took + "ms"
                + (outcome.equals("RUN_ERROR") ? "（超限=明确拒绝✓）" : "（准入非拒绝——框架上限=缓存淘汰语义，P1 须自建准入限流）"));
            // 明确结局三选一（RUN_ERROR=拒绝；RUN_STARTED=准入+LRU 淘汰；其余帧型如实记录供证据回填）
            assertTrue(outcome.equals("RUN_ERROR") || outcome.equals("RUN_STARTED") || outcome.equals("RUN_FINISHED"),
                "结局应为可解释帧型（实测=" + outcome + "）");
        } finally {
            registry.unregister("p15-never");
            SaTokenContextMockUtil.setMockContext(() -> StpUtil.logoutByTokenValue(token));
        }
    }

    /**
     * Step4-顺带②：输入尺寸边界校准（X-13/S-03：上限 8K~16K 字符，超限=提示拆分或拒绝）。
     * 框架侧无护栏（starter 配置 metadata 全表无输入尺寸属性——javap+json 取证），应用层守卫 P0 原型。
     */
    @Test
    void inputSizeGuardCalibration() {
        assertEquals(true, InputSizeGuard.check("正常长度的单条消息".repeat(100)).allowed(),
            "8K 以下应放行（实测 " + "正常长度的单条消息".repeat(100).length() + " 字符）");
        assertEquals(true, InputSizeGuard.check("x".repeat(16_384)).allowed(),
            "16K 上边界（16384 字符）应放行（S-03 区间上限）");
        InputSizeGuard over = InputSizeGuard.check("x".repeat(16_385));
        assertFalse(over.allowed(), "16K+1 应拒绝");
        assertEquals("单条消息过长（16385 字符，上限 16384），请拆分后分条发送", over.rejection(),
            "拒绝文案须指明实际长度/上限/拆分建议（S-03 口径）");
        // 边界形态：多字节中文字符按字符数计（非字节数）——16384 个中文字符=49152 字节也放行
        assertEquals(true, InputSizeGuard.check("中".repeat(16_384)).allowed(),
            "按字符数计（中文 16384 字符放行，非按字节）");
        System.out.println("[P15a] 输入尺寸守卫：8K✓放行 / 16K✓上界放行 / 16K+1✓拒绝+文案（框架无护栏，P1 应用层装配）");
    }

    /** Step5-顺带：emit-token-usage=true → SSE 帧流含 token_usage 事件（真实模型 ×1） */
    @Test
    void emitTokenUsageFrameAppearsWhenEnabled() throws Exception {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg, "978311 DeepSeek 配置应存在");
        ReActAgent agent = ReActAgent.builder()
            .sysPrompt("你是 DCS 数据采集系统的工作助手。用一句话中文回答。不要使用任何工具。")
            .model(OpenAIChatModel.builder()
                .baseUrl(cfg.getBaseUrl()).apiKey(cfg.getApiKey()).modelName(cfg.getModelName())
                .stream(true).formatter(new DeepSeekFormatter()).build())
            .maxIters(2)
            .build();
        String token = loginAsTestUser();
        registry.register("p15-usage", agent);
        try {
            List<Frame> frames = postSse(token, "p15-usage", "p15a-usage-" + System.currentTimeMillis(),
                "用一句话介绍你自己。", 120_000, 10_000);
            String raw = frames.stream().map(Frame::data).reduce("", (a, b) -> a + "\n" + b);
            Files.writeString(OUT.resolve("15a-token-usage-frames.txt"), raw, StandardCharsets.UTF_8);
            assertTrue(frames.stream().anyMatch(f -> f.type().equals("RUN_FINISHED")), "应收到 RUN_FINISHED");
            assertTrue(raw.contains("token_usage"),
                "emit-token-usage=true 时帧流应含 token_usage 事件（"
                    + frames.size() + " 帧内无命中——计费事件源缺失）");
            // 三审 k3 P2 强化：载荷解析对账——inputTokens 必须>0（非仅子串命中）
            java.util.regex.Matcher um = java.util.regex.Pattern
                .compile("\"inputTokens\":(\\d+)").matcher(raw);
            assertTrue(um.find() && Integer.parseInt(um.group(1)) > 0,
                "token_usage 事件载荷应含正值 inputTokens（计费数据源非空）");
            int idx = raw.indexOf("token_usage");
            System.out.println("[P15a] token_usage 事件命中: "
                + raw.substring(Math.max(0, idx - 60), Math.min(raw.length(), idx + 200)).replace('\n', '|'));
        } finally {
            registry.unregister("p15-usage");
            SaTokenContextMockUtil.setMockContext(() -> StpUtil.logoutByTokenValue(token));
        }
    }

    // ==================== 工具方法 ====================

    /** 占住一个 serverSideMemory 会话：POST → 读到首帧 → 断开（返回首帧耗时 ms） */
    private long occupySession(String token, String threadId) {
        long t0 = System.currentTimeMillis();
        List<Frame> frames = postSse(token, "p15-never", threadId, "占位", 10_000, 1);
        if (frames.isEmpty()) {
            throw new IllegalStateException("占位会话未收到首帧（" + threadId + "）");
        }
        return System.currentTimeMillis() - t0;
    }

    /**
     * POST SSE 并收帧。
     *
     * @param readTimeoutMs 总读取超时（读到 {@code minFrames} 帧即返回，或流结束/超时）；
     *                        实际上限受 socketReadGuard 的 SO_TIMEOUT=30s 截断（双轨护栏，30s 先生效）
     * @param minFrames     读到该帧数即提前断开（会话占用场景传 1）
     */
    private List<Frame> postSse(String token, String agentId, String threadId, String userText,
                                long readTimeoutMs, int minFrames) {
        String body = "{\"threadId\":\"" + threadId + "\",\"runId\":\"r-" + System.nanoTime() + "\","
            + "\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"" + userText + "\"}],"
            + "\"state\":{},\"forwardedProps\":{}}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        headers.set("clientid", CLIENT_ID);

        long t0 = System.currentTimeMillis();
        List<Frame> frames = new ArrayList<>();
        rest.execute("/agui/run/" + agentId, HttpMethod.POST, req -> {
                req.getHeaders().putAll(headers);
                req.getBody().write(body.getBytes(StandardCharsets.UTF_8));
            }, resp -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(resp.getBody(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (System.currentTimeMillis() - t0 > readTimeoutMs) {
                            break;
                        }
                        if (!line.startsWith("data:")) {
                            continue;
                        }
                        long at = System.currentTimeMillis() - t0;
                        String data = line.substring(5).trim();
                        frames.add(new Frame(eventType(data), at, data));
                        if (frames.size() >= minFrames
                                || System.currentTimeMillis() - t0 > readTimeoutMs) {
                            break;
                        }
                    }
                }
                return frames;
            });
        return frames;
    }

    /** T4 双 mock 登录配方（HTTP 可用形态）：extra 必带 clientid（SaInterceptor 比对 header） */
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
        assertNotNull(token, "应取得 token（此处不得 logout——token 供 HTTP 调用，收尾再清理）");
        return token;
    }

    private static String eventType(String data) {
        try {
            return new ObjectMapper().readTree(data).path("type").asText("UNKNOWN");
        } catch (Exception e) {
            return "UNPARSED";
        }
    }

    private static String extractCode(String body) {
        try {
            return body == null ? "null" : String.valueOf(new ObjectMapper().readTree(body).path("code").asInt());
        } catch (Exception e) {
            return "raw";
        }
    }

    /** 应用层输入尺寸守卫（X-13 P0 原型：S-03 上限 16K 字符、按字符数计、超限给拆分建议文案） */
    record InputSizeGuard(boolean allowed, String rejection) {
        static final int MAX_CHARS = 16_384;

        static InputSizeGuard check(String content) {
            if (content == null) {
                return new InputSizeGuard(true, null);
            }
            int len = content.length();
            if (len <= MAX_CHARS) {
                return new InputSizeGuard(true, null);
            }
            return new InputSizeGuard(false,
                "单条消息过长（" + len + " 字符，上限 " + MAX_CHARS + "），请拆分后分条发送");
        }
    }

    @AfterAll
    static void noteContext() {
        System.out.println("[P15a] 类级形态：server-side-memory=true / max-thread-sessions=2 / emit-token-usage=true");
    }
}
