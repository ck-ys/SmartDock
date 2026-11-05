package org.dromara.assistant.automation.service;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.automation.domain.DcsAutomation;
import org.dromara.assistant.automation.domain.DcsAutomationRun;
import org.dromara.assistant.automation.mapper.DcsAutomationMapper;
import org.dromara.assistant.automation.mapper.DcsAutomationRunMapper;
import org.dromara.assistant.core.AssistantAgentFactory;
import org.dromara.assistant.service.AssistantConfigService;
import org.dromara.assistant.service.AssistantConversationService;
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
 * 「账号还在、还有助手权限、租户助手没关」），用 {@link AssistantAgentFactory#createReadOnly}
 * 建只读 agent，跑一轮后落会话 + 消息 + 运行记录 + 站内通知。</p>
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

    private final DcsAutomationMapper automationMapper;
    private final DcsAutomationRunMapper runMapper;
    private final AssistantConversationService conversationService;
    private final AssistantAgentFactory agentFactory;
    private final AssistantConfigService configService;
    private final AutomationNotifier notifier;
    private final AutomationFailurePolicy failurePolicy;
    private final ISysUserService userService;
    private final ISysPermissionService permissionService;
    private final Executor executor;

    public AutomationRunner(DcsAutomationMapper automationMapper,
                            DcsAutomationRunMapper runMapper,
                            AssistantConversationService conversationService,
                            AssistantAgentFactory agentFactory,
                            AssistantConfigService configService,
                            AutomationNotifier notifier,
                            AutomationFailurePolicy failurePolicy,
                            ISysUserService userService,
                            ISysPermissionService permissionService,
                            @Qualifier("automationExecutor") Executor executor) {
        this.automationMapper = automationMapper;
        this.runMapper = runMapper;
        this.conversationService = conversationService;
        this.agentFactory = agentFactory;
        this.configService = configService;
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
        DcsAutomationRun run = runMapper.selectOne(tenantId, runId);
        if (run == null || !"RUNNING".equals(run.getStatus())) {
            // 已被超时收口（或已删）：直接退出，不再烧一次模型调用
            return;
        }
        // 认领时刻不等于开跑时刻：队列里排过的这段时间不算运行超时（否则排队久的会被
        // sweepStale 判超时，真跑完的成功结果又因「只从 RUNNING 迁出」被静默丢弃）
        long started = System.currentTimeMillis();
        runMapper.touchStarted(runId, now());
        DcsAutomation automation = automationMapper.selectOne(tenantId, run.getAutomationId());
        if (automation == null || "1".equals(automation.getDelFlag())) {
            fail(tenantId, runId, null, "这条自动化已被删除", true);
            return;
        }

        LoginUser user;
        try {
            user = buildUser(automation.getUserId(), tenantId);
            configService.assertOpen(tenantId);
        } catch (ServiceException e) {
            fail(tenantId, runId, null, e.getMessage(), true);
            return;
        }

        String threadId = run.getThreadId() == null ? THREAD_PREFIX + runId : run.getThreadId();
        String aguiRun = "auto-" + runId;
        Long conversationId;
        try {
            conversationId = conversationService.ensureConversation(tenantId, automation.getUserId(),
                threadId, automation.getTitle());
            conversationService.appendUserMessage(tenantId, conversationId, automation.getUserId(),
                promptOf(automation));
            safeRegisterRun(tenantId, automation.getUserId(), threadId, aguiRun);
        } catch (Exception e) {
            log.warn("[Automation] 结果会话创建失败 run={}: {}", runId, e.toString());
            fail(tenantId, runId, null, "无法打开结果会话：" + friendly(e), true);
            return;
        }

        ReActAgent agent = null;
        String answer;
        try {
            agent = agentFactory.createReadOnly(user, threadId);
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
            try {
                conversationService.appendAssistantMessage(tenantId, conversationId,
                    automation.getUserId(), "（这次自动化没有跑完）" + reason, null, "error");
                conversationService.finishRun(aguiRun, "FAILED", "error");
            } catch (Exception ignore) {
                // 会话写失败不阻断运行记录收口
            }
            fail(tenantId, runId, conversationId, reason, true);
            return;
        }

        try {
            conversationService.appendAssistantMessage(tenantId, conversationId,
                automation.getUserId(), answer, null, "normal");
            conversationService.finishRun(aguiRun, "COMPLETED", "normal");
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
        DcsAutomationRun run = runMapper.selectOne(tenantId, runId);
        if (run == null) {
            return;
        }
        DcsAutomation automation = automationMapper.selectOne(tenantId, run.getAutomationId());
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
        if (!perms.contains("*:*:*") && !perms.contains("dcs:assistant:use")) {
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
            conversationService.registerRun(tenantId, userId, threadId, aguiRun);
        } catch (Exception e) {
            log.warn("[Automation] run 登记失败（不影响执行） thread={}: {}", threadId, e.toString());
        }
    }

    // ---------------------------------------------------------------- 文案

    private String promptOf(DcsAutomation a) {
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
