package org.dromara.test.assistant;

import cn.dev33.satoken.context.mock.SaTokenContextMockUtil;
import cn.dev33.satoken.stp.StpUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agui.registry.AguiAgentRegistry;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.extensions.model.openai.compat.deepseek.DeepSeekFormatter;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.satoken.utils.LoginHelper;
import com.smartdock.domain.DcsAiConfig;
import com.smartdock.mapper.DcsAiConfigMapper;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-T6 探针：Undertow × AG-UI starter 端点 SSE 全链实测（实测⑥，真实付费流式调用 ×1）。
 * <p>判据（计划 Task 6）：</p>
 * <ul>
 *   <li>Step1：登录态（T4 双 mock 配方 + clientid extra，token 走共享 Redis）POST /agui/run/{agentId}
 *       → SSE 事件流逐帧到达：RUN_STARTED 首帧、TEXT_MESSAGE_* 中段≥3 帧（流式非憋包）、
 *       RUN_FINISHED 终帧、无 RUN_ERROR；真实事件序列落盘 03-agui-events.json（更新 T3 占位）；</li>
 *   <li>Step2：长回答（≈600 字，生成 30s+ 量级）不掐断——RUN_FINISHED 到达即 Undertow 异步链全程未断；
 *       记录 AguiProperties 默认 runTimeout/sseTimeout 与逐帧间隔分布（憋包检测：帧间最大间隔 ≪ 总时长）；</li>
 *   <li>对照：无 token POST → 业务码 401（P03 结论在登录链路上复核）。</li>
 * </ul>
 * <p>预算纪律：单遍 1 次模型调用（流式）。</p>
 */
@Tag("p0probe")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class P06AguiSseTest {

    private static final long CONFIG_ID = 9100000000003L;
    private static final String CLIENT_ID = "e5cd7e4891bf95d1d19206ce24a7b32e";
    private static final Path OUT = Path.of("target", "p06");

    @BeforeAll
    static void ensureOutDir() throws Exception {
        // modelLevelStreamingCadence 单独跑也写 OUT——目录创建提到 @BeforeAll（三审 P2 修正）
        Files.createDirectories(OUT);
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    DcsAiConfigMapper aiConfigMapper;

    @Autowired
    AguiAgentRegistry registry;

    /** 事件帧：类型 + 相对首帧毫秒 + data 摘要 */
    record Frame(String type, long atMs, String data) {}

    @Test
    void sseStreamFullChain() throws Exception {
        // ② 先登录拿 token（T4 双 mock 配方；extra 带 clientid 过 SaInterceptor 校验）——
        //    提前到 try 外，finally 才能在任何断言失败时按 token 清理（三审 P2 修正）
        String token = loginAsTestUser();
        try {
        // ① 注册探针 agent（流式模型——SSE 增量帧的前提）
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg, "978311 DeepSeek 配置应存在");
        ReActAgent agent = ReActAgent.builder()
            .sysPrompt("你是 DCS 数据采集系统的工作助手。无论用户问什么，都用恰好约600字的中文段落作答，"
                + "分四段，每段以『第X段』开头。不要使用任何工具。")
            .model(OpenAIChatModel.builder()
                .baseUrl(cfg.getBaseUrl()).apiKey(cfg.getApiKey()).modelName(cfg.getModelName())
                .stream(true).formatter(new DeepSeekFormatter()).build())
            .maxIters(2)
            .build();
        registry.register("p0-agui", agent);
        assertTrue(registry.hasAgent("p0-agui"));

        // ③ 无 token 对照（登录链路复核 P03 结论）
        HttpHeaders bare = new HttpHeaders();
        bare.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<String> unauth = rest.exchange("/agui/run/p0-agui", HttpMethod.POST,
            new HttpEntity<>("{}", bare), String.class);
        System.out.println("[P06] 无 token -> HTTP " + unauth.getStatusCode().value()
            + " body.code=" + extractCode(unauth.getBody()));
        // 三审 P2 修正：精确断言业务码 401（原"非 200"宽松判据 403/500 也算过）
        assertEquals("401", extractCode(unauth.getBody()),
            "未登录应被拦为业务码 401（HTTP " + unauth.getStatusCode().value() + "）");

        // ④ 登录态 SSE 全链：逐帧读流并记时间戳
        String body = "{\"threadId\":\"p06-" + System.currentTimeMillis() + "\",\"runId\":\"r1\","
            + "\"messages\":[{\"id\":\"m1\",\"role\":\"user\",\"content\":\"介绍一下你自己的定位。\"}],"
            + "\"state\":{},\"forwardedProps\":{}}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + token);
        headers.set("clientid", CLIENT_ID);

        long t0 = System.currentTimeMillis();
        List<Frame> frames = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        rest.execute("/agui/run/p0-agui", HttpMethod.POST, req -> {
            req.getHeaders().putAll(headers);
            req.getBody().write(body.getBytes(StandardCharsets.UTF_8));
        }, resp -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(resp.getBody(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    long at = System.currentTimeMillis() - t0;
                    String data = line.substring(5).trim();
                    String type = eventType(data);
                    frames.add(new Frame(type, at, data));
                    raw.append("{\"at_ms\":").append(at).append(",\"type\":\"").append(type)
                       .append("\",\"data\":").append(data).append("}\n");
                }
            }
            return frames;
        });
        long totalMs = System.currentTimeMillis() - t0;
        dump(raw.toString(), frames.size(), totalMs);

        // ⑤ 断言：首帧 RUN_STARTED；文本帧≥3（流式）；终帧 RUN_FINISHED；无 RUN_ERROR；长流未掐断
        assertTrue(frames.size() >= 3, "应有≥3 帧（实测=" + frames.size() + "）");
        assertEquals("RUN_STARTED", frames.get(0).type(), "首帧应为 RUN_STARTED");
        long textFrames = frames.stream().filter(f -> f.type().startsWith("TEXT_MESSAGE")).count();
        assertTrue(textFrames >= 3, "TEXT_MESSAGE_* 帧≥3（流式逐帧到达，非一次性整包）");
        assertEquals("RUN_FINISHED", frames.get(frames.size() - 1).type(), "终帧应为 RUN_FINISHED（未掐断）");
        assertTrue(frames.stream().noneMatch(f -> f.type().equals("RUN_ERROR")), "不得出现 RUN_ERROR");
        // ⑥ 节律断言（憋包检测换判据——原 maxGap 判据会被思考期静默误伤）：
        //    a) RUN_STARTED 应即时到达（服务器不压首帧）；
        //    b) TEXT 帧铺开窗口 ≥500ms（若阻塞后一次性分块，铺开≈0）；
        //    c) 首 TEXT 帧前的大间隔归属（思考期 vs 阻塞）由 modelLevelStreamingCadence 对照判定
        long runStartedAt = frames.get(0).atMs();
        long firstTextAt = -1, lastTextAt = -1;
        for (Frame f : frames) {
            if (f.type().startsWith("TEXT_MESSAGE")) {
                if (firstTextAt < 0) {
                    firstTextAt = f.atMs;
                }
                lastTextAt = f.atMs;
            }
        }
        System.out.println("[P06] RUN_STARTED@ " + runStartedAt + "ms, 首TEXT@ " + firstTextAt
            + "ms, 末TEXT@ " + lastTextAt + "ms, 总 " + totalMs + "ms, 帧数=" + frames.size());
        assertTrue(runStartedAt <= 3_000, "RUN_STARTED 应≤3s 到达（实测 " + runStartedAt + "ms）——服务器即时响应");
        assertTrue(lastTextAt - firstTextAt >= 500,
            "TEXT 帧应铺开≥500ms（实测 " + (lastTextAt - firstTextAt) + "ms）——铺开≈0=阻塞后一次性分块");

        } finally {
            // 收尾（三审 P2）：断言失败也必须清理——注销探针 agent + 按 token 清登录态（共享 Redis 不留活 token）
            registry.unregister("p0-agui");
            SaTokenContextMockUtil.setMockContext(() -> StpUtil.logoutByTokenValue(token));
        }
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
        assertNotNull(token, "应取得 token（注意：此处不得 logout——token 供 HTTP 调用，收尾再清理）");
        return token;
    }

    private static String eventType(String data) {
        try {
            JsonNode node = new ObjectMapper().readTree(data);
            return node.path("type").asText("UNKNOWN");
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

    private static void dump(String rawFrames, int frameCount, long totalMs) throws Exception {
        String sb = "{\n  \"probe\": \"P06 agui SSE 全链\",\n  \"total_ms\": " + totalMs
          + ",\n  \"frame_count\": " + frameCount + ",\n  \"frames\": [\n" + rawFrames + "  ]\n}\n";
        Files.write(OUT.resolve("03-agui-events.json"), sb.getBytes(StandardCharsets.UTF_8));
        System.out.println("[P06] 事件序列落盘 -> " + OUT.toAbsolutePath().resolve("03-agui-events.json"));
    }

    /**
     * 模型层流式节律对照（SSE 大间隔归因用，1 次真实调用）：
     * 直连 agent.stream（不经 HTTP/AG-UI），记录每个事件到达时刻——
     * 若文本事件在总时长内渐进铺开=真流式（SSE 首帧大间隔=思考期静默）；
     * 若长时间静默后末尾突发=模型客户端阻塞后分块（T12 心跳方案的直接依据）。
     */
    @Test
    void modelLevelStreamingCadence() throws Exception {
        DcsAiConfig cfg = aiConfigMapper.selectById(CONFIG_ID);
        assertNotNull(cfg);
        ReActAgent agent = ReActAgent.builder()
            .sysPrompt("你是 DCS 数据采集系统的工作助手。无论用户问什么，都用恰好约600字的中文段落作答，"
                + "分四段，每段以『第X段』开头。不要使用任何工具。")
            .model(OpenAIChatModel.builder()
                .baseUrl(cfg.getBaseUrl()).apiKey(cfg.getApiKey()).modelName(cfg.getModelName())
                .stream(true).formatter(new DeepSeekFormatter()).build())
            .maxIters(2)
            .build();

        long t0 = System.currentTimeMillis();
        List<long[]> marks = new ArrayList<>();   // [atMs, isTextDelta]
        List<String> typeNames = new ArrayList<>();
        io.agentscope.core.agent.RuntimeContext rc = io.agentscope.core.agent.RuntimeContext.builder()
            .userId("t:978311:p06").sessionId("p06-raw-" + System.currentTimeMillis()).build();
        agent.stream(java.util.List.of(Msg.builderForRole(MsgRole.USER)
                .textContent("介绍一下你自己的定位。").build()),
            io.agentscope.core.agent.StreamOptions.defaults(), rc)
            .timeout(Duration.ofSeconds(120))
            .doOnNext(ev -> {
                long at = System.currentTimeMillis() - t0;
                // Event 是包装类：类型看 getType()，文本增量=消息里带 TextBlock 且非终帧
                boolean hasText = ev.getMessage() != null
                    && !ev.getMessage().getContentBlocks(io.agentscope.core.message.TextBlock.class).isEmpty();
                marks.add(new long[]{at, hasText ? 1 : 0});
                typeNames.add(ev.getType().toString());
            })
            .blockLast();
        long total = System.currentTimeMillis() - t0;

        long firstText = -1, lastText = -1, textCount = 0;
        for (long[] m : marks) {
            if (m[1] == 1) {
                if (firstText < 0) {
                    firstText = m[0];
                }
                lastText = m[0];
                textCount++;
            }
        }
        System.out.println("[P06-raw] 事件=" + marks.size() + " 类别样本=" + typeNames.stream().distinct().toList());
        System.out.println("[P06-raw] 总=" + total + "ms, 首文本事件@ " + firstText
            + "ms, 末文本事件@ " + lastText + "ms, 文本事件数=" + textCount);
        // 节律结论：文本事件铺开窗口占大头=真流式；铺开≈0=阻塞后分块（登记 T12 心跳依据）
        long spread = lastText - firstText;
        System.out.println("[P06-raw] 文本铺开窗口=" + spread + "ms / 总 " + total + "ms");
        assertTrue(textCount >= 3, "模型层应有多个文本事件（实测 " + textCount + "）");
        Files.write(OUT.resolve("06-model-cadence.txt"),
            ("events=" + marks.size() + "\ntotal_ms=" + total + "\nfirst_text_ms=" + firstText
                + "\nlast_text_ms=" + lastText + "\ntext_events=" + textCount
                + "\nspread_ms=" + spread + "\ntypes=" + typeNames.stream().distinct().toList() + "\n").getBytes(StandardCharsets.UTF_8));
    }
}
