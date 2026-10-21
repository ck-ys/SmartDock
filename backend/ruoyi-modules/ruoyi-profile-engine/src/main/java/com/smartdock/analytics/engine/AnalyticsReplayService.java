package com.smartdock.analytics.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.analytics.domain.AnalyticsReplayTask;
import com.smartdock.analytics.mapper.AnalyticsReplayTaskMapper;
import com.smartdock.analytics.query.AnalyticsReplayBo;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 事件回放编排(批10 Q3-A)。
 *
 * <p>回放 = 按来源+时间窗选择范围(会话/日汇总/快照三链可勾选) →
 * 删窗口内派生数据后按 append-only 事件账本重算(天然幂等) →
 * 断点续跑(天粒度 checkpoint) → 回放前后对账数字落任务行。</p>
 *
 * <p>互斥口径(拍板 C.6): 天粒度重建走 {@link AnalyticsService#rebuildRange}(持 Q1 分布式
 * 重建锁), 快照链走 {@code ProfileScoreModelService.run}(持模型运行锁+Q2 advisory 锁),
 * 回放自身再持源级回放锁防两个回放任务同时跑同一源。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsReplayService {

    private static final Set<String> SUPPORTED_CHAINS = Set.of("SESSION", "DAILY", "SNAPSHOT");
    private static final long STALE_RUNNING_MS = 30 * 60_000L;

    private final AnalyticsReplayTaskMapper taskMapper;
    private final AnalyticsService analyticsService;
    private final AnalyticsReplayRunner runner;

    public AnalyticsReplayTask start(AnalyticsReplayBo bo) {
        if (bo == null || StringUtils.isBlank(bo.getSource())) {
            throw new ServiceException("source 不能为空");
        }
        if (bo.getSource().length() > 32) {
            throw new ServiceException("source 长度不能超过 32");
        }
        LocalDate from = parseDay(bo.getFromDate());
        LocalDate to = parseDay(bo.getToDate());
        java.util.List<String> errors = AnalyticsReplayWindows.validate(from, to,
            LocalDate.now(ZoneId.of("GMT+8")));
        if (!errors.isEmpty()) {
            throw new ServiceException(String.join("; ", errors));
        }
        long span = from.datesUntil(to.plusDays(1)).count();
        Set<String> chains = normalizeChains(bo.getChains());
        String tenantId = currentTenant();

        AnalyticsReplayTask task = new AnalyticsReplayTask();
        task.setTenantId(tenantId);
        task.setSource(bo.getSource().trim());
        task.setFromDay(toDate(from));
        task.setToDay(toDate(to));
        task.setChains(String.join(",", chains));
        task.setStatus("RUNNING");
        task.setCurrentDay(toDate(from));
        task.setDaysTotal((int) span);
        task.setDaysDone(0);
        task.setStatsBefore(JsonUtils.toJsonString(windowStats(tenantId, bo.getSource().trim(), from, to)));
        try {
            task.setCreateBy(LoginHelper.getUserId());
        } catch (Exception ignored) {
            // 系统线程无登录态
        }
        taskMapper.insert(task);
        runner.execute(task.getReplayId());
        return task;
    }

    public AnalyticsReplayTask resume(Long replayId) {
        AnalyticsReplayTask task = requireTask(replayId);
        if ("DONE".equals(task.getStatus())) {
            return task;
        }
        if ("RUNNING".equals(task.getStatus())) {
            // 僵尸 RUNNING(进程重启遗留, 30 分钟无进度刷新)可拉起; 活任务拒绝双跑
            Date touched = taskMapper.selectUpdateTime(task.getTenantId(), replayId);
            if (touched != null && System.currentTimeMillis() - touched.getTime() < STALE_RUNNING_MS) {
                throw new ServiceException("回放任务 " + replayId + " 正在执行中, 拒绝并发续跑");
            }
            task.setStatus("PAUSED");
        }
        task.setStatus("RUNNING");
        task.setUpdateTime(null);
        taskMapper.updateById(task);
        runner.execute(replayId);
        return task;
    }

    public AnalyticsReplayTask pause(Long replayId) {
        AnalyticsReplayTask task = requireTask(replayId);
        if ("PAUSED".equals(task.getStatus())) {
            return task;
        }
        if (taskMapper.markPaused(task.getTenantId(), replayId) == 0) {
            throw new ServiceException("只有执行中的回放任务可以暂停(当前状态=" + task.getStatus() + ")");
        }
        return taskMapper.selectById(replayId);
    }

    public List<AnalyticsReplayTask> list(String source) {
        if (StringUtils.isNotBlank(source) && source.length() > 32) {
            throw new ServiceException("source 长度不能超过 32");
        }
        return taskMapper.selectRecent(currentTenant(), StringUtils.isBlank(source) ? null : source.trim());
    }

    public AnalyticsReplayTask get(Long replayId) {
        return requireTask(replayId);
    }

    /** 对账口径: 窗口内会话数/浏览量/跳出数 + 日汇总行数——回放前后同口径对比。 */
    public Map<String, Object> windowStats(String tenantId, String source, LocalDate from, LocalDate to) {
        return analyticsService.replayWindowStats(tenantId, source, toDate(from), toDate(to));
    }

    private AnalyticsReplayTask requireTask(Long replayId) {
        if (replayId == null) {
            throw new ServiceException("replayId 不能为空");
        }
        AnalyticsReplayTask task = taskMapper.selectById(replayId);
        if (task == null) {
            throw new ServiceException("回放任务不存在: " + replayId);
        }
        return task;
    }

    private Set<String> normalizeChains(List<String> chains) {
        if (chains == null || chains.isEmpty()) {
            throw new ServiceException("至少勾选一条回放链(SESSION/DAILY/SNAPSHOT)");
        }
        Set<String> out = new LinkedHashSet<>();
        for (String chain : chains) {
            if (chain == null) {
                continue;
            }
            String normalized = chain.trim().toUpperCase();
            if (!SUPPORTED_CHAINS.contains(normalized)) {
                throw new ServiceException("不支持的回放链: " + chain + "(只支持 SESSION/DAILY/SNAPSHOT)");
            }
            out.add(normalized);
        }
        if (out.isEmpty()) {
            throw new ServiceException("至少勾选一条回放链(SESSION/DAILY/SNAPSHOT)");
        }
        return out;
    }

    private LocalDate parseDay(String raw) {
        if (StringUtils.isBlank(raw)) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private Date toDate(LocalDate day) {
        return Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private String currentTenant() {
        try {
            String t = TenantHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 无租户上下文
        }
        try {
            String t = LoginHelper.getTenantId();
            if (StringUtils.isNotBlank(t)) {
                return t;
            }
        } catch (Exception ignored) {
            // 非登录上下文
        }
        return "000000";
    }
}
