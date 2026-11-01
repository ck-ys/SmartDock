package org.dromara.assistant.core;

import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.service.AssistantActionService;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 写操作审计挂钩（三批③，2026-11-01）：工具执行前后向动作账本落行/收口——
 * 放行执行走完 EXECUTING→DONE/FAILED 全生命周期，release_mode 按
 * {@link AssistantReleaseMode#resolve} 建时快照判定。
 *
 * <p><b>非中间件——工具层挂钩</b>：账本行的粒度是「一次工具执行」而非「一次 run」，
 * 挂在 BizToolAgentAdapter/MemoryTools 执行体前后，天然与实际执行对齐（含平台
 * 放行的 save_memory）。hook 内含 {@link ProviderToolRegistry.AuditScope}，
 * 构造时快照档位（levelAtBuild）与放行集，无需另接集合。</p>
 *
 * <p><b>失败不连坐</b>：账本写入/收口失败只 warn 不炸工具执行（对齐 enricher
 * 拒绝留痕的口径）——代价是极端故障下漏行，日志留痕可查。</p>
 *
 * <p>幂等键 {@code assistant:{tenant}:{runRef}:{tool}:{seq}}（seq=hook 实例自增计数器，
 * 同一 run 内多笔写操作不撞键）；runId 线程换乘时降级 UUID
 * （{@link AssistantRunContext} javadoc 登记的既有限制——审计四维不含 runId，不受影响）。</p>
 */
@Slf4j
public class AssistantActionLedgerHook {

    /** 幂等键列宽（与 recordRejected 同口径：超长截断防插入报错被吞成 warn=审计静默丢失） */
    static final int KEY_MAX = 128;

    /**
     * 账本写入口（纯单测造假点——生产由 {@link #AssistantActionLedgerHook(AssistantActionService,
     * String, Long, String, ProviderToolRegistry.AuditScope)} 用 AssistantActionService 直连）。
     */
    public interface Port {

        /** 落行（EXECUTING 起步）返回行 id */
        Long open(String tenantId, Long userId, String runId, String threadId, String toolName,
                  String argsJson, String idempotencyKey, String releaseMode);

        /** 收口（ok=DONE / 非 ok=FAILED；refId 非空时结果摘要钉「（记录 #id）」尾段） */
        void finish(Long rowId, boolean ok, String summary, Long refId);
    }

    private final Port port;
    private final String tenantId;
    private final Long userId;
    private final String threadId;
    private final ProviderToolRegistry.AuditScope scope;
    /** 建 agent 时的自主权档位快照——与 scope 同刻取值，auto/once 分流不读执行时现值 */
    private final String levelAtBuild;
    /** 同一 hook（=同一会话 agent）内的动作序，幂等键去重用 */
    private final AtomicLong seq = new AtomicLong();

    public AssistantActionLedgerHook(AssistantActionService actionService, String tenantId, Long userId,
                                     String threadId, ProviderToolRegistry.AuditScope scope) {
        this(new ServicePort(actionService), tenantId, userId, threadId, scope);
    }

    public AssistantActionLedgerHook(Port port, String tenantId, Long userId, String threadId,
                                     ProviderToolRegistry.AuditScope scope) {
        this.port = port;
        this.tenantId = tenantId;
        this.userId = userId;
        this.threadId = threadId;
        this.scope = scope;
        this.levelAtBuild = AssistantAutonomyHolder.level(threadId);
    }

    /**
     * 执行前落行（EXECUTING）。返回行 id 供 {@link #finish} 收口；任何异常只 warn 返回
     * null——审计失败绝不炸工具执行（null 行 id 时 finish 为 no-op，工具行为与无 hook 一致）。
     */
    public Long open(String toolName, String argsJson) {
        try {
            // 第二参只来自建时 auto 快照（scope.autonomyAllowed），不得用 writeTools.contains——
            // 否则 always 档经确认卡放行的执行会全被误记成 auto/once
            String releaseMode = AssistantReleaseMode.resolve(toolName,
                scope != null && scope.autonomyAllowed().contains(toolName), levelAtBuild);
            String runRef = AssistantRunContext.currentRunId();
            if (runRef == null || runRef.isBlank()) {
                runRef = UUID.randomUUID().toString();
            }
            long n = seq.incrementAndGet();
            return port.open(tenantId, userId, runRef, threadId, toolName, argsJson,
                buildKey(tenantId, runRef, toolName, n), releaseMode);
        } catch (Exception e) {
            log.warn("[AssistantActionAudit] 账本落行失败（不炸工具执行）tool={}: {}", toolName, e.getMessage());
            return null;
        }
    }

    /** 执行后收口（DONE/FAILED）；rowId=null（未落行/落行失败）时 no-op */
    public void finish(Long rowId, boolean ok, String summary, Long refId) {
        if (rowId == null) {
            return;
        }
        try {
            port.finish(rowId, ok, summary, refId);
        } catch (Exception e) {
            log.warn("[AssistantActionAudit] 账本收口失败 rowId={}: {}", rowId, e.getMessage());
        }
    }

    /** 幂等键拼接（纯函数）：超 128 截断（对齐 recordRejected :89-90 口径）。
     *  public=跨模块单测锚点（ruoyi-admin 的 AssistantActionAuditTest 直调） */
    public static String buildKey(String tenantId, String runRef, String toolName, long seqNo) {
        String key = "assistant:" + tenantId + ":" + runRef + ":" + toolName + ":" + seqNo;
        return key.length() > KEY_MAX ? key.substring(0, KEY_MAX) : key;
    }

    /**
     * 从工具结果 data 取回执锚点（纯函数）：读 "taskId" 数字（dcs_create_task 落任务行 id，
     * 供④撤回直读）。垃圾值/缺键→null（不猜）。public=跨模块单测锚点（同 buildKey）。
     */
    public static Long refIdOf(Object data) {
        if (!(data instanceof Map<?, ?> map)) {
            return null;
        }
        Object v = map.get("taskId");
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignore) {
                return null;
            }
        }
        return null;
    }

    /** AssistantActionService → Port 适配 */
    private record ServicePort(AssistantActionService service) implements Port {

        @Override
        public Long open(String tenantId, Long userId, String runId, String threadId, String toolName,
                         String argsJson, String idempotencyKey, String releaseMode) {
            AssistantActionService.OpenResult r = service.openExecution(
                tenantId, userId, runId, threadId, toolName, argsJson, idempotencyKey, releaseMode);
            return r == null || r.row() == null ? null : r.row().getId();
        }

        @Override
        public void finish(Long rowId, boolean ok, String summary, Long refId) {
            if (ok) {
                service.markDone(rowId, summary, refId);
            } else {
                service.markFailed(rowId, summary, refId);
            }
        }
    }
}
