package org.dromara.assistant.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsAssistantConversation;
import org.dromara.assistant.mapper.AssistantConversationMapper;
import org.dromara.assistant.persist.AssistantPendingInterruptCache;
import org.dromara.assistant.persist.AssistantPendingInterruptCache.PendingInterrupt;
import org.dromara.assistant.persist.AssistantRunRecorder;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.satoken.utils.LoginHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 悬挂 interrupt 入口自动解锁过滤器（二站 T2，2026-10-06；形态 α=T0 探针定案）。
 *
 * <p><b>挂点为何是 Servlet Filter 而非 resolver</b>：框架 {@code AguiRuntimeContextRequest}
 * final 且无 input setter、四个官方定制点（resolver/enricher/converter/adapterFactory）都
 * 不经手 RunAgentInput（javap 实证）——计划 §3.2 原设 resolver 注入不可达，改在
 * /agui/run/* 进框架前重写 JSON body，把缓存内全部悬挂以 resume=cancelled 合入本次
 * RunAgentInput 同发。不改框架 jar、不反射私有结构（范围红线）。</p>
 *
 * <p><b>只动该动的请求</b>（shouldNotFilter 快路径零 body 读取）：非 POST / 非
 * /agui/run/* / 缓存空 / body 带 resume（前端确认卡正常流绝不打扰）一概直通。
 * 注入条件=body 无 resume + threadId 命中缓存 + <b>请求者是 thread 属主</b>
 * （G7 批G 1008：悬挂缓存按裸 threadId 键控，此前任何登录用户拿他人会话号发一条
 * 无 resume 消息就能作废人家待确认卡——DoS 级；现与 AssistantHitlController.pending
 * 同款 token 解析+会话表属主比对，非属主跳过注入，resolver 异主 403 照常兜底）。</p>
 *
 * <p><b>安全性</b>：注入的 resume 只含 status=cancelled（作废旧确认卡），注入后的 run
 * 仍走 resolver 全链前置闸（登录/体积/开关/限额/准入），计费照常按一轮——α 形态
 * 无独立取消 run，无需配额豁免（β 已废）。</p>
 *
 * <p><b>留痕</b>（拍板 Q2=A）：每次自动解锁写审计行 auto_cancel_stale_interrupt
 * （threadId/toolCallId 列表/距今时长），对齐「每次查库都留痕」口径。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantResumeUnlockFilter extends OncePerRequestFilter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** body 读取上限（防御性；G4 体积闸 16K 在 resolver 侧更严）——超限不注入直通 */
    private static final int MAX_BODY = 64 * 1024;

    /** 与批F 规划的 /agui 路径级小过滤器同槽位，顺序交默认（不依赖 Sa-Token 过滤顺序） */
    private static final String AGUI_RUN_PREFIX = "/agui/run/";

    private final AssistantPendingInterruptCache pendingInterrupts;
    private final AssistantConversationMapper conversationMapper;
    /** 三席审查 P1-1 修：cancelled 注入即弃卡——同步回收 runRecorder 保留的 INTERRUPTED
     *  步骤/思考缓冲，防 run B 同 thread 快照把旧回合幽灵步骤并入新消息 tool_steps */
    private final AssistantRunRecorder runRecorder;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod())
            || pendingInterrupts.isEmpty()
            || request.getRequestURI() == null
            || !request.getRequestURI().substring(request.getContextPath().length())
                .startsWith(AGUI_RUN_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        long len = request.getContentLengthLong();
        // 读 body 前先决断：超限/非 JSON 直通（不消费流，下游照常读）
        if (len > MAX_BODY) {
            chain.doFilter(request, response);
            return;
        }
        String ct = request.getContentType();
        if (ct != null && !ct.toLowerCase().startsWith("application/json")) {
            chain.doFilter(request, response);
            return;
        }
        byte[] raw = request.getInputStream().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) {
            // 实际体超限（Content-Length 缺报/chunked）——读完剩余流全量回放不注入。
            // 三审 P2-1 修：只回放已读前段会静默截断 body（长会话 history 可超 64K）
            byte[] rest = request.getInputStream().readAllBytes();
            byte[] full = new byte[raw.length + rest.length];
            System.arraycopy(raw, 0, full, 0, raw.length);
            System.arraycopy(rest, 0, full, raw.length, rest.length);
            chain.doFilter(new BodyRequest(request, full), response);
            return;
        }
        // 已读 body：无论是否改写，下游必须拿回放包装（流只能读一次）
        byte[] out = rewrite(request, raw);
        chain.doFilter(new BodyRequest(request, out), response);
    }

    /**
     * 判定+改写（纯函数形态）：无 resume 且 threadId 命中缓存 → 注入全量
     * resume=cancelled 并留审计行；其余场景原样返回 raw。
     */
    private byte[] rewrite(HttpServletRequest request, byte[] raw) {
        JsonNode root;
        try {
            root = MAPPER.readTree(raw);
        } catch (Exception e) {
            return raw;
        }
        if (!(root instanceof ObjectNode body)) {
            return raw;
        }
        // resume 在场=前端确认卡正常流（resolved/cancelled），绝不打扰；空数组视为缺席
        // （框架把空 resume 当无效，注入补全才是出路）
        JsonNode resumeNode = body.get("resume");
        boolean hasResume = resumeNode != null && resumeNode.isArray() && resumeNode.size() > 0;
        if (hasResume) {
            return raw;
        }
        String threadId = body.path("threadId").asText(null);
        List<PendingInterrupt> pending = pendingInterrupts.peek(threadId);
        if (pending == null || pending.isEmpty()) {
            return raw;
        }
        // G7(批G 1008)：注入前验属主——悬挂缓存裸 threadId 键控，非属主请求不得触发
        // 自动解锁（否则知道他人会话号即可作废其待确认卡）。挂起过 interrupt 的 thread
        // 必有会话行（resolver 每次运行前 ensureConversation），属主比对与
        // HitlController.pending 同款；解析不出身份/无会话行/异主一律跳过注入原样直通
        LoginUser requester = resolveRequester(request);
        DcsAssistantConversation conv = requester == null ? null
            : conversationMapper.selectByThread(requester.getTenantId(), threadId);
        if (requester == null || conv == null || !requester.getUserId().equals(conv.getUserId())) {
            log.warn("[AssistantUnlock] 拒绝非属主的悬挂解锁注入 threadId={} requester={}",
                threadId, requester == null ? "anonymous" : requester.getUserId());
            return raw;
        }
        ArrayNode resume = MAPPER.createArrayNode();
        for (PendingInterrupt p : pending) {
            resume.addPOJO(new AguiResumePayload(p.id(), "cancelled"));
        }
        body.set("resume", resume);
        byte[] out;
        try {
            out = MAPPER.writeValueAsBytes(body);
        } catch (Exception e) {
            return raw;
        }
        auditAutoCancel(request, threadId, pending);
        // 三席审查 P1-1：注入=用户弃卡改问新问题，旧回合保留的步骤/思考缓冲就此作废——
        // 不清则 run B 同 thread 落库快照把 A 的幽灵步骤/思考段并入新消息（回放串台）
        runRecorder.evictStepBuffers(Collections.singletonList(threadId));
        return out;
    }

    /** 拍板 Q2=A：自动解锁留痕(批3 微核心化: 旧 dcs 审计表已删, 降级为结构化 warn——
     *  行为审计改由 access log + 本行承担, 批4 评估是否落新审计表)。
     *  ⚠️ 本方法跑在**请求线程**, 与 sa-token 过滤器相对顺序未钉死——全部调用
     *  上下文无关(getLoginUser(token) 走 dao 直读), 后人加代码勿引入上下文依赖。 */
    private void auditAutoCancel(HttpServletRequest request, String threadId,
                                 List<PendingInterrupt> pending) {
        try {
            String token = bearer(request.getHeader("Authorization"));
            if (token == null) {
                return;
            }
            LoginUser user = LoginHelper.getLoginUser(token);
            if (user == null) {
                // 未登录请求：resolver 随后 401，解锁无从发生，不留半截审计
                return;
            }
            long age = System.currentTimeMillis() - pending.get(0).at();
            log.info("[AssistantUnlock] thread 悬挂自动解锁 threadId={} interrupts={} age={}ms "
                    + "toolCallIds={} user={} tenant={}",
                threadId, pending.size(), age,
                pending.stream().map(PendingInterrupt::toolCallId).toList(),
                user.getUserId(), user.getTenantId());
        } catch (Exception e) {
            log.warn("[AssistantUnlock] 自动解锁留痕失败(不拦对话) threadId={}: {} {}",
                threadId, e.getClass().getSimpleName(), e.getMessage());
        }
    }

    /** G7(批G 1008)：免上下文解析请求者——与 auditAutoCancel 同款（本过滤器与
     *  Sa-Token 过滤器相对顺序未钉死，禁依赖请求线程登录上下文）；解析失败按匿名处理 */
    private LoginUser resolveRequester(HttpServletRequest request) {
        try {
            String token = bearer(request.getHeader("Authorization"));
            return token == null ? null : LoginHelper.getLoginUser(token);
        } catch (Exception e) {
            return null;
        }
    }

    private static String bearer(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        return header.startsWith("Bearer ") ? header.substring(7).trim() : header.trim();
    }

    /** resume 载荷（与框架 AguiResume 同构：interruptId+status；payload 免） */
    record AguiResumePayload(String interruptId, String status) {
    }

    /** body 回放包装（改写或不改写都必须包——原流已被本过滤器消费）。
     *  ⚠️ 注入会变长：Content-Length 三口径（int/long/头）必须与回放体一致——下游有按
     *  请求头长度截断读流的读取器（E2E 实证按旧头截断→JSON 解析失败 400）。 */
    static class BodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        BodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        private boolean isContentLength(String name) {
            return name != null && "content-length".equalsIgnoreCase(name.trim());
        }

        @Override
        public String getHeader(String name) {
            if (isContentLength(name)) {
                return String.valueOf(body.length);
            }
            return super.getHeader(name);
        }

        @Override
        public java.util.Enumeration<String> getHeaders(String name) {
            if (isContentLength(name)) {
                return java.util.Collections.enumeration(List.of(String.valueOf(body.length)));
            }
            return super.getHeaders(name);
        }

        @Override
        public int getIntHeader(String name) {
            if (isContentLength(name)) {
                return body.length;
            }
            return super.getIntHeader(name);
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream buf = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return buf.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("非异步场景");
                }

                @Override
                public int read() {
                    return buf.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return buf.read(b, off, len);
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(
                new InputStreamReader(new ByteArrayInputStream(body), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
