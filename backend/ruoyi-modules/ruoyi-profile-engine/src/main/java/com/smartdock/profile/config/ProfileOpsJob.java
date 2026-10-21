package com.smartdock.profile.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.profile.domain.ProfileEngineConfig;
import com.smartdock.profile.domain.ProfileReviewQueue;
import com.smartdock.profile.domain.ProfileSegment;
import com.smartdock.profile.engine.ProfileReachService;
import com.smartdock.profile.engine.ProfileReviewService;
import com.smartdock.profile.engine.ProfileSegmentService;
import com.smartdock.profile.mapper.ProfileEngineConfigMapper;
import com.smartdock.profile.mapper.ProfileReviewQueueMapper;
import com.smartdock.profile.mapper.ProfileSegmentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 画像运营定时任务（批9 Q2/Q4-A）：
 * ① 每小时 :05 分群保鲜巡检——refresh_mode=HOURLY/DAILY 且到期的动态分群自动评估
 *   （复用手动评估链路, run 记录可重跑可追溯; 完成后自动裁剪保留最近 30 次）。
 * ② 每日 09:10 复核 SLA 巡检——超时未处理的 PENDING 条目给复核负责人发站内提醒
 *   （dedupKey=review_sla:{source}:{yyyyMMdd}, 同一业务源每天最多提醒一次）。
 *
 * <p>多实例部署下靠单 JVM AtomicBoolean 防重入（评估本身幂等, 重复执行无害;
 * 分布式锁统一在批 10 落地）。cron 按容器时区（GMT+8 铁律）。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileOpsJob {

    private static final long HOURLY_MS = 55 * 60_000L;
    private static final long DAILY_MS = 20 * 3_600_000L;

    private final ProfileSegmentMapper segmentMapper;
    private final ProfileSegmentService segmentService;
    private final ProfileEngineConfigMapper configMapper;
    private final ProfileReviewQueueMapper reviewMapper;
    private final ProfileReachService reachService;
    private final com.smartdock.profile.mapper.ProfileEventMapper eventMapper;
    private final com.smartdock.analytics.engine.AnalyticsHealthService healthService;

    private final AtomicBoolean refreshing = new AtomicBoolean(false);
    private final AtomicBoolean slaScanning = new AtomicBoolean(false);
    private final AtomicBoolean healthScanning = new AtomicBoolean(false);

    @Scheduled(cron = "0 5 * * * ?")
    public void refreshSegments() {
        if (!refreshing.compareAndSet(false, true)) {
            return;
        }
        try {
            List<ProfileSegment> due = TenantHelper.ignore(() ->
                segmentMapper.selectList(new LambdaQueryWrapper<ProfileSegment>()
                    .eq(ProfileSegment::getStatus, "ENABLED")
                    .eq(ProfileSegment::getSegmentType, "DYNAMIC")
                    .ne(ProfileSegment::getRefreshMode, "OFF")));
            long now = System.currentTimeMillis();
            int done = 0;
            for (ProfileSegment segment : due) {
                try {
                    long window = "HOURLY".equalsIgnoreCase(segment.getRefreshMode()) ? HOURLY_MS : DAILY_MS;
                    Long last = segment.getLastEvalTime() == null ? null : segment.getLastEvalTime().getTime();
                    if (last != null && now - last < window) {
                        continue;
                    }
                    // 调度线程无登录态: 必须显式落到分群所属租户, 否则候选集会回落默认租户
                    TenantHelper.dynamic(segment.getTenantId(),
                        () -> segmentService.evaluate(segment.getSegmentId(), "SCHEDULE"));
                    done++;
                } catch (Exception e) {
                    log.warn("[Profile] scheduled segment refresh failed segment={} err={}",
                        segment.getSegmentId(), e.getMessage());
                }
            }
            if (done > 0) {
                log.info("[Profile] scheduled segment refresh done count={}", done);
            }
        } catch (Exception e) {
            log.error("[Profile] scheduled segment refresh failed", e);
        } finally {
            refreshing.set(false);
        }
    }

    @Scheduled(cron = "0 10 9 * * ?")
    public void scanReviewSla() {
        if (!slaScanning.compareAndSet(false, true)) {
            return;
        }
        try {
            List<ProfileEngineConfig> configs = TenantHelper.ignore(() ->
                configMapper.selectList(new LambdaQueryWrapper<ProfileEngineConfig>()
                    .eq(ProfileEngineConfig::getReviewEnabled, "1")));
            Date now = new Date();
            String day = LocalDate.now(ZoneId.of("GMT+8")).toString().replace("-", "");
            for (ProfileEngineConfig config : configs) {
                try {
                    int slaDays = config.getReviewSlaDays() == null ? 7 : config.getReviewSlaDays();
                    Date cutoff = new Date(now.getTime() - slaDays * 86_400_000L);
                    List<ProfileReviewQueue> overdue = TenantHelper.ignore(() ->
                        reviewMapper.selectList(new LambdaQueryWrapper<ProfileReviewQueue>()
                            .eq(ProfileReviewQueue::getTenantId, config.getTenantId())
                            .eq(ProfileReviewQueue::getSource, config.getSource())
                            .eq(ProfileReviewQueue::getStatus, "PENDING")
                            .lt(ProfileReviewQueue::getCreateTime, cutoff)
                            .orderByAsc(ProfileReviewQueue::getCreateTime)
                            .last("LIMIT 100")));
                    if (overdue.isEmpty()) {
                        continue;
                    }
                    // 提醒复核负责人: 当前实现发给租户管理员(userId=1); 多级升级链留批 10/12
                    Long adminUserId = 1L;
                    String title = "低分复核超时提醒";
                    String content = "业务源 " + config.getSource() + " 有 " + overdue.size()
                        + " 条复核已超过 " + slaDays + " 天未处理（SLA "
                        + slaDays + " 天），请尽快处理。最早一条入队于 "
                        + overdue.get(0).getCreateTime() + "。";
                    TenantHelper.dynamic(config.getTenantId(), () ->
                        reachService.sendToUser(adminUserId, title, content,
                            "review_sla:" + config.getSource() + ":" + day));
                    log.info("[Profile] review SLA remind source={} overdue={} slaDays={}",
                        config.getSource(), overdue.size(), slaDays);
                } catch (Exception e) {
                    // 单个租户失败不阻断后续租户当天的提醒
                    log.warn("[Profile] review SLA remind failed tenant={} source={} err={}",
                        config.getTenantId(), config.getSource(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[Profile] review SLA scan failed", e);
        } finally {
            slaScanning.set(false);
        }
    }

    /**
     * 引擎健康巡检(批10 Q5): 每小时 :15, 对近 24h 活跃源算四指标,
     * 越线给租户管理员发站内告警(dedupKey=health:{source}:{day}:{metric}, 每源每天每指标一次)。
     */
    @Scheduled(cron = "0 15 * * * ?")
    public void scanHealth() {
        if (!healthScanning.compareAndSet(false, true)) {
            return;
        }
        try {
            Date since = new Date(System.currentTimeMillis() - 24 * 3600_000L);
            List<Map<String, Object>> sources = TenantHelper.ignore(() ->
                eventMapper.selectActiveSources(since));
            String day = LocalDate.now(ZoneId.of("GMT+8")).toString().replace("-", "");
            for (Map<String, Object> pair : sources) {
                String tenantId = String.valueOf(pair.get("tenantId"));
                String source = String.valueOf(pair.get("source"));
                try {
                    Map<String, Object> health = healthService.health(tenantId, source);
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> alerts = (List<Map<String, Object>>) health.get("alerts");
                    if (alerts == null || alerts.isEmpty()) {
                        continue;
                    }
                    for (Map<String, Object> alert : alerts) {
                        String metric = String.valueOf(alert.get("metric"));
                        TenantHelper.dynamic(tenantId, () ->
                            reachService.sendToUser(1L, "引擎健康告警",
                                "业务源 " + source + " " + alert.get("message"),
                                "health:" + source + ":" + day + ":" + metric));
                    }
                    log.warn("[Profile] health alerts source={} count={}", source, alerts.size());
                } catch (Exception e) {
                    log.warn("[Profile] health scan failed tenant={} source={} err={}",
                        tenantId, source, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[Profile] health scan failed", e);
        } finally {
            healthScanning.set(false);
        }
    }
}
