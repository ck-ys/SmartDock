package org.dromara.assistant.job;

import com.baomidou.lock.annotation.Lock4j;
import com.smartdock.dcs.domain.DcsNotice;
import com.smartdock.dcs.domain.DcsSubmission;
import com.smartdock.dcs.domain.DcsTask;
import com.smartdock.dcs.mapper.DcsTaskMapper;
import com.smartdock.dcs.mapper.DcsUserLookupMapper;
import com.smartdock.dcs.service.DcsNoticeService;
import com.smartdock.dcs.service.DcsSubmissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.domain.DcsAssistantConfig;
import org.dromara.assistant.service.AssistantConfigService;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「每日摘要」定时任务（「问数据」批·题6②）：兑现助手管理页开关的承诺——
 * 每天 08:00 给「有进行中任务的发布人」推一条站内未交摘要（谁还没交、离截止还有多久），
 * 安静日（没有未交）不打扰。
 *
 * <p><b>为什么此前是骗人开关</b>: 管理页开关与文案 2026-10 已上线, 但 08:00 扫描任务从未实现
 * （{@code AssistantConfigService.digestEnabledTenants()} 注释写着「08:00 job 扫描用」却零调用方）。
 * 本类即那个 job。</p>
 *
 * <p><b>多实例</b>: {@code @Lock4j} 全局锁（与 AssistantRetentionJob 同款）——多台助手实例只发一次;
 * 另有 (租户, 发布人, 日期) 幂等键双保险, 锁漂移重跑也不会重复落通知。</p>
 *
 * <p><b>口径</b>: 进行中 = 任务状态 COLLECTING; 「没交」= 目标成员没有 PENDING_AUDIT / APPROVED
 * 状态的提交（被退回待重提也算没交）; 一个发布人一天一条, 多任务合并; 全员都交了=安静日不发。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssistantDailyDigestJob {

    /** 单租户单日最多处理任务数（防大租户拖垮 08:00 批; 按截止时间近的优先） */
    static final int MAX_TASKS_PER_TENANT = 100;
    /** 摘要里最多列出的未交姓名数（超出写「等 N 人」） */
    static final int MAX_NAMES = 5;

    private final AssistantConfigService configService;
    private final DcsTaskMapper taskMapper;
    private final DcsSubmissionService submissionService;
    private final DcsNoticeService noticeService;
    private final DcsUserLookupMapper userLookupMapper;

    @Lock4j(keys = {"'assistant-daily-digest'"})
    @Scheduled(cron = "0 0 8 * * ?")
    public void push() {
        List<DcsAssistantConfig> tenants = configService.digestEnabledTenants();
        if (tenants.isEmpty()) {
            return;
        }
        int sent = 0;
        for (DcsAssistantConfig cfg : tenants) {
            String tenantId = cfg.getTenantId();
            try {
                sent += TenantHelper.dynamic(tenantId, () -> pushTenant(tenantId));
            } catch (Exception e) {
                log.warn("[DailyDigest] tenant={} failed: {}", tenantId, e.getMessage());
            }
        }
        if (sent > 0) {
            log.info("[DailyDigest] done, tenants={} notices={}", tenants.size(), sent);
        }
    }

    /** 单租户: 按 COLLECTING 任务聚合出每个发布人的未交摘要; 返回发出的通知数 */
    int pushTenant(String tenantId) {
        List<DcsTask> tasks = taskMapper.selectList(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DcsTask>()
                .eq(DcsTask::getStatus, DcsTask.STATUS_COLLECTING)
                .orderByAsc(DcsTask::getDeadline)
                .last("LIMIT " + (MAX_TASKS_PER_TENANT + 1)));
        // 审查席 P2：多取 1 条判真截断——超 100 时日志明说，不静默吞掉第 101 个以后的未交
        boolean overCap = tasks.size() > MAX_TASKS_PER_TENANT;
        if (overCap) {
            tasks = new ArrayList<>(tasks.subList(0, MAX_TASKS_PER_TENANT));
            log.warn("[DailyDigest] tenant={} COLLECTING tasks exceed {} (capped, closest deadlines first)", tenantId, MAX_TASKS_PER_TENANT);
        }
        if (tasks.isEmpty()) {
            return 0;
        }
        // 发布人 → (任务, 未交成员) 列表
        Map<Long, List<Map<String, Object>>> byPublisher = new LinkedHashMap<>();
        for (DcsTask task : tasks) {
            List<Long> unsubmitted = unsubmittedOf(task);
            if (unsubmitted.isEmpty()) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("task", task);
            row.put("unsubmitted", unsubmitted);
            byPublisher.computeIfAbsent(task.getPublisherUserId(), k -> new ArrayList<>()).add(row);
        }
        if (byPublisher.isEmpty()) {
            return 0;
        }
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        int sent = 0;
        for (Map.Entry<Long, List<Map<String, Object>>> entry : byPublisher.entrySet()) {
            Long publisher = entry.getKey();
            try {
                if (notifyPublisher(tenantId, publisher, entry.getValue(), today)) {
                    sent++;
                }
            } catch (Exception e) {
                log.warn("[DailyDigest] tenant={} publisher={} notify failed: {}", tenantId, publisher, e.getMessage());
            }
        }
        return sent;
    }

    private boolean notifyPublisher(String tenantId, Long publisher, List<Map<String, Object>> rows, LocalDate today) {
        // 审查席 P2：正文按字数预算封顶（DB 层 1000 字硬切会切出半句且无提示），
        // 超预算时收口为「…等 N 个任务」；标题计数仍是真实任务数
        final int contentBudget = 900;
        List<String> lines = new ArrayList<>();
        int used = 0;
        int dropped = 0;
        for (Map<String, Object> row : rows) {
            DcsTask task = (DcsTask) row.get("task");
            @SuppressWarnings("unchecked")
            List<Long> unsubmitted = (List<Long>) row.get("unsubmitted");
            String line = describeTask(task.getTitle(), task.getDeadline(), today,
                unsubmitted.size(), namesOf(unsubmitted));
            if (used + line.length() > contentBudget && !lines.isEmpty()) {
                dropped = rows.size() - lines.size();
                break;
            }
            lines.add(line);
            used += line.length() + 1;
        }
        if (dropped > 0) {
            lines.add("…等 " + rows.size() + " 个任务（摘要过长，其余从略，完整清单到任务页看）");
        }
        String title = "今日未交摘要（" + rows.size() + " 个任务）";
        String content = String.join("\n", lines);
        return noticeService.notifyIdem(publisher, DcsNotice.TYPE_TASK_DIGEST, title, content, null,
            dedupKey(tenantId, publisher, today));
    }

    /** 任务没有「已交」提交的目标成员（被退回待重提=没交） */
    private List<Long> unsubmittedOf(DcsTask task) {
        List<Long> out = new ArrayList<>();
        for (Long uid : task.assigneeList()) {
            var latest = submissionService.latestOf(task.getId(), uid);
            if (!countsAsSubmitted(latest == null ? null : latest.getStatus())) {
                out.add(uid);
            }
        }
        return out;
    }

    private List<String> namesOf(List<Long> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Map<String, Object> brief : userLookupMapper.selectBriefsByIds(userIds)) {
            Object nick = brief.get("nick_name");
            names.add(nick != null ? String.valueOf(nick) : String.valueOf(brief.get("user_name")));
        }
        return names;
    }

    // ---------------------------------------------------------------- 纯函数（单测锚点）

    /** 已交口径: 提交在途待审或已通过都算交了; 无提交/被退回=没交（状态串用 DcsSubmission 常量，防改名静默漂移） */
    static boolean countsAsSubmitted(String latestStatus) {
        return DcsSubmission.STATUS_PENDING_AUDIT.equals(latestStatus) || DcsSubmission.STATUS_APPROVED.equals(latestStatus);
    }

    /** 幂等键: 同一租户同一发布人同一天只落一条（防锁漂移重跑重复推送） */
    static String dedupKey(String tenantId, Long userId, LocalDate day) {
        return "digest:" + tenantId + ":" + userId + ":" + day.toString().replace("-", "");
    }

    /** 单任务一行摘要: 「任务名」还有 N 人未交（a、b、…等 N 人）· 截止倒计时。
     *  「等 N 人」的 N 用未交总数（审查席 P2：查不到名字时 names 会短于未交数，用 names.size() 会自相矛盾） */
    static String describeTask(String title, Date deadline, LocalDate today, int unsubmittedCount, List<String> names) {
        StringBuilder sb = new StringBuilder("「").append(title).append("」还有 ").append(unsubmittedCount).append(" 人未交");
        if (names != null && !names.isEmpty()) {
            sb.append("（");
            int n = Math.min(names.size(), MAX_NAMES);
            sb.append(String.join("、", names.subList(0, n)));
            if (unsubmittedCount > n) {
                sb.append(" 等 ").append(unsubmittedCount).append(" 人");
            }
            sb.append("）");
        }
        sb.append("，").append(deadlineText(deadline, today));
        return sb.toString();
    }

    /** 截止倒计时人话: 无截止/今天截止/还有 N 天/已过期 N 天 */
    static String deadlineText(Date deadline, LocalDate today) {
        if (deadline == null) {
            return "未设截止时间";
        }
        LocalDate due = deadline.toInstant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();
        long diff = java.time.temporal.ChronoUnit.DAYS.between(today, due);
        if (diff > 0) {
            return "离截止还有 " + diff + " 天";
        }
        if (diff == 0) {
            return "今天截止";
        }
        return "已过期 " + (-diff) + " 天";
    }
}
