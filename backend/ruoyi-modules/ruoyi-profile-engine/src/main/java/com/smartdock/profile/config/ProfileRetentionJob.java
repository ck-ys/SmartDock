package com.smartdock.profile.config;

import com.smartdock.analytics.mapper.AnalyticsCollectLogMapper;
import com.smartdock.profile.mapper.ProfileEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * 事件保留期清理: 每日 03:30(GMT+8) 删过期事件, 单轮 1 万行分批。
 *
 * <p>快照是账本永不清理; 事件是原料可老化。多实例部署下靠单 JVM 内 AtomicBoolean
 * 防重入(跨实例重复删除幂等无害, 批1 不引入分布式锁)。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileRetentionJob {

    private static final int BATCH_LIMIT = 10_000;

    private final ProfileEventMapper eventMapper;
    private final AnalyticsCollectLogMapper collectLogMapper;

    /** 保留天数; 0 = 关闭清理 */
    @Value("${profile.event.retention-days:730}")
    private int retentionDays;

    /** 采集丢弃留痕保留天数(批7, 只做质量排查用, 短于事件账本) */
    @Value("${analytics.collect.log-retention-days:90}")
    private int collectLogRetentionDays;

    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(false);

    /**
     * cron 按容器时区(GMT+8 铁律)。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanup() {
        if (retentionDays <= 0) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Date before = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays));
            long total = TenantHelper.ignore(() -> {
                long deleted = 0;
                int rows;
                int rounds = 0;
                do {
                    rows = eventMapper.deleteBefore(before, BATCH_LIMIT);
                    deleted += rows;
                } while (rows >= BATCH_LIMIT && ++rounds < 100); // dspro P2-4: 单次封顶 100 轮(百万行), 防失控
                return deleted;
            });
            if (total > 0) {
                log.info("[Profile] retention cleanup deleted {} events older than {} days", total, retentionDays);
            }
        } catch (Exception e) {
            log.error("[Profile] retention cleanup failed", e);
        } finally {
            running.set(false);
        }
    }

    /**
     * 采集丢弃留痕清理(批7): 每日 03:40, 默认 90 天。
     * 留痕只是质量报表原料, 爬虫灌水场景下比事件账本更易膨胀, 保留期刻意更短。
     */
    @Scheduled(cron = "0 40 3 * * ?")
    public void cleanupCollectLogs() {
        if (collectLogRetentionDays <= 0) {
            return;
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Date before = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(collectLogRetentionDays));
            TenantHelper.ignore(() -> {
                int rows;
                int rounds = 0;
                do {
                    rows = collectLogMapper.deleteBefore(before, BATCH_LIMIT);
                } while (rows >= BATCH_LIMIT && ++rounds < 100);
            });
        } catch (Exception e) {
            log.error("[Analytics] collect log cleanup failed", e);
        } finally {
            running.set(false);
        }
    }
}
