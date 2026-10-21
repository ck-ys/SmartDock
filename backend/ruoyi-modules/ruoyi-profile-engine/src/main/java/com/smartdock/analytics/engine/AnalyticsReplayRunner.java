package com.smartdock.analytics.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.analytics.domain.AnalyticsReplayTask;
import com.smartdock.analytics.mapper.AnalyticsReplayTaskMapper;
import com.smartdock.profile.domain.ProfileModelRun;
import com.smartdock.profile.domain.ProfileScoreModel;
import com.smartdock.profile.engine.ProfileLocks;
import com.smartdock.profile.engine.ProfileScoreModelService;
import com.smartdock.profile.mapper.ProfileScoreModelMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 回放任务执行器(批10 Q3): @Async 逐日推进, 天粒度断点。
 *
 * <p>逐日升序是口径要求: 跨午夜边界会话会在后一天的重建里被完整重写,
 * 降序或乱序会留下残缺边界行。暂停=每天开始前重读任务状态, 非 RUNNING 即退出
 * (断点已随 current_day 持久化)。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsReplayRunner {

    private final AnalyticsReplayTaskMapper taskMapper;
    private final AnalyticsService analyticsService;
    private final ProfileScoreModelMapper modelMapper;
    private final ProfileScoreModelService modelService;
    private final org.redisson.api.RedissonClient redisson;

    @Async
    public void execute(Long replayId) {
        AnalyticsReplayTask task = taskMapper.selectById(replayId);
        if (task == null || !"RUNNING".equals(task.getStatus())) {
            return;
        }
        TenantHelper.dynamic(task.getTenantId(), () -> {
            runTask(task);
            return null;
        });
    }

    private void runTask(AnalyticsReplayTask task) {
        Long replayId = task.getReplayId();
        String source = task.getSource();
        Set<String> chains = new LinkedHashSet<>(Arrays.asList(task.getChains().split(",")));
        // 同一业务源同时只允许一个回放任务(防互相删插); watchdog 续租, 崩溃后自动过期。
        // 阻塞等待而非立即改 PAUSED: create/resume 双派发竞态下第二路稍等即可接续或确认退出,
        // 直接改共享行会把别人正在跑的任务错误置为暂停并留下脏 errorMsg。
        org.redisson.api.RLock lock = redisson.getLock(ProfileLocks.replay(task.getTenantId(), source));
        boolean acquired;
        try {
            acquired = lock.tryLock(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Analytics] replay {} interrupted while waiting source lock", replayId);
            return;
        }
        if (!acquired) {
            log.warn("[Analytics] replay {} gave up: source lock held over 10min", replayId);
            return;
        }
        try {
            // 拿到锁后以库里状态为准: 非 RUNNING 说明另一路已接管或已被暂停/取消, 静默让位
            AnalyticsReplayTask owner = taskMapper.selectById(replayId);
            if (owner == null || !"RUNNING".equals(owner.getStatus())) {
                log.info("[Analytics] replay {} yielded to status={}", replayId,
                    owner == null ? "GONE" : owner.getStatus());
                return;
            }
            LocalDate to = toLocalDate(task.getToDay());
            LocalDate day = toLocalDate(task.getCurrentDay());
            boolean rebuildChain = chains.contains("SESSION") || chains.contains("DAILY");
            while (!day.isAfter(to)) {
                AnalyticsReplayTask fresh = taskMapper.selectById(replayId);
                if (fresh == null || !"RUNNING".equals(fresh.getStatus())) {
                    log.info("[Analytics] replay {} stopped by status={}", replayId,
                        fresh == null ? "GONE" : fresh.getStatus());
                    return;
                }
                if (rebuildChain) {
                    // SESSION 勾了就整链重建; 只勾 DAILY 时信任现存会话只重算日汇总
                    analyticsService.rebuildRange(task.getTenantId(), source,
                        toDate(day), toDate(day.plusDays(1)), chains.contains("SESSION"), "REPLAY");
                }
                day = day.plusDays(1);
                task.setCurrentDay(toDate(day));
                task.setDaysDone((task.getDaysDone() == null ? 0 : task.getDaysDone()) + 1);
                if (taskMapper.updateProgressIfRunning(task.getTenantId(), replayId,
                        toDate(day), task.getDaysDone()) == 0) {
                    log.info("[Analytics] replay {} paused externally during day loop", replayId);
                    return;
                }
            }
            if (chains.contains("SNAPSHOT")) {
                replaySnapshots(task);
                if ("FAILED".equals(task.getStatus())) {
                    return;
                }
            }
            task.setStatsAfter(JsonUtils.toJsonString(analyticsService.replayWindowStats(
                task.getTenantId(), source, task.getFromDay(), task.getToDay())));
            task.setStatus("DONE");
            task.setErrorMsg(null);
        } catch (Exception e) {
            log.error("[Analytics] replay {} failed", replayId, e);
            task.setStatus("FAILED");
            task.setErrorMsg(StringUtils.substring(String.valueOf(e.getMessage()), 0, 1000));
        } finally {
            try {
                // 暂停被外部落库时本线程的内存副本已过期, 不能再覆盖回去
                AnalyticsReplayTask fresh = taskMapper.selectById(replayId);
                if (fresh != null && "RUNNING".equals(fresh.getStatus())) {
                    taskMapper.updateById(task);
                }
            } catch (Exception persistError) {
                log.error("[Analytics] replay {} status persist failed", replayId, persistError);
            }
            try {
                if (lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            } catch (Exception e) {
                log.warn("[Analytics] replay lock unlock failed: {}", e.getMessage());
            }
        }
    }

    /** 快照链回放: 该源全部已发布启用模型逐个全量重算(自带模型锁+advisory 锁)。 */
    private void replaySnapshots(AnalyticsReplayTask task) {
        String source = task.getSource();
        java.util.List<ProfileScoreModel> models = modelMapper.selectList(new LambdaQueryWrapper<ProfileScoreModel>()
            .eq(ProfileScoreModel::getTenantId, task.getTenantId())
            .eq(ProfileScoreModel::getSource, source)
            .eq(ProfileScoreModel::getStatus, "PUBLISHED")
            .eq(ProfileScoreModel::getEnabled, "1"));
        for (ProfileScoreModel model : models) {
            try {
                ProfileModelRun run = modelService.run(model.getModelId(), "REPLAY");
                if (!"DONE".equals(run.getStatus())) {
                    task.setStatus("FAILED");
                    taskMapper.markFailedIfRunning(task.getTenantId(), task.getReplayId(), StringUtils.substring(
                        "模型 " + model.getModelCode() + " 重算失败: " + run.getErrorMsg(), 0, 1000));
                    return;
                }
            } catch (Exception e) {
                task.setStatus("FAILED");
                taskMapper.markFailedIfRunning(task.getTenantId(), task.getReplayId(), StringUtils.substring(
                    "模型 " + model.getModelCode() + " 重算异常: " + e.getMessage(), 0, 1000));
                return;
            }
        }
    }

    private LocalDate toLocalDate(Date day) {
        return day.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }

    private Date toDate(LocalDate day) {
        return Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }
}
