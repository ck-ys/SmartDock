package org.dromara.assistant.automation.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.automation.domain.AutomationBo;
import org.dromara.assistant.automation.domain.DcsAutomation;
import org.dromara.assistant.automation.domain.DcsAutomationRun;
import org.dromara.assistant.automation.domain.vo.AutomationRunVo;
import org.dromara.assistant.automation.domain.vo.AutomationVo;
import org.dromara.assistant.automation.mapper.DcsAutomationMapper;
import org.dromara.assistant.automation.mapper.DcsAutomationRunMapper;
import org.dromara.assistant.domain.DcsAssistantConfig;
import org.dromara.assistant.service.AssistantConfigService;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 自动化业务层（2026-11-05 拍板 题3 甲/题4 乙/题5 甲/题6 甲/题8 甲）。
 *
 * <p>可见性：只看自己的自建项 + 本租户内置项（每日摘要）；每人 5 条上限（事务内咨询锁防并发超卖）。
 * 调度认领在 {@link #claimDue()}：行级锁 + SKIP LOCKED，先推进 next_run_at 再执行。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantAutomationService {

    static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final String BUILTIN_TITLE = "每日未交摘要";
    private static final String BUILTIN_PROMPT =
        "（系统内置）每天 08:00 汇总还没交任务的人，推送给任务发布人。";

    private final DcsAutomationMapper automationMapper;
    private final DcsAutomationRunMapper runMapper;
    private final AssistantConfigService configService;
    private final AutomationRunner runner;
    private final AutomationNotifier notifier;
    private final AutomationFailurePolicy failurePolicy;

    // ---------------------------------------------------------------- 查询

    public List<AutomationVo> list(String tenantId, Long userId, String filter, boolean canManageBuiltin) {
        ensureBuiltin(tenantId);
        List<DcsAutomation> rows = automationMapper.selectVisible(tenantId, userId, normalizeFilter(filter));
        List<AutomationVo> out = new ArrayList<>(rows.size());
        for (DcsAutomation row : rows) {
            out.add(toVo(row, canManageBuiltin));
        }
        return out;
    }

    public AutomationVo detail(String tenantId, Long userId, Long id, boolean canManageBuiltin) {
        return toVo(requireVisible(tenantId, userId, id), canManageBuiltin);
    }

    /** 未来 5 次预览（新建/编辑页日程卡） */
    public List<String> preview(String scheduleType, String scheduleTime, String weekdays) {
        try {
            AutomationSchedule.validate(scheduleType, scheduleTime, weekdays);
        } catch (IllegalArgumentException e) {
            throw new ServiceException(e.getMessage(), 400);
        }
        List<String> out = new ArrayList<>();
        for (LocalDateTime t : AutomationSchedule.preview(now(), scheduleType, scheduleTime, weekdays, 5)) {
            out.add(t.format(MINUTE));
        }
        return out;
    }

    public List<AutomationRunVo> runs(String tenantId, Long userId, Long id, int limit) {
        DcsAutomation a = requireVisible(tenantId, userId, id);
        int safe = Math.min(Math.max(1, limit), 50);
        return runMapper.selectByAutomation(tenantId, a.getId(), safe).stream()
            .map(r -> toRunVo(r, a.getTitle())).toList();
    }

    public List<AutomationRunVo> recentRuns(String tenantId, Long userId, int limit) {
        int safe = Math.min(Math.max(1, limit), 30);
        return runMapper.selectRecent(tenantId, userId, safe).stream()
            .map(r -> toRunVo(r, r.getAutomationTitle())).toList();
    }

    // ---------------------------------------------------------------- 写

    @Transactional(rollbackFor = Exception.class)
    public AutomationVo create(String tenantId, Long userId, AutomationBo bo) {
        validateBo(bo);
        automationMapper.lockUserSlot(tenantId, userId);
        int count = automationMapper.countOwn(tenantId, userId);
        if (count >= AutomationSchedule.MAX_PER_USER) {
            throw new ServiceException("每人最多 " + AutomationSchedule.MAX_PER_USER
                + " 条自动化，先删掉一条再建", 400);
        }
        DcsAutomation a = new DcsAutomation();
        a.setId(IdWorker.getId());
        a.setTenantId(tenantId);
        a.setUserId(userId);
        a.setTitle(bo.getTitle().trim());
        a.setPrompt(bo.getPrompt().trim());
        a.setScheduleType(bo.getScheduleType());
        a.setScheduleTime(bo.getScheduleTime());
        a.setWeekdays(AutomationSchedule.WEEKLY.equals(bo.getScheduleType())
            ? joinDays(bo.getWeekdays()) : null);
        a.setMcpServers(sanitizeMcpServers(bo.getMcpServers()));
        a.setTimeZone("Asia/Shanghai");
        a.setEnabled("1");
        a.setBuiltin("0");
        a.setNextRunAt(AutomationSchedule.next(now(), a.getScheduleType(), a.getScheduleTime(), a.getWeekdays()));
        a.setCreateBy(userId);
        automationMapper.insertOne(a);
        return toVo(a, false);
    }

    @Transactional(rollbackFor = Exception.class)
    public AutomationVo update(String tenantId, Long userId, Long id, AutomationBo bo) {
        validateBo(bo);
        DcsAutomation a = requireOwn(tenantId, userId, id);
        a.setTitle(bo.getTitle().trim());
        a.setPrompt(bo.getPrompt().trim());
        a.setScheduleType(bo.getScheduleType());
        a.setScheduleTime(bo.getScheduleTime());
        a.setWeekdays(AutomationSchedule.WEEKLY.equals(bo.getScheduleType())
            ? joinDays(bo.getWeekdays()) : null);
        a.setMcpServers(sanitizeMcpServers(bo.getMcpServers()));
        a.setNextRunAt("1".equals(a.getEnabled())
            ? AutomationSchedule.next(now(), a.getScheduleType(), a.getScheduleTime(), a.getWeekdays())
            : null);
        a.setUpdateBy(userId);
        if (automationMapper.updateOwn(a) == 0) {
            throw new ServiceException("自动化不存在或无权修改", 404);
        }
        return toVo(automationMapper.selectOne(tenantId, id), false);
    }

    public void delete(String tenantId, Long userId, Long id) {
        requireOwn(tenantId, userId, id);
        if (automationMapper.softDeleteOwn(tenantId, userId, id) == 0) {
            throw new ServiceException("自动化不存在或无权删除", 404);
        }
    }

    public AutomationVo toggle(String tenantId, Long userId, Long id, boolean enabled) {
        DcsAutomation a = requireOwn(tenantId, userId, id);
        LocalDateTime next = enabled
            ? AutomationSchedule.next(now(), a.getScheduleType(), a.getScheduleTime(), a.getWeekdays())
            : null;
        if (automationMapper.toggleOwn(tenantId, userId, id, enabled ? "1" : "0", next) == 0) {
            throw new ServiceException("自动化不存在或无权操作", 404);
        }
        return toVo(automationMapper.selectOne(tenantId, id), false);
    }

    /** 内置项开关（题8 甲）：写租户配置 digest_enabled，卡片状态以配置为准 */
    public AutomationVo toggleBuiltin(String tenantId, Long userId, boolean enabled, boolean canManage) {
        if (!canManage) {
            throw new ServiceException("内置项由管理员开关", 403);
        }
        DcsAutomation builtin = ensureBuiltin(tenantId);
        DcsAssistantConfig cfg = configService.raw(tenantId);
        if (cfg == null) {
            cfg = new DcsAssistantConfig();
            cfg.setTenantId(tenantId);
        }
        cfg.setDigestEnabled(enabled ? "1" : "0");
        configService.update(cfg);
        automationMapper.toggleBuiltin(tenantId, userId, builtin.getId(), enabled ? "1" : "0");
        return toVo(automationMapper.selectOne(tenantId, builtin.getId()), true);
    }

    /** 立即运行一次（题6 甲：失败不自动重试，手动重跑走这里） */
    public Long runNow(String tenantId, Long userId, Long id) {
        DcsAutomation a = requireOwn(tenantId, userId, id);
        if (runMapper.countRunning(tenantId, a.getId()) > 0) {
            throw new ServiceException("这条自动化正在运行，稍等它跑完", 409);
        }
        LocalDateTime now = now();
        DcsAutomationRun run = newRun(a, "MANUAL", now, now);
        if (runMapper.insertIgnore(run) == 0) {
            throw new ServiceException("这条自动化正在运行，稍等它跑完", 409);
        }
        automationMapper.markRun(tenantId, a.getId(), "RUNNING", run.getId(), now);
        if (!runner.submit(tenantId, run.getId())) {
            // 队列满已在 submit 里收口成失败（不计入连续失败）；这里如实告诉用户没排上
            throw new ServiceException("系统繁忙：自动化排队已满，稍后再点一次", 409);
        }
        return run.getId();
    }

    // ---------------------------------------------------------------- 调度

    /**
     * 内置每日摘要跑完记一行运行记录（题8 甲：卡片「最近运行」有据可查）。
     * 摘要本身仍由 {@code AssistantDailyDigestJob} 08:00 推送，这里只记账。
     */
    public void recordBuiltinRun(String tenantId, LocalDateTime scheduledFor, String summary) {
        DcsAutomation builtin = ensureBuiltin(tenantId);
        DcsAutomationRun run = newRun(builtin, "SCHEDULE", scheduledFor, scheduledFor);
        run.setUserId(0L);
        run.setThreadId(null);
        if (runMapper.insertIgnore(run) == 0) {
            return;
        }
        runMapper.finish(tenantId, run.getId(), "SUCCESS", summary, null, 0, null, null);
        automationMapper.markRun(tenantId, builtin.getId(), "SUCCESS", run.getId(), scheduledFor);
    }

    /** 到期任务认领结果（事务提交后由 job 逐个提交执行） */
    public record Claimed(String tenantId, Long runId) {
    }

    /**
     * 认领到期的自建自动化：SKIP LOCKED 取行 → 推进 next_run_at → 落 RUNNING 运行记录。
     * 超过 6h 补跑窗口的记 MISSED（不执行），并把下次直接推到未来，避免积压补跑。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<Claimed> claimDue() {
        LocalDateTime now = now();
        List<DcsAutomation> due = automationMapper.claimDue(now, 50);
        List<Claimed> out = new ArrayList<>();
        for (DcsAutomation a : due) {
            LocalDateTime slot = a.getNextRunAt();
            LocalDateTime base = slot.isBefore(now.minus(AutomationSchedule.CATCH_UP_WINDOW)) ? now : slot;
            LocalDateTime next = AutomationSchedule.next(base, a.getScheduleType(),
                a.getScheduleTime(), a.getWeekdays());
            automationMapper.advanceNextRun(a.getTenantId(), a.getId(), next);

            boolean tooLate = slot.plus(AutomationSchedule.CATCH_UP_WINDOW).isBefore(now);
            if (tooLate) {
                DcsAutomationRun missed = newRun(a, "SCHEDULE", slot, null);
                missed.setStatus("MISSED");
                missed.setThreadId(null);
                if (runMapper.insertIgnore(missed) > 0) {
                    runMapper.finishMissed(missed.getId());
                    automationMapper.markRun(a.getTenantId(), a.getId(), "MISSED", missed.getId(), slot);
                    log.info("[Automation] 错过补跑窗口 automation={} slot={}", a.getId(), slot);
                }
                continue;
            }
            DcsAutomationRun run = newRun(a, "SCHEDULE", slot, now);
            if (runMapper.insertIgnore(run) > 0) {
                automationMapper.markRun(a.getTenantId(), a.getId(), "RUNNING", run.getId(), now);
                out.add(new Claimed(a.getTenantId(), run.getId()));
            }
        }
        return out;
    }

    /** 悬挂 RUNNING 收口：进程崩溃/超时，超过 15 分钟未结束记 FAILED 并计失败次数 */
    public int sweepStale() {
        LocalDateTime now = now();
        LocalDateTime before = now.minus(AutomationSchedule.RUN_TIMEOUT);
        int swept = 0;
        for (DcsAutomationRun run : runMapper.selectStale(before)) {
            if (runMapper.failStale(run.getId(), now,
                "运行超时（超过 15 分钟未结束，可能服务重启过）") == 0) {
                continue;
            }
            swept++;
            LocalDateTime at = run.getScheduledFor() == null ? now : run.getScheduledFor();
            automationMapper.markRun(run.getTenantId(), run.getAutomationId(), "FAILED", run.getId(), at);
            int failures = failurePolicy.record(run.getTenantId(), run.getAutomationId());
            // 收口也要发通知：否则「服务重启丢的那次」用户完全无感（题5 甲承诺结果一定进通知）
            DcsAutomation automation = automationMapper.selectOne(run.getTenantId(), run.getAutomationId());
            if (automation != null && !"1".equals(automation.getDelFlag())) {
                notifier.failure(automation, run.getId(),
                    "运行超时（超过 15 分钟未结束，可能服务重启过）",
                    failures >= AutomationSchedule.FAILURE_LIMIT);
            }
        }
        return swept;
    }

    // ---------------------------------------------------------------- 内部

    private DcsAutomation ensureBuiltin(String tenantId) {
        DcsAutomation exists = automationMapper.selectBuiltin(tenantId);
        if (exists != null) {
            return exists;
        }
        DcsAutomation a = new DcsAutomation();
        a.setId(IdWorker.getId());
        a.setTenantId(tenantId);
        a.setUserId(0L);
        a.setTitle(BUILTIN_TITLE);
        a.setPrompt(BUILTIN_PROMPT);
        a.setScheduleType(AutomationSchedule.BUILTIN);
        a.setTimeZone("Asia/Shanghai");
        a.setEnabled(configService.effective(tenantId).digestEnabled());
        automationMapper.insertBuiltinIgnore(a);
        return automationMapper.selectBuiltin(tenantId);
    }

    private DcsAutomation requireVisible(String tenantId, Long userId, Long id) {
        DcsAutomation a = automationMapper.selectOne(tenantId, id);
        if (a == null) {
            throw new ServiceException("自动化不存在", 404);
        }
        boolean builtin = "1".equals(a.getBuiltin());
        if (!builtin && !userId.equals(a.getUserId())) {
            throw new ServiceException("自动化不存在或无权查看", 403);
        }
        return a;
    }

    private DcsAutomation requireOwn(String tenantId, Long userId, Long id) {
        DcsAutomation a = requireVisible(tenantId, userId, id);
        if ("1".equals(a.getBuiltin())) {
            throw new ServiceException("内置项只能开关，不能编辑或删除", 400);
        }
        return a;
    }

    private void validateBo(AutomationBo bo) {
        if (bo == null) {
            throw new ServiceException("提交内容为空", 400);
        }
        String title = bo.getTitle() == null ? "" : bo.getTitle().trim();
        if (title.isEmpty()) {
            throw new ServiceException("给这条自动化起个名字", 400);
        }
        if (title.length() > 60) {
            throw new ServiceException("名字最多 60 个字", 400);
        }
        String prompt = bo.getPrompt() == null ? "" : bo.getPrompt().trim();
        if (prompt.isEmpty()) {
            throw new ServiceException("写一句要它做什么", 400);
        }
        if (prompt.length() > 2000) {
            throw new ServiceException("任务描述最多 2000 个字", 400);
        }
        try {
            AutomationSchedule.validate(bo.getScheduleType(), bo.getScheduleTime(), bo.getWeekdays());
        } catch (IllegalArgumentException e) {
            throw new ServiceException(e.getMessage(), 400);
        }
        bo.setTitle(title);
        bo.setPrompt(prompt);
    }

    /**
     * 只读外部服务白名单清洗（纯函数）：只留小写字母/数字/下划线、逗号连接、去重、上限 10 个。
     * 未知名字/可写服务不在这里拒绝——挂载面（McpPilotService.attachEnabled）二次过滤，
     * 保证「只读」是执行期硬约束，不依赖保存时的库状态。
     */
    static String sanitizeMcpServers(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String name = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (name.matches("[a-z0-9_]{1,30}")) {
                out.add(name);
            }
            if (out.size() >= 10) {
                break;
            }
        }
        return out.isEmpty() ? null : String.join(",", out);
    }

    private String joinDays(String weekdays) {
        List<Integer> days = new ArrayList<>(AutomationSchedule.parseWeekdays(weekdays));
        if (days.isEmpty()) {
            throw new ServiceException("每周执行至少要选一天", 400);
        }
        return days.stream().map(String::valueOf).reduce((x, y) -> x + "," + y).orElse("");
    }

    private String normalizeFilter(String filter) {
        if (filter == null) {
            return "ALL";
        }
        return switch (filter.toUpperCase()) {
            case "ENABLED", "PAUSED", "FAILED" -> filter.toUpperCase();
            default -> "ALL";
        };
    }

    private DcsAutomationRun newRun(DcsAutomation a, String trigger, LocalDateTime slot, LocalDateTime startedAt) {
        DcsAutomationRun run = new DcsAutomationRun();
        run.setId(IdWorker.getId());
        run.setTenantId(a.getTenantId());
        run.setAutomationId(a.getId());
        run.setUserId(a.getUserId());
        run.setTriggerType(trigger);
        run.setScheduledFor(slot);
        run.setStatus("RUNNING");
        run.setThreadId(AutomationRunner.THREAD_PREFIX + run.getId());
        run.setStartedAt(startedAt);
        return run;
    }

    private AutomationVo toVo(DcsAutomation a, boolean canManageBuiltin) {
        AutomationVo vo = new AutomationVo();
        vo.setId(a.getId());
        vo.setTitle(a.getTitle());
        vo.setPrompt(a.getPrompt());
        vo.setScheduleType(a.getScheduleType());
        vo.setScheduleTime(a.getScheduleTime());
        vo.setWeekdays(a.getWeekdays());
        vo.setMcpServers(a.getMcpServers());
        vo.setScheduleText(AutomationSchedule.describe(a.getScheduleType(), a.getScheduleTime(), a.getWeekdays()));
        boolean builtin = "1".equals(a.getBuiltin());
        // 内置项状态以租户配置为准（管理员在管理页改过也同步）
        vo.setEnabled(builtin ? configService.effective(a.getTenantId()).digestEnabled() : a.getEnabled());
        vo.setBuiltin(a.getBuiltin());
        vo.setNextRunAt(fmt(a.getNextRunAt()));
        vo.setLastRunAt(fmt(a.getLastRunAt()));
        vo.setLastStatus(a.getLastStatus());
        // 新建时实体没带默认值，直接透传会让前端拿到 null（列表显示「失败 null 次」）
        vo.setConsecutiveFailures(a.getConsecutiveFailures() == null ? 0 : a.getConsecutiveFailures());
        vo.setRunCount(runMapper.countByAutomation(a.getTenantId(), a.getId()));
        vo.setSuccessCount(runMapper.countSuccess(a.getTenantId(), a.getId()));
        vo.setCanEdit(!builtin);
        vo.setCanToggle(!builtin || canManageBuiltin);
        return vo;
    }

    private AutomationRunVo toRunVo(DcsAutomationRun r, String title) {
        AutomationRunVo vo = new AutomationRunVo();
        vo.setId(r.getId());
        vo.setAutomationId(r.getAutomationId());
        vo.setAutomationTitle(title);
        vo.setStatus(r.getStatus());
        vo.setTriggerType(r.getTriggerType());
        vo.setScheduledFor(fmt(r.getScheduledFor()));
        vo.setStartedAt(fmt(r.getStartedAt()));
        vo.setFinishedAt(fmt(r.getFinishedAt()));
        vo.setDurationMs(r.getDurationMs());
        vo.setConversationId(r.getConversationId());
        vo.setThreadId(r.getThreadId());
        vo.setSummary(r.getResultSummary());
        vo.setError(r.getErrorMessage());
        return vo;
    }

    private String fmt(LocalDateTime t) {
        return t == null ? null : t.format(MINUTE);
    }

    private LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
