package com.smartdock.analytics.engine;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.analytics.mapper.AnalyticsRebuildLogMapper;
import com.smartdock.profile.domain.ProfileModelRun;
import com.smartdock.profile.engine.ProfileRecalcBatch;
import com.smartdock.profile.mapper.ProfileEventMapper;
import com.smartdock.profile.mapper.ProfileModelRunMapper;
import com.smartdock.profile.mapper.ProfileRecalcBatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 引擎健康(批10 Q5-A): 四指标从现有数据现算, 不引入 Prometheus。
 *
 * <p>指标口径:
 * ①事件延迟 = occurred→create 落库毫秒的 P50/P95(近 24h);
 * ②重建耗时 = analytics_rebuild_log 里近 24h 的 P50/P95 + 近 6 次明细;
 * ③任务失败率 = 近 24h 模型 run + 重算批 的 FAILED 占比;
 * ④队列积压 = 上次重建完成后仍未被会话重建消化的事件数。
 * 阈值先落代码常量(拍板页给的样例值), 要可配时再进 profile_engine_config。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsHealthService {

    /** 告警阈值(拍板样例值): 任务失败率 > 5% */
    public static final double FAIL_RATE_ALERT_PCT = 5.0;
    /** 告警阈值(拍板样例值): 队列积压 > 1000 */
    public static final long BACKLOG_ALERT_LIMIT = 1000;
    /** 告警阈值: 事件延迟 P95 > 10s(采集→可见) */
    public static final long LATENCY_P95_ALERT_MS = 10_000;
    /** 告警阈值: 重建耗时 P95 > 60s */
    public static final long REBUILD_P95_ALERT_MS = 60_000;

    private static final long WINDOW_MS = 24 * 3600_000L;

    private final ProfileEventMapper eventMapper;
    private final AnalyticsRebuildLogMapper rebuildLogMapper;
    private final ProfileModelRunMapper modelRunMapper;
    private final ProfileRecalcBatchMapper recalcMapper;

    public Map<String, Object> health(String source) {
        if (StringUtils.isBlank(source) || source.length() > 32) {
            throw new ServiceException("source 不能为空且不能超过 32 字符");
        }
        return health(currentTenant(), source);
    }

    /** 巡检线程用: 显式租户(调度线程无登录上下文)。 */
    public Map<String, Object> health(String tenantId, String source) {
        Date from = new Date(System.currentTimeMillis() - WINDOW_MS);

        Map<String, Object> latency = eventMapper.selectLatencyPercentiles(tenantId, source, from);
        Map<String, Object> rebuild = rebuildLogMapper.selectDurationPercentiles(tenantId, source, from);
        List<Map<String, Object>> rebuildRecent = rebuildLogMapper.selectRecent(tenantId, source, 6);

        Long runTotal = modelRunMapper.selectCount(new LambdaQueryWrapper<ProfileModelRun>()
            .eq(ProfileModelRun::getTenantId, tenantId)
            .eq(ProfileModelRun::getSource, source)
            .ge(ProfileModelRun::getCreateTime, from));
        Long runFailed = modelRunMapper.selectCount(new LambdaQueryWrapper<ProfileModelRun>()
            .eq(ProfileModelRun::getTenantId, tenantId)
            .eq(ProfileModelRun::getSource, source)
            .eq(ProfileModelRun::getStatus, "FAILED")
            .ge(ProfileModelRun::getCreateTime, from));
        Long recalcTotal = recalcMapper.selectCount(new LambdaQueryWrapper<ProfileRecalcBatch>()
            .eq(ProfileRecalcBatch::getTenantId, tenantId)
            .eq(ProfileRecalcBatch::getSource, source)
            .ge(ProfileRecalcBatch::getCreateTime, from));
        Long recalcFailed = recalcMapper.selectCount(new LambdaQueryWrapper<ProfileRecalcBatch>()
            .eq(ProfileRecalcBatch::getTenantId, tenantId)
            .eq(ProfileRecalcBatch::getSource, source)
            .eq(ProfileRecalcBatch::getStatus, "FAILED")
            .ge(ProfileRecalcBatch::getCreateTime, from));
        long taskTotal = nz(runTotal) + nz(recalcTotal);
        long taskFailed = nz(runFailed) + nz(recalcFailed);
        BigDecimal failRatePct = taskTotal == 0 ? BigDecimal.ZERO
            : BigDecimal.valueOf(taskFailed * 100.0 / taskTotal).setScale(2, RoundingMode.HALF_UP);

        Date lastRebuildAt = rebuildLogMapper.selectLastFinishedAt(tenantId, source);
        long backlog = lastRebuildAt == null
            ? eventMapper.countCreatedAfter(tenantId, source, new Date(0))
            : eventMapper.countCreatedAfter(tenantId, source, lastRebuildAt);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("windowHours", 24);
        out.put("eventLatency", latency == null ? Map.of() : latency);
        out.put("rebuildDuration", rebuild == null ? Map.of() : rebuild);
        out.put("rebuildRecent", rebuildRecent);
        out.put("tasks", Map.of(
            "total", taskTotal,
            "failed", taskFailed,
            "failRatePct", failRatePct));
        Map<String, Object> backlogItem = new LinkedHashMap<>();
        backlogItem.put("events", backlog);
        backlogItem.put("lastRebuildAt", lastRebuildAt);
        out.put("backlog", backlogItem);
        out.put("thresholds", Map.of(
            "failRatePct", FAIL_RATE_ALERT_PCT,
            "backlog", BACKLOG_ALERT_LIMIT,
            "latencyP95Ms", LATENCY_P95_ALERT_MS,
            "rebuildP95Ms", REBUILD_P95_ALERT_MS));
        out.put("alerts", alerts(latency, rebuild, failRatePct, backlog));
        return out;
    }

    /** 越线项(供页面红标与 OpsJob 站内告警复用同一判定)。 */
    public List<Map<String, Object>> alerts(Map<String, Object> latency, Map<String, Object> rebuild,
                                            BigDecimal failRatePct, long backlog) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (failRatePct.doubleValue() > FAIL_RATE_ALERT_PCT) {
            out.add(alert("tasks", failRatePct + "%",
                "任务失败率 " + failRatePct + "% 超过阈值 " + FAIL_RATE_ALERT_PCT + "%"));
        }
        if (backlog > BACKLOG_ALERT_LIMIT) {
            out.add(alert("backlog", String.valueOf(backlog),
                "去抖队列积压 " + backlog + " 超过阈值 " + BACKLOG_ALERT_LIMIT));
        }
        double latencyP95 = toDouble(latency == null ? null : latency.get("p95"));
        if (latencyP95 > LATENCY_P95_ALERT_MS) {
            out.add(alert("latency", String.valueOf(latencyP95),
                "事件延迟 P95 " + (long) latencyP95 + "ms 超过阈值 " + LATENCY_P95_ALERT_MS + "ms"));
        }
        double rebuildP95 = toDouble(rebuild == null ? null : rebuild.get("p95"));
        if (rebuildP95 > REBUILD_P95_ALERT_MS) {
            out.add(alert("rebuild", String.valueOf(rebuildP95),
                "重建耗时 P95 " + (long) rebuildP95 + "ms 超过阈值 " + REBUILD_P95_ALERT_MS + "ms"));
        }
        return out;
    }

    private Map<String, Object> alert(String metric, String value, String message) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("metric", metric);
        item.put("value", value);
        item.put("message", message);
        return item;
    }

    private long nz(Long value) {
        return value == null ? 0L : value;
    }

    private double toDouble(Object value) {
        return value instanceof Number n ? n.doubleValue() : 0.0;
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
        return com.smartdock.profile.engine.ProfileTenantContext.require();
    }
}
