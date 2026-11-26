package com.smartdock.assistant.service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import lombok.extern.slf4j.Slf4j;
import com.smartdock.assistant.domain.gateway.ReadOnlyAgentGateway;
import com.smartdock.assistant.domain.gateway.TenantConfigGateway;
import com.smartdock.assistant.domain.model.Automation;
import com.smartdock.assistant.domain.model.AutomationRun;
import com.smartdock.assistant.mapper.AutomationMapper;
import com.smartdock.assistant.mapper.AutomationRunMapper;
import com.smartdock.assistant.infrastructure.AssistantAdmissionGate;
import com.smartdock.assistant.infrastructure.AssistantDailyQuota;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.system.domain.vo.SysUserVo;
import org.dromara.system.service.ISysPermissionService;
import org.dromara.system.service.ISysUserService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * 自动化无人值守执行器（2026-11-05 拍板 题1 乙/题2 甲/题5 甲）。
 *
 * <p>不依赖浏览器登录态：按创建人账号重建 {@link LoginUser} 快照（权限现查，触发时重验
 * 「账号还在、还有助手权限、租户助手没关」），经只读 agent 网关（批 8B 紫桥）建只读 agent，
 * 跑一轮后落会话 + 消息 + 运行记录 + 站内通知。</p>
 *
 * <p>写操作一律不执行：工厂在只读模式下把写类/交互类/MCP 工具全部摘掉（题2 甲）；
 * 模型即使被诱导尝试写，工具也根本不在牌面上。</p>
 */
@Slf4j
@Component
public class AutomationRunner {

    /** 运行会话 threadId 前缀（每条运行一个会话，点通知直达那次结果） */
    public static final String THREAD_PREFIX = "auto-";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final int MAX_SUMMARY_CHARS = 300;
    private static final Duration RUN_TIMEOUT = Duration.ofMinutes(10);

    private final AutomationMapper automationMapper;
    private final AutomationRunMapper runMapper;
    /** 7C-1：run 台账（首绑 / 登记 / 终态） */
    private final RunLedgerService runLedgerService;
    /** 7C-1：消息写（user / assistant 落库） */
    private final MessageWriteService messageWriteService;
    /** 批 8B 紫桥：只读 agent 装配链收窄为一个方法，legacy 工厂不进门 */
    private final ReadOnlyAgentGateway agentGateway;
    /** 批 8B 红桥：租户助手配置（开关/日预算）经语义化桥面取用 */
    private final TenantConfigGateway tenantConfig;
    /** E-13（2026-11-06）：对话侧四道闸对无人值守同样生效——暂停用户 / 日预算 / 准入 / 回合上限 */
    private final com.smartdock.assistant.service.AssistantUserQuotaService quotaService;
    private final AssistantDailyQuota dailyQuota;
    private final AssistantAdmissionGate admissionGate;
    private final AutomationNotifier notifier;
    private final AutomationFailurePolicy failurePolicy;
    private final ISysUserService userService;
    private final ISysPermissionService permissionService;
    private final Executor executor;

    public AutomationRunner(AutomationMapper automationMapper,
                            AutomationRunMapper runMapper,
                            RunLedgerService runLedgerService,
                            MessageWriteService messageWriteService,
                            ReadOnlyAgentGateway agentGateway,
                            TenantConfigGateway tenantConfig,
                            com.smartdock.assistant.service.AssistantUserQuotaService quotaService,
                            AssistantDailyQuota dailyQuota,
                            AssistantAdmissionGate admissionGate,
                            AutomationNotifier notifier,
                            AutomationFailurePolicy failurePolicy,
                            ISysUserService userService,
                            ISysPermissionService permissionService,
                            @Qualifier("automationExecutor") Executor executor) {
        this.automationMapper = automationMapper;
        this.runMapper = runMapper;
        this.runLedgerService = runLedgerService;
        this.messageWriteService = messageWriteService;
        this.agentGateway = agentGateway;
        this.tenantConfig = tenantConfig;
        this.quotaService = quotaService;
        this.dailyQuota = dailyQuota;
        this.admissionGate = admissionGate;
        this.notifier = notifier;
        this.failurePolicy = failurePolicy;
        this.userService = userService;
        this.permissionService = permissionService;
        this.executor = executor;
    }

    /**
     * 提交到自动化线程池（扫描器/立即运行共用）。
     *
     * @return false = 队列已满被拒（调用方据此告诉用户「没排上」而不是「已开始」，且这种容量失败
     *         不计入连续失败次数，否则会被挤到自动停用）
     */
    public boolean submit(String tenantId, Long runId) {
        try {
            executor.execute(() -> {
                try {
                    TenantHelper.dynamic(tenantId, () -> execute(tenantId, runId));
                } catch (Throwable t) {
                    log.error("[Automation] 运行线程异常 run={}", runId, t);
                    try {
                        TenantHelper.dynamic(tenantId, () -> fail(tenantId, runId, null, friendly(t), true));
                    } catch (Throwable ignore) {
                        // 二次失败只留日志，run 行由扫描器按超时收口
                    }
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            log.warn("[Automation] 执行队列已满 run={}", runId);
            fail(tenantId, runId, null, "系统繁忙：自动化排队已满，请稍后手动重跑", false);
            return false;
        }
    }

    // ---------------------------------------------------------------- 主流程

    private void execute(String tenantId, Long runId) {
        AutomationRun run = runMapper.selectOne(tenantId, runId);
        if (run == null || !"RUNNING".equals(run.getStatus())) {
            // 已被超时收口（或已删）：直接退出，不再烧一次模型调用
            return;
        }
        // 认领时刻不等于开跑时刻：队列里排过的这段时间不算运行超时（否则排队久的会被
        // sweepStale 判超时，真跑完的成功结果又因「只从 RUNNING 迁出」被静默丢弃）
        long started = System.currentTimeMillis();
        runMapper.touchStarted(runId, now());
        Automation automation = automationMapper.selectOne(tenantId, run.getAutomationId());
        if (automation == null || "1".equals(automation.getDelFlag())) {
            fail(tenantId, runId, null, "这条自动化已被删除", true);
            return;
        }

        LoginUser user;
        try {
            user = buildUser(automation.getUserId(), tenantId);
            tenantConfig.assertOpen(tenantId);
            // E-13（2026-11-06）：被管理员暂停的账号不再烧模型
            quotaService.assertEnabled(tenantId, automation.getUserId());
        } catch (ServiceException e) {
            fail(tenantId, runId, null, e.getMessage(), true);
            return;
        }

        // E-13（2026-11-06）：与对话侧同口径的四道闸——回合计数 → 日预算 → 准入。
        // 此前自动化只查租户开关，用户被暂停/预算用完/准入不过都照常扣费（14 条缺陷里唯一的钱相关 P1）。
        // 被后置闸拒绝时退回合（与 AssistantRuntimeContextResolver 的补偿口径一致）；
        // 模型调用本身失败不退——那一次确实跑过了（同对话侧"跑完即算"）。
        com.smartdock.assistant.service.AssistantUserQuotaService.BillingAwareLimits limits = null;
        try {
            limits = quotaService.billingAwareLimits(tenantId, automation.getUserId());
            if (!limits.creditsMode() || limits.roundLimit() <= 0) {
                dailyQuota.tryTurn(tenantId, automation.getUserId(), limits.roundLimit());
            }
            // 额度三级管控批（2026-11-26 / Q6 补条件）：积分模式先查个人额度——走这条「不算失败」
            // 的路（下面的 catch 传 false），否则用户额度恢复后还得手动重开被停用的自动化。
            // 装饰器里那道闸是兜底：进门放行、模型阶段才超的情形，靠下面的 catch 认额度类异常
            if (limits.creditsMode()) {
                quotaService.assertPersonalCredits(tenantId, automation.getUserId(), "自动化进门");
            }
            try {
                if (!limits.creditsMode()) {
                    dailyQuota.checkBudget(tenantId, tenantConfig.dailyBudget(tenantId));
                }
                admissionGate.admit(tenantId, automation.getUserId());
            } catch (RuntimeException e) {
                if (!limits.creditsMode()) {
                    dailyQuota.refundTurn(tenantId, automation.getUserId());
                }
                throw e;
            }
        } catch (RuntimeException e) {
            // 三审 P2：被暂停/配额用完/准入不过都不是自动化本身坏了——不记连续失败，
            // 否则每天排一次即累计一次，第 3 天整条自动化被自动停用，用户恢复配额后还得手动重开
            fail(tenantId, runId, null, friendly(e), false);
            return;
        }

        String threadId = run.getThreadId() == null ? THREAD_PREFIX + runId : run.getThreadId();
        String aguiRun = "auto-" + runId;
        Long conversationId;
        try {
            conversationId = runLedgerService.ensureConversation(tenantId, automation.getUserId(),
                threadId, automation.getTitle());
            messageWriteService.appendUserMessage(tenantId, conversationId, automation.getUserId(),
                promptOf(automation));
            safeRegisterRun(tenantId, automation.getUserId(), threadId, aguiRun);
        } catch (Exception e) {
            log.warn("[Automation] 结果会话创建失败 run={}: {}", runId, e.toString());
            // 三审 P2：回合已在上方扣了，但这次连模型都没跑到——按「被后置闸拒绝」同口径退回合
            if (limits != null && !limits.creditsMode()) {
                dailyQuota.refundTurn(tenantId, automation.getUserId());
            }
            fail(tenantId, runId, null, "无法打开结果会话：" + friendly(e), true);
            return;
        }

        // 旧板题 3 乙：只读外部服务白名单（默认空=一个不挂；可写服务在挂载面被二次过滤）
        java.util.Set<String> mcpSel = parseMcp(automation.getMcpServers());
        // 三审 P2：真正挂上的外部工具（空=这轮没有任何外部因素，失败照常计数）
        java.util.Set<String> mountedMcp = new java.util.HashSet<>();
        ReActAgent agent = null;
        String answer;
        try {
            agent = agentGateway.createReadOnly(user, threadId, mcpSel, mountedMcp);
            // sessionId 必须显式给：AGUI 链路由框架按 threadId 注入，这里直调不会自动带；
            // 留空会让同一用户的每次运行共用同一个会话槽，历史互相污染。
            RuntimeContext ctx = RuntimeContext.builder()
                .userId("t:" + tenantId + ":" + automation.getUserId())
                .sessionId(threadId)
                .build();
            Msg msg = agent.call(promptOf(automation), ctx).block(RUN_TIMEOUT);
            answer = msg == null ? null : msg.getTextContent();
            if (answer == null || answer.isBlank()) {
                throw new ServiceException("模型没有返回内容");
            }
        } catch (Throwable t) {
            log.warn("[Automation] 执行失败 run={}: {}", runId, t.toString());
            if (agent != null) {
                try {
                    agent.interrupt();
                } catch (Throwable ignore) {
                    // 中断失败不影响收口
                }
            }
            String reason = friendly(t);
            // 三条硬规矩③：真挂上外部工具的自动化，运行失败不计入「连续 3 次自动停用」——
            // 外部服务抖动/离线不是用户的错，不能把整条自动化停掉（宁可多跑几次失败）。
            // 三审 P2（2026-11-07）：判据从「勾选名单」改成「实际挂上没」——服务已删除/停用/
            // 被闸关时一个工具都挂不上，这时的失败没有任何外部因素可归因，必须照常计数，
            // 否则一条残留勾选就能让坏掉的自动化永远停不下来。
            boolean countFailure = mountedMcp.isEmpty();
            // 额度三级管控批（2026-11-26 / Q6 补条件）：模型阶段被个人额度闸拦下的那次也不能计失败。
            // 这正是「还剩 5 分、这次要 10 分」走的路——只修进门那一半，续跑阶段照样把自动化耗死
            // （连 3 次自动停用，用户额度恢复后还得手动重开）。只认自家闸的记号，不认厂商 429：
            // 厂商限流是外部因素，但那条已经被 mountedMcp 判据覆盖，这里不越界扩大
            if (countFailure && isPersonalCreditRejection(t)) {
                countFailure = false;
            }
            try {
                messageWriteService.appendAssistantMessage(tenantId, conversationId,
                    automation.getUserId(), "（这次自动化没有跑完）" + reason, null, "error");
                runLedgerService.finishRun(aguiRun, "FAILED", "error");
            } catch (Exception ignore) {
                // 会话写失败不阻断运行记录收口
            }
            fail(tenantId, runId, conversationId, reason, countFailure);
            return;
        }

        try {
            messageWriteService.appendAssistantMessage(tenantId, conversationId,
                automation.getUserId(), answer, null, "normal");
            runLedgerService.finishRun(aguiRun, "COMPLETED", "normal");
        } catch (Exception e) {
            log.warn("[Automation] 结果消息落库失败 run={}: {}", runId, e.toString());
        }

        int updated = runMapper.finish(tenantId, runId, "SUCCESS", excerpt(answer), null,
            elapsed(started), conversationId, threadId);
        if (updated == 0) {
            return;
        }
        automationMapper.markRun(tenantId, automation.getId(), "SUCCESS", runId, now());
        automationMapper.resetFailures(tenantId, automation.getId());
        notifier.success(automation, runId, threadId, answer);
        log.info("[Automation] 运行成功 automation={} run={} 用时={}ms",
            automation.getId(), runId, elapsed(started));
    }

    // ---------------------------------------------------------------- 收口

    /**
     * 收口为失败。
     *
     * @param countFailure 是否计入「连续失败」——容量类（排队满）不计，否则系统繁忙会把用户
     *                     的自动化推到自动停用
     */
    private void fail(String tenantId, Long runId, Long conversationId, String reason, boolean countFailure) {
        AutomationRun run = runMapper.selectOne(tenantId, runId);
        if (run == null) {
            return;
        }
        Automation automation = automationMapper.selectOne(tenantId, run.getAutomationId());
        String safe = reason == null || reason.isBlank() ? "运行失败（系统错误）" : reason;
        int elapsedMs = run.getStartedAt() == null ? 0
            : (int) Math.max(0, Duration.between(run.getStartedAt(), now()).toMillis());
        int updated = runMapper.finish(tenantId, runId, "FAILED", null,
            truncate(safe, 900), elapsedMs, conversationId, run.getThreadId());
        if (updated == 0 || automation == null) {
            return;
        }
        automationMapper.markRun(tenantId, automation.getId(), "FAILED", runId, now());
        int failures = countFailure ? failurePolicy.record(tenantId, automation.getId()) : 0;
        notifier.failure(automation, runId, safe, failures >= AutomationSchedule.FAILURE_LIMIT);
    }

    // ---------------------------------------------------------------- 身份与工具

    /**
     * 按创建人账号重建登录快照。触发时重查（题3 甲）：账号删除/停用/换租户、助手权限被收回，
     * 都不再执行。
     */
    /** 白名单解析（纯函数）：null/空=空集合；忽略非法片段（保存面已清洗，这里兜底历史脏值）。 */
    private static java.util.Set<String> parseMcp(String raw) {
        if (raw == null || raw.isBlank()) {
            return java.util.Set.of();
        }
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String name = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (name.matches("[a-z0-9_]{1,30}")) {
                out.add(name);
            }
        }
        return out;
    }

    private LoginUser buildUser(Long userId, String tenantId) {
        SysUserVo u = userService.selectUserById(userId);
        if (u == null) {
            throw new ServiceException("创建人账号不存在，自动化无法执行");
        }
        if (!tenantId.equals(u.getTenantId())) {
            throw new ServiceException("创建人已不在这个单位，自动化无法执行");
        }
        if (!"0".equals(u.getStatus())) {
            throw new ServiceException("创建人账号已停用，自动化无法执行");
        }
        Set<String> perms = permissionService.getMenuPermission(userId);
        if (!perms.contains("*:*:*") && !perms.contains("smartdock:assistant:use")) {
            throw new ServiceException("创建人已没有使用 AI 助手的权限，自动化无法执行");
        }
        LoginUser user = new LoginUser();
        user.setTenantId(u.getTenantId());
        user.setUserId(userId);
        user.setDeptId(u.getDeptId());
        user.setUsername(u.getUserName());
        user.setNickname(u.getNickName());
        user.setUserType(u.getUserType());
        user.setMenuPermission(perms);
        user.setRolePermission(permissionService.getRolePermission(userId));
        return user;
    }

    private void safeRegisterRun(String tenantId, Long userId, String threadId, String aguiRun) {
        try {
            runLedgerService.registerRun(tenantId, userId, threadId, aguiRun);
        } catch (Exception e) {
            log.warn("[Automation] run 登记失败（不影响执行） thread={}: {}", threadId, e.toString());
        }
    }

    // ---------------------------------------------------------------- 文案

    private String promptOf(Automation a) {
        return "【自动化】" + a.getTitle() + "\n"
            + "现在是 " + now().format(HUMAN) + "（北京时间）。\n"
            + "请只用只读查询工具完成下面这件事：不要请求人工确认，不要尝试新增、修改或删除任何数据。\n"
            + "能做多少做多少，结果用要点汇报；查不到就直说没有。\n"
            + "任务：" + a.getPrompt();
    }

    private static String excerpt(String text) {
        // 运行摘要也是纯文本位（列表/编辑页两行截断显示），markdown 记号要抹掉
        String t = text == null ? "" : AutomationNotifier.plain(text).strip();
        return truncate(t, MAX_SUMMARY_CHARS);
    }

    private static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        if (text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        int end = text.offsetByCodePoints(0, max);
        return text.substring(0, end) + "…";
    }

    /** 给用户看的原因：业务异常原文；其它一律不暴露内部细节 */
    /**
     * 额度三级管控批（Q6 补条件）：判定「这次失败是不是被个人额度闸拒的」。
     *
     * <p>沿 cause / suppressed 链找自家闸的记号——框架会把 ServiceException 包一层再抛，
     * 只看最外层 {@code getMessage()} 会漏。纯函数，单测直调。</p>
     */
    static boolean isPersonalCreditRejection(Throwable t) {
        int hops = 0;
        for (Throwable cur = t; cur != null && hops < 8; cur = cur.getCause() == cur ? null : cur.getCause(), hops++) {
            String msg = cur.getMessage();
            if (msg != null && msg.contains(AssistantDailyQuota.PERSONAL_CREDIT_MARKER)) {
                return true;
            }
            for (Throwable s : cur.getSuppressed()) {
                if (s != null && s.getMessage() != null
                    && s.getMessage().contains(AssistantDailyQuota.PERSONAL_CREDIT_MARKER)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String friendly(Throwable t) {
        if (t instanceof ServiceException se && se.getMessage() != null && !se.getMessage().isBlank()) {
            return se.getMessage();
        }
        Throwable cause = t.getCause();
        if (cause instanceof ServiceException se && se.getMessage() != null) {
            return se.getMessage();
        }
        String msg = t == null ? "" : String.valueOf(t.getMessage());
        if (msg != null && (msg.contains("Timeout") || msg.contains("timed out"))) {
            return "运行超时（超过 10 分钟）";
        }
        return "运行失败（系统错误），可以稍后手动重跑";
    }

    private static int elapsed(long startedMs) {
        return (int) Math.max(0, System.currentTimeMillis() - startedMs);
    }

    private static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
