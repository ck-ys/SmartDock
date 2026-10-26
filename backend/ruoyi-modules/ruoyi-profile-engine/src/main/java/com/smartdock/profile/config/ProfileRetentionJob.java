package com.smartdock.profile.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.analytics.mapper.AnalyticsRebuildLogMapper;
import com.smartdock.profile.domain.ProfileEngineConfig;
import com.smartdock.profile.mapper.ProfileEngineConfigMapper;
import com.smartdock.profile.mapper.ProfileEventMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 事件保留期清理(批10 Q4 重写): 每源可配保留天数 + 过期先迁归档表再删。
 *
 * <p>三套保留期口径统一登记(拍板 C.9):
 * 事件=本任务(全局默认 730 天, 每源可覆盖); 分群成员明细=evaluate 内裁剪保留 30 次 run;
 * 模型快照=账本永不清理。重建留痕 analytics_rebuild_log 随本任务清 30 天。</p>
 *
 * <p>多实例防重入: 单 JVM AtomicBoolean 挡住本实例, 跨实例重复执行幂等无害
 * (迁移/删除按行原子, 双跑只会分到不相交的行); 分布式互斥属批10 Q1 任务锁域, 这里按
 * 拍板 A 的"删除幂等无害"口径不做跨实例锁。</p>
 *
 * <p>PG 分区明示不上(拍板 Q8-A/R3): 触发条件=事件总量过亿或单表过千万时再立专项批。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileRetentionJob {

    private static final int BATCH_LIMIT = 10_000;
    private static final int MAX_ROUNDS = 100;
    private static final int REBUILD_LOG_RETENTION_DAYS = 30;

    private final ProfileEventMapper eventMapper;
    private final AnalyticsRebuildLogMapper rebuildLogMapper;
    private final ProfileEngineConfigMapper configMapper;
    private final com.smartdock.analytics.mapper.AnalyticsSessionMapper sessionMapper;
    private final com.smartdock.analytics.mapper.AnalyticsDailyMapper dailyMapper;

    /** 保留天数; 0 = 关闭清理 */
    @Value("${profile.event.retention-days:730}")
    private int retentionDays;

    private final java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean(false);
    /** 保留期清理的互斥标志：上一轮未结束则跳过本轮，避免重入叠加 */

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
            Date now = new Date();
            // 候选(租户,源): 有事件早于该源生效截止日(SQL 内联合 profile_engine_config 取自定义保留期)
            List<Map<String, Object>> pairs = TenantHelper.ignore(() ->
                eventMapper.selectSourcesWithOldEvents(retentionDays));
            long totalDeleted = 0;
            for (Map<String, Object> pair : pairs) {
                try {
                    totalDeleted += cleanSource(String.valueOf(pair.get("tenantId")),
                        String.valueOf(pair.get("source")), now);
                } catch (Exception e) {
                    log.warn("[Profile] retention cleanup failed tenant={} source={} err={}",
                        pair.get("tenantId"), pair.get("source"), e.getMessage());
                }
            }
            TenantHelper.ignore(() -> rebuildLogMapper.deleteBefore(
                new Date(now.getTime() - TimeUnit.DAYS.toMillis(REBUILD_LOG_RETENTION_DAYS))));
            if (totalDeleted > 0) {
                log.info("[Profile] retention cleanup archived/deleted {} events across {} sources",
                    totalDeleted, pairs.size());
            }
        } catch (Exception e) {
            log.error("[Profile] retention cleanup failed", e);
        } finally {
            running.set(false);
        }
    }

    private long cleanSource(String tenantId, String source, Date now) {
        ProfileEngineConfig config = TenantHelper.dynamic(tenantId, () ->
            configMapper.selectList(new LambdaQueryWrapper<ProfileEngineConfig>()
                .eq(ProfileEngineConfig::getTenantId, tenantId)
                .eq(ProfileEngineConfig::getSource, source)
                .last("LIMIT 1"))
                .stream().findFirst().orElse(null));
        int days = config != null && config.getEventRetentionDays() != null
            ? config.getEventRetentionDays() : retentionDays;
        boolean archive = config == null || !"0".equals(config.getEventArchive());
        Date cutoff = new Date(now.getTime() - TimeUnit.DAYS.toMillis(days));
        long deleted = 0;
        int rounds = 0;
        int rows;
        do {
            rows = TenantHelper.dynamic(tenantId, () -> archive
                ? eventMapper.archiveExpiredBatch(tenantId, source, cutoff, BATCH_LIMIT)
                : eventMapper.deleteExpiredBatch(tenantId, source, cutoff, BATCH_LIMIT));
            deleted += rows;
        } while (rows >= BATCH_LIMIT && ++rounds < MAX_ROUNDS); // 单源封顶 100 轮防失控
        // R2-5 项11: 先出账事件、再清派生表窗口(顺序不可倒)——总览/趋势(读 session/daily)
        // 与漏斗/路径(直查事件)两套数字从此不分叉; 每次进入本源清理都补一刀, 上轮中断也能自愈
        int sessions = TenantHelper.dynamic(tenantId, () ->
            sessionMapper.deleteBeforeWatermark(tenantId, source, cutoff));
        int dailyRows = TenantHelper.dynamic(tenantId, () ->
            dailyMapper.deleteBeforeWatermark(tenantId, source, cutoff));
        if (sessions > 0 || dailyRows > 0) {
            log.info("[Profile] retention derived cleanup tenant={} source={} sessions={} dailyRows={} watermark={}",
                tenantId, source, sessions, dailyRows, cutoff);
        }
        return deleted;
    }

}
