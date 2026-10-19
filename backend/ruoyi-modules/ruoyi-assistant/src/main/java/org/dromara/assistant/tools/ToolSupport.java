package org.dromara.assistant.tools;

import org.dromara.common.core.domain.model.LoginUser;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 工具返回形状公共件（批B B2，2026-10-04）。
 *
 * <p>与 ToolContracts 键同源：asOf/queriedAs/total/rows/truncated/nextPage/error。
 * B1 打样件 QueryTools 自包含未回抽（绿码不动）；B2 起新工具走本件，
 * 避免三处复制粘贴漂移。</p>
 */
final class ToolSupport {

    private static final DateTimeFormatter ISO =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private ToolSupport() {
    }

    static Map<String, Object> baseShape(ToolIdentity identity) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put(ToolContracts.KEY_AS_OF, nowIso());
        Map<String, Object> as = new LinkedHashMap<>();
        as.put("tenantId", identity.getTenantId());
        as.put("userId", identity.getUserId());
        out.put(ToolContracts.KEY_QUERIED_AS, as);
        return out;
    }

    /** 数据截止时间戳（二站② AU-48：时间锚——搜索/附件等自建 shape 的工具同源取用） */
    static String nowIso() {
        return OffsetDateTime.now(ZoneOffset.ofHours(8)).format(ISO);
    }

    /** 超时包裹：到点即 TimeoutException，后台任务自然结束（不中断，浪费可接受）。 */
    static <T> T withTimeout(long timeoutMs, Supplier<T> body) throws Exception {
        return CompletableFuture.supplyAsync(body::get, TOOL_POOL)
            .get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 工具专用有界池（三审 P1 回修）：工具体是阻塞 JDBC/Redis，禁占 JVM 共享的
     * ForkJoinPool.commonPool（并发会话下会拖累所有并行流用户且并行度退化）。
     * 有界队列+CallerRunsPolicy 背压到调用线程，宁可慢不可饿死全局池。
     */
    static final java.util.concurrent.ExecutorService TOOL_POOL =
        new java.util.concurrent.ThreadPoolExecutor(
            2, 8, 60L, TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "dcs-asst-tool");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());

    static void timeoutShape(Map<String, Object> out, long timeoutMs) {
        out.put(ToolContracts.KEY_ERROR, "TIMEOUT");
        // AU-06: errorType 供模型归因——超时建议重试,别再答成「权限问题」
        out.put("errorType", "TIMEOUT");
        out.put("timeoutMs", timeoutMs);
        out.put("message", "查询超时（" + timeoutMs + "ms），建议重试一次");
    }

    static void errorShape(Map<String, Object> out, Exception e) {
        out.put(ToolContracts.KEY_ERROR, "ERROR");
        out.put("errorType", errorTypeOf(e));
        // 批I 零技术词：模型按纪律把 message 原样转告用户——英文原文（SQL/NPE/"null"）
        // 一律换人话兜底；含中文的服务端文案（ServiceException）保留原样传递
        out.put("message", humanMessage(e, "操作执行失败（系统异常），请稍后再试；持续出现请联系管理员"));
    }

    /** 无中文字符的异常消息（英文原文/null）→ 人话兜底；原始串由调用方 log 落日志 */
    static String humanMessage(Exception e, String fallback) {
        String msg = errorMessage(e);
        boolean hasCjk = msg != null && msg.matches(".*\\p{IsHan}.*");
        return hasCjk ? msg : fallback;
    }

    /**
     * AU-06 失败分类（喂模型防误归因）：DENIED=权限/越权被拒才可向用户提权限；
     * SYSTEM=超时/数据库故障/参数解析等其余失败——试点 PD-3 实锤过模型把任意
     * 失败转述成「可能是权限问题」，让用户白找管理员。
     */
    static String errorTypeOf(Exception e) {
        String msg = errorMessage(e);
        if (msg != null && (msg.contains("无权") || msg.contains("权限") || msg.contains("403"))) {
            return "DENIED";
        }
        return "SYSTEM";
    }

    /** 解包异步包装异常（ExecutionException.getMessage 含原因类 FQCN，不喂模型，k3 P2-3） */
    static String errorMessage(Exception e) {
        Throwable t = e;
        while ((t instanceof java.util.concurrent.ExecutionException
            || t instanceof java.util.concurrent.CompletionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return String.valueOf(t.getMessage());
    }

    static String iso(Object date) {
        if (date == null) {
            return null;
        }
        return OffsetDateTime.ofInstant(((java.util.Date) date).toInstant(),
            ZoneOffset.ofHours(8)).format(ISO);
    }

    static String toJson(Map<String, Object> out) {
        try {
            return MAPPER.writeValueAsString(out);
        } catch (Exception e) {
            return "{\"error\":\"ERROR\",\"message\":\"serialize-failed\"}";
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
        new com.fasterxml.jackson.databind.ObjectMapper();

    /** G5(批G 1008)：Jackson ObjectNode 重载——搜索等用 Jackson 组装的工具同走安全出口 */
    @SuppressWarnings("unchecked")
    static String secureJson(com.fasterxml.jackson.databind.node.ObjectNode out, ToolIdentity identity) {
        return secureJson(MAPPER.convertValue(out, Map.class), identity);
    }

    /**
     * 业务批4 Q1：免 PII 变体（仅注入打标）——姓名是查询目标本体的工具用
     * （search_user「找姓王的」返回王** 无法区分=工具失效）。泄露面=本租户姓名，
     * 与 DCS 页面既有展示（发起人/审核人列、表单选人控件）同宽，不放大；
     * 提示词层另约束「搜人结果仅用于本次定位，勿在无关回答复述他人全名」。
     */
    static String secureJsonNoPii(Map<String, Object> out) {
        String json = toJson(out);
        try {
            org.dromara.assistant.security.AssistantInjectionFilter injection =
                org.dromara.common.core.utils.SpringUtils.getBean(org.dromara.assistant.security.AssistantInjectionFilter.class);
            if (injection.flagged(json)) {
                int close = json.lastIndexOf('}');
                if (close > 0) {
                    json = json.substring(0, close) + ",\"injectionFlagged\":true}";
                }
            }
            return json;
        } catch (Exception e) {
            return json;
        }
    }

    /**
     * 安全出口版序列化（批G G4 入口②）：工具结果进上下文前——
     * ①PII 确定性脱敏（他人姓名/手机/证件/邮箱，掩码形态见 AssistantPiiFilter）；
     * ②注入确定性打标：命中指令模式 → JSON 追加 {@code "injectionFlagged":true}
     * （模型系统提示已声明该标记=数据非指令；防线不依赖模型免疫力，15b 定论）。
     * <p>两个过滤器是无状态单例 @Component，经 SpringUtils 惰性取（工具构造器零改动）；
     * Spring 上下文缺席（极端单测）= 原样返回（安全件不可用不阻断工具）。</p>
     * <p>批J-3 E2E 实锤补充：掩码落在 JSON 数字字面量（userId:9200000**1）会产出非法 JSON，
     * 前端 parseToolJson 双路全挂 → DataCard 永不渲染——掩码后对含 * 的数字值补引号修复。</p>
     */
    static String secureJson(Map<String, Object> out, ToolIdentity identity) {
        String json = toJson(out);
        try {
            org.dromara.assistant.security.AssistantPiiFilter pii =
                org.dromara.common.core.utils.SpringUtils.getBean(org.dromara.assistant.security.AssistantPiiFilter.class);
            org.dromara.assistant.security.AssistantInjectionFilter injection =
                org.dromara.common.core.utils.SpringUtils.getBean(org.dromara.assistant.security.AssistantInjectionFilter.class);
            String masked = org.dromara.assistant.security.AssistantPiiFilter.repairMaskedJsonNumbers(
                pii.mask(json, identity.getTenantId()));
            if (injection.flagged(json)) {
                int close = masked.lastIndexOf('}');
                if (close > 0) {
                    masked = masked.substring(0, close) + ",\"injectionFlagged\":true}";
                }
            }
            return masked;
        } catch (Exception e) {
            return json;
        }
    }

    /** 供 runAs lambda 里使用的登录用户直通（语义化别名） */
    static LoginUser user(ToolIdentity identity) {
        return identity.getLoginUser();
    }
}
