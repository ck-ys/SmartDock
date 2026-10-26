package com.smartdock.analytics.engine;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.analytics.domain.AnalyticsDaily;
import com.smartdock.analytics.domain.AnalyticsFunnel;
import com.smartdock.analytics.domain.AnalyticsSession;
import com.smartdock.analytics.mapper.AnalyticsDailyMapper;
import com.smartdock.analytics.mapper.AnalyticsEventMapper;
import com.smartdock.analytics.mapper.AnalyticsFunnelMapper;
import com.smartdock.analytics.mapper.AnalyticsSessionMapper;
import com.smartdock.analytics.query.AnalyticsBreakdownRow;
import com.smartdock.analytics.query.AnalyticsCustomBo;
import com.smartdock.analytics.query.AnalyticsEventRow;
import com.smartdock.analytics.query.AnalyticsFunnelBo;
import com.smartdock.analytics.query.AnalyticsImportBo;
import com.smartdock.analytics.query.AnalyticsSessionQuery;
import com.smartdock.profile.ProfileEventPublisher;
import com.smartdock.profile.domain.ProfileEngineConfig;
import com.smartdock.profile.domain.ProfileExportLog;
import com.smartdock.profile.engine.ProfileScaleGuard;
import com.smartdock.profile.event.ProfileEventBo;
import com.smartdock.profile.mapper.ProfileEngineConfigMapper;
import com.smartdock.profile.mapper.ProfileExportLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.json.utils.JsonUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 分析中心：会话重建、日汇总、趋势/下钻/留存/漏斗/自定义分析与 CSV 导入。
 *
 * <p>口径：PV=页面浏览事件数；UV=去重访问者；访问次数=去重会话；
 * 跳出=只浏览了一个页面的会话；新访客=该业务源首次出现。</p>
 *
 * @author smartdock
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private static final Set<String> SESSION_DIMENSIONS = Set.of(
        "channel", "device", "browser", "os", "geo", "entry", "exit", "campaign");
    private static final Set<String> EVENT_DIMENSIONS = Set.of("page", "search", "event");
    private static final Set<String> METRICS = Set.of("pv", "uv", "visits", "events");
    private static final int MAX_CSV_ROWS = 5000;

    private final AnalyticsEventMapper eventMapper;
    private final AnalyticsSessionMapper sessionMapper;
    private final AnalyticsDailyMapper dailyMapper;
    private final AnalyticsFunnelMapper funnelMapper;
    private final com.smartdock.analytics.mapper.AnalyticsRebuildLogMapper rebuildLogMapper;
    private final com.smartdock.analytics.mapper.AnalyticsVisitorFirstSeenMapper firstSeenMapper;
    private final com.smartdock.profile.engine.ProfilePiiService piiService;
    private final org.redisson.api.RedissonClient redisson;
    private final AnalyticsSessionizer sessionizer;
    /** ObjectProvider 打破 AnalyticsService → 事件发布器 → 自动汇总触发器 的构造环 */
    private final ObjectProvider<ProfileEventPublisher> eventPublisherProvider;
    /** 批4A 规模护栏: 大窗口(漏斗/路径/流失)载入前的前置信号 */
    private final ProfileScaleGuard scaleGuard;
    /** R2-5 项11: 保留期水位拦截要读每源 eventRetentionDays 覆盖值 */
    private final ProfileEngineConfigMapper engineConfigMapper;
    /** R2-5 项10: 导出审计(复用画像侧 profile_export_log) */
    private final ProfileExportLogMapper exportLogMapper;

    /** 事件保留期全局默认(R2-5 项11 水位口径, 与 ProfileRetentionJob 同键; 0=不清理无水位) */
    @org.springframework.beans.factory.annotation.Value("${profile.event.retention-days:730}")
    private int defaultEventRetentionDays;

    /** 报表级 CSV 导出行数上限(学 Mixpanel 限流姿态; 原始事件全量导出走异步任务候补) */
    private static final int EXPORT_MAX_ROWS = 50_000;

    /**
     * 事务模板: 锁必须包住事务的提交边界。@Transactional 代理是"先开事务再进方法体拿锁",
     * unlock 早于 commit, 两个重建仍可能在提交阶段撞唯一键; 显式模板让 lock() 在 BEGIN 之前。
     */
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;

    /**
     * 同一 (租户,业务源) 的重建互斥: 手动重建与自动汇总并发时会互相删插对方的会话。
     * 批10 Q1: ReentrantLock(单 JVM) 换 Redisson 分布式锁——两实例部署时
     * 同一源同一时间只有一个重建者(单实例行为不变)。显式租期不用 watchdog,
     * 持锁进程崩溃后锁最迟在租期内自动过期, 另一实例可接管。
     */

    // ---------------------------------------------------------------- 重建 / 汇总

    public Map<String, Object> rebuild(String source, int days) {
        requireSource(source);
        int clampedDays = Math.min(Math.max(days, 1), 365);
        String tenantId = currentTenant();
        Map<String, Object> out = rebuildRange(tenantId, source,
            startOfDaysAgo(clampedDays), startOfTomorrow(), true, "MANUAL");
        out.put("source", source);
        out.put("days", clampedDays);
        return out;
    }

    /**
     * 窗口重建(批10 Q3): 回放/身份拆分与手动重建共用的执行入口——持分布式锁,
     * 会话(可选)+日汇总一个事务, 完成后留一行耗时(健康页原料, 失败不阻断)。
     */
    public Map<String, Object> rebuildRange(String tenantId, String source, Date from, Date to,
                                            boolean rebuildSessions, String kind) {
        // R2-5 项11: 窗口起点早于该源归档水位直接拒绝——按现存事件 deleteRange 后重算
        // 会把已归档窗口的会话/日汇总清零且无法从归档恢复(不可逆抹零, 手滑 rebuild 也进不来)
        guardRebuildWatermark(tenantId, source, from);
        org.redisson.api.RLock lock = redisson.getLock(
            com.smartdock.profile.engine.ProfileLocks.rebuild(tenantId, source));
        lock.lock(com.smartdock.profile.engine.ProfileLocks.REBUILD_LEASE_MINUTES, TimeUnit.MINUTES);
        try {
            long startedAt = System.currentTimeMillis();
            int[] sessionsHolder = new int[1];
            int[] dailyHolder = new int[1];
            txTemplate.execute(status -> {
                if (rebuildSessions) {
                    sessionsHolder[0] = sessionizer.rebuild(tenantId, source, from, to);
                }
                dailyHolder[0] = rollup(tenantId, source, from, to);
                return null;
            });
            int sessions = sessionsHolder[0];
            int dailyRows = dailyHolder[0];
            writeRebuildLog(tenantId, source, kind, System.currentTimeMillis() - startedAt, sessions, null);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("from", from);
            out.put("to", to);
            out.put("sessions", sessions);
            out.put("dailyRows", dailyRows);
            return out;
        } finally {
            unlockQuietly(lock);
        }
    }

    /** 回放对账口径: 窗口内会话数/浏览量/跳出数 + 日汇总行数(fromDay/toDay 均为自然日零点)。 */
    public Map<String, Object> replayWindowStats(String tenantId, String source, Date fromDay, Date toDay) {
        Date from = fromDay;
        Date to = new Date(toDay.getTime() + 24L * 3600_000L);
        Map<String, Object> sessions = sessionMapper.selectReplayStats(tenantId, source, from, to);
        Long dailyRows = dailyMapper.selectCount(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AnalyticsDaily>()
                .eq(AnalyticsDaily::getTenantId, tenantId)
                .eq(AnalyticsDaily::getSource, source)
                .ge(AnalyticsDaily::getStatDate, new java.sql.Date(fromDay.getTime()))
                .le(AnalyticsDaily::getStatDate, new java.sql.Date(toDay.getTime())));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessions", sessions == null ? 0L : sessions.get("sessions"));
        out.put("pageViews", sessions == null ? 0L : sessions.get("pageViews"));
        out.put("bounces", sessions == null ? 0L : sessions.get("bounces"));
        out.put("dailyRows", dailyRows == null ? 0L : dailyRows);
        return out;
    }

    private void writeRebuildLog(String tenantId, String source, String kind, long durationMs, int sessions,
                                 Date readWatermark) {
        try {
            com.smartdock.analytics.domain.AnalyticsRebuildLog logRow = new com.smartdock.analytics.domain.AnalyticsRebuildLog();
            logRow.setTenantId(tenantId);
            logRow.setSource(source);
            logRow.setKind(kind);
            logRow.setDurationMs(durationMs);
            logRow.setSessions(sessions);
            logRow.setFinishedAt(new Date());
            logRow.setReadWatermark(readWatermark);
            rebuildLogMapper.insert(logRow);
        } catch (Exception e) {
            log.warn("[Analytics] rebuild log persist failed (non-fatal) source={} : {}", source, e.getMessage());
        }
    }

    /**
     * 事件写入后重建最近两天：会话与日汇总一起刷新。
     *
     * <p>批10 顺手修(遗留清零表): 窗口起点改事件时间驱动——迟到事件(occurredAt 落在
     * 更早的天)会把窗口拉宽到它所在的自然日, 不再因固定"最近两天"而漏建。</p>
     *
     * @return 是否真正执行; false = 手动重建持锁中被跳过, 调用方应保留脏标记等下一个事件补
     */
    public boolean rebuildRecent(String tenantId, String source, Date occurredAt) {
        org.redisson.api.RLock lock = redisson.getLock(
            com.smartdock.profile.engine.ProfileLocks.rebuild(tenantId, source));
        boolean acquired;
        try {
            acquired = lock.tryLock(0, com.smartdock.profile.engine.ProfileLocks.REBUILD_LEASE_MINUTES,
                TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!acquired) {
            return false;
        }
        try {
            // 双实例去重: 比较"读取水位"而非完成时刻——上一轮重建已读到全部现存事件时才跳过,
            // 重建执行期间写入的事件不会被误判为已覆盖(它们仍在账本, 但会话贡献要等下一轮)
            Date watermark = rebuildLogMapper.selectLastReadWatermark(tenantId, source);
            Date latestEvent = eventMapper.selectMaxCreateTime(tenantId, source);
            if (watermark != null && (latestEvent == null || !latestEvent.after(watermark))) {
                return true;
            }
            long startedAt = System.currentTimeMillis();
            Date[] readThrough = new Date[1];
            int sessions = txTemplate.execute(status -> {
                Date from = startOfDaysAgo(2);
                if (occurredAt != null) {
                    Date occurredDay = startOfDay(occurredAt);
                    if (occurredDay.before(from)) {
                        // 批11 Q7: 迟到事件拉宽窗口设 7 天上限——更久远的离线缓存回传
                        // 会触发锁内全量重建, 性能放大; 超限只修最近 7 天, 历史缺口走回放
                        Date oldest = startOfDaysAgo(7);
                        if (occurredDay.before(oldest)) {
                            log.warn("[Analytics] late event beyond 7d cap, use replay for history: occurredAt={}", occurredAt);
                            from = oldest;
                        } else {
                            from = occurredDay;
                        }
                    }
                }
                Date to = startOfTomorrow();
                int rebuilt = sessionizer.rebuild(tenantId, source, from, to);
                rollup(tenantId, source, from, to);
                // 水位=本次重建实际读到的最大落库时间, 不再留安全边际——边际会让紧接着的
                // 顺序触发(双实例去重)永远判不覆盖; 残余风险仅剩"insert 事务滞留超过水位差"的毫秒级竞态
                readThrough[0] = eventMapper.selectMaxCreateTime(tenantId, source);
                return rebuilt;
            });
            writeRebuildLog(tenantId, source, "RECENT", System.currentTimeMillis() - startedAt,
                sessions, readThrough[0]);
            return true;
        } finally {
            unlockQuietly(lock);
        }
    }

    /** 租期已过被别人持有时 unlock 会抛 IllegalMonitorStateException, 降级为日志。 */
    private void unlockQuietly(org.redisson.api.RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            } else {
                log.warn("[Analytics] rebuild lock lease expired before unlock, another holder may have taken over");
            }
        } catch (Exception e) {
            log.warn("[Analytics] rebuild lock unlock failed: {}", e.getMessage());
        }
    }

    private int rollup(String tenantId, String source, Date from, Date to) {
        dailyMapper.deleteRange(tenantId, source, new java.sql.Date(from.getTime()),
            new java.sql.Date(to.getTime()));
        int rows = 0;
        for (LocalDate day : datesBetween(from, to)) {
            Date dayStart = Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant());
            Date dayEnd = Date.from(day.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
            Map<String, Object> ov = sessionMapper.selectOverview(tenantId, source, dayStart, dayEnd);
            AnalyticsDaily daily = new AnalyticsDaily();
            daily.setDailyId(IdWorker.getId());
            daily.setTenantId(tenantId);
            daily.setSource(source);
            daily.setStatDate(java.sql.Date.valueOf(day));
            daily.setPv(longValue(ov.get("pv")));
            daily.setUv(longValue(ov.get("uv")));
            daily.setVisits(longValue(ov.get("visits")));
            daily.setEvents(longValue(ov.get("events")));
            daily.setNewVisitors(longValue(ov.get("newVisitors")));
            daily.setReturningVisitors(longValue(ov.get("returningVisitors")));
            daily.setBounces(longValue(ov.get("bounces")));
            daily.setDurationSeconds(longValue(ov.get("durationSeconds")));
            dailyMapper.upsert(daily);
            rows++;
        }
        return rows;
    }

    // ---------------------------------------------------------------- 查询面

    public Map<String, Object> overview(String source, int days) {
        requireSource(source);
        String tenantId = currentTenant();
        Date from = startOfDaysAgo(days);
        Date to = startOfTomorrow();
        long span = to.getTime() - from.getTime();
        Map<String, Object> current = normalizeOverview(sessionMapper.selectOverview(tenantId, source, from, to));
        Map<String, Object> previous = normalizeOverview(sessionMapper.selectOverview(tenantId, source,
            new Date(from.getTime() - span), from));
        Map<String, Object> delta = new LinkedHashMap<>();
        for (String key : List.of("pv", "uv", "visits", "events", "newVisitors", "returningVisitors")) {
            delta.put(key, change(longValue(current.get(key)), longValue(previous.get(key))));
        }
        for (String key : List.of("bounceRate", "avgDuration", "pagesPerVisit")) {
            delta.put(key, change(decimal(current.get(key)).doubleValue(), decimal(previous.get(key)).doubleValue()));
        }
        current.put("newRate", rate(longValue(current.get("newVisitors")), longValue(current.get("visits"))));
        previous.put("newRate", rate(longValue(previous.get("newVisitors")), longValue(previous.get("visits"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("days", days);
        out.put("from", from);
        out.put("to", to);
        out.put("current", current);
        out.put("previous", previous);
        out.put("delta", delta);
        return out;
    }

    public List<Map<String, Object>> trend(String source, int days) {
        requireSource(source);
        String tenantId = currentTenant();
        Date from = startOfDaysAgo(days);
        Date to = startOfTomorrow();
        List<Map<String, Object>> rows = dailyMapper.selectTrend(tenantId, source,
            new java.sql.Date(from.getTime()), new java.sql.Date(to.getTime()));
        Map<String, Map<String, Object>> byDay = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            byDay.put(String.valueOf(row.get("statDate")), row);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (LocalDate day : datesBetween(from, to)) {
            String key = day.toString();
            Map<String, Object> row = byDay.get(key);
            if (row == null) {
                row = new LinkedHashMap<>();
                row.put("statDate", key);
                row.put("pv", 0);
                row.put("uv", 0);
                row.put("visits", 0);
                row.put("events", 0);
                row.put("newVisitors", 0);
                row.put("returningVisitors", 0);
                row.put("bounces", 0);
                row.put("durationSeconds", 0);
            }
            row.put("bounceRate", rate(longValue(row.get("bounces")), longValue(row.get("visits"))));
            row.put("avgDuration", avg(longValue(row.get("durationSeconds")), longValue(row.get("visits"))));
            out.add(row);
        }
        return out;
    }

    public Map<String, Object> breakdown(String source, int days, String type, int limit) {
        requireSource(source);
        String dim = StringUtils.isBlank(type) ? "channel" : type.trim().toLowerCase();
        int size = Math.min(Math.max(limit, 1), 100);
        String tenantId = currentTenant();
        Date from = startOfDaysAgo(days);
        Date to = startOfTomorrow();
        List<AnalyticsBreakdownRow> rows;
        long shareBase;
        if (SESSION_DIMENSIONS.contains(dim)) {
            rows = sessionMapper.selectBreakdown(tenantId, source, from, to, sessionColumn(dim), size);
            shareBase = rows.stream().mapToLong(row -> longValue(row.getVisits())).sum();
        } else if ("page".equals(dim)) {
            rows = eventMapper.selectPageBreakdown(tenantId, source, from, to, size);
            shareBase = rows.stream().mapToLong(row -> longValue(row.getPv())).sum();
        } else if ("search".equals(dim)) {
            rows = eventMapper.selectSearchBreakdown(tenantId, source, from, to, size);
            // 批11 回修(终审 P1): 搜索词聚合口与会话行同权——无 contact 权限按 TERM 规则打码
            for (AnalyticsBreakdownRow row : rows) {
                row.setLabel(piiService.maskValueForKey("search_term", row.getLabel()));
            }
            shareBase = rows.stream().mapToLong(row -> longValue(row.getPv())).sum();
        } else if ("event".equals(dim)) {
            rows = eventMapper.selectEventBreakdown(tenantId, source, from, to, size);
            shareBase = rows.stream().mapToLong(row -> longValue(row.getEvents())).sum();
        } else {
            throw new ServiceException("不支持的分析维度: " + type);
        }
        for (AnalyticsBreakdownRow row : rows) {
            long base = "event".equals(dim) ? longValue(row.getEvents())
                : ("page".equals(dim) || "search".equals(dim)) ? longValue(row.getPv())
                : longValue(row.getVisits());
            row.setShare(percent(base, shareBase));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("type", dim);
        out.put("days", days);
        out.put("from", from);
        out.put("to", to);
        out.put("rows", rows);
        return out;
    }

    public IPage<AnalyticsSession> sessionPage(AnalyticsSessionQuery query) {
        requireSource(query.getSource());
        Date[] range = resolveRange(query.getDays(), query.getFromDate(), query.getToDate());
        int pageNum = query.getPageNum() == null ? 1 : Math.max(1, query.getPageNum());
        int pageSize = query.getPageSize() == null ? 20 : Math.min(Math.max(1, query.getPageSize()), 200);
        Page<AnalyticsSession> page = new Page<>(pageNum, pageSize);
        page.setOptimizeCountSql(false);
        IPage<AnalyticsSession> rows = sessionMapper.selectSessionPage(page, currentTenant(), query.getSource(),
            range[0], range[1], query);
        // 批11 Q1: 会话行的搜索词按清单打码
        rows.getRecords().forEach(r -> r.setSearchTerm(piiService.maskValueForKey("search_term", r.getSearchTerm())));
        return rows;
    }

    public Map<String, Object> sessionDetail(Long sessionId) {
        AnalyticsSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw new ServiceException("访问记录不存在");
        }
        Date from = new Date(session.getStartedAt().getTime() - 60_000L);
        Date to = new Date(session.getLastEventAt().getTime() + 60_000L);
        boolean explicit = session.getSessionKey() != null && session.getSessionKey().startsWith("s:");
        String externalSession = explicit ? session.getSessionKey().substring(2) : null;
        // 显式会话按 sessionId 查: 登录前(匿名 ID)与登录后(用户 ID)的事件都能进时间线;
        // 按 visitorId 查会把匿名期事件过滤掉
        List<AnalyticsEventRow> events = explicit
            ? eventMapper.selectSessionEvents(session.getTenantId(), session.getSource(), externalSession, from, to)
            : eventMapper.selectVisitorEvents(session.getTenantId(), session.getSource(), session.getVisitorId(), from, to);
        List<Map<String, Object>> timeline = new ArrayList<>();
        for (AnalyticsEventRow event : events) {
            boolean same = explicit
                ? externalSession.equals(event.getSessionId())
                : StringUtils.isBlank(event.getSessionId());
            if (!same) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventId", event.getEventId());
            item.put("eventName", event.getEventName());
            item.put("occurredTime", event.getOccurredTime());
            Map<String, Object> context = piiService.maskOut(parse(event.getContext()));
            Map<String, Object> props = piiService.maskOut(parse(event.getProps()));
            item.put("pageUrl", firstString(context, "pageUrl", "url"));
            item.put("title", firstString(context, "title", "pageTitle"));
            item.put("referrer", firstString(context, "referrer", "referer"));
            item.put("props", props);
            item.put("context", context);
            timeline.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        // 批11 Q1: 无行为明细权限的角色只给会话元信息, 时间线不给(页面提示无权)
        if (!piiService.canViewBehavior()) {
            session.setSearchTerm(piiService.maskValueForKey("search_term", session.getSearchTerm()));
            out.put("session", session);
            out.put("timeline", List.of());
            out.put("pages", List.of());
            out.put("behaviorBlocked", true);
            return out;
        }
        out.put("session", session);
        out.put("timeline", timeline);
        out.put("pages", distinctPages(timeline));
        return out;
    }

    public List<AnalyticsFunnel> funnels(String source) {
        requireSource(source);
        return funnelMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AnalyticsFunnel>()
            .eq(AnalyticsFunnel::getTenantId, currentTenant())
            .eq(AnalyticsFunnel::getSource, source)
            .orderByDesc(AnalyticsFunnel::getBuiltin)
            .orderByAsc(AnalyticsFunnel::getCreateTime));
    }

    @Transactional(rollbackFor = Exception.class)
    public AnalyticsFunnel saveFunnel(AnalyticsFunnelBo bo) {
        if (bo == null) {
            throw new ServiceException("漏斗参数不能为空");
        }
        if (bo.getFunnelId() != null) {
            AnalyticsFunnel existing = funnelMapper.selectById(bo.getFunnelId());
            if (existing == null) {
                throw new ServiceException("漏斗不存在");
            }
            validateFunnel(bo.getFunnelName(), bo.getSteps());
            existing.setFunnelName(bo.getFunnelName().trim());
            existing.setSteps(JsonUtils.toJsonString(bo.getSteps()));
            existing.setWindowDays(clampWindow(bo.getWindowDays()));
            existing.setEnabled("0".equals(bo.getEnabled()) ? "0" : "1");
            existing.setRemark(StringUtils.substring(bo.getRemark(), 0, 500));
            existing.setUpdateBy(currentUser());
            funnelMapper.updateJsonb(existing);
            return existing;
        }
        if (StringUtils.isBlank(bo.getSource())) {
            throw new ServiceException("source 不能为空");
        }
        if (StringUtils.isBlank(bo.getFunnelCode())) {
            throw new ServiceException("漏斗编码不能为空");
        }
        validateFunnel(bo.getFunnelName(), bo.getSteps());
        AnalyticsFunnel funnel = new AnalyticsFunnel();
        funnel.setFunnelId(IdWorker.getId());
        funnel.setTenantId(currentTenant());
        funnel.setSource(bo.getSource());
        funnel.setFunnelCode(bo.getFunnelCode().trim());
        funnel.setFunnelName(bo.getFunnelName().trim());
        funnel.setSteps(JsonUtils.toJsonString(bo.getSteps()));
        funnel.setWindowDays(clampWindow(bo.getWindowDays()));
        funnel.setEnabled("0".equals(bo.getEnabled()) ? "0" : "1");
        funnel.setBuiltin("0");
        funnel.setRemark(StringUtils.substring(bo.getRemark(), 0, 500));
        funnel.setCreateBy(currentUser());
        funnelMapper.insertJsonb(funnel);
        return funnel;
    }

    @Transactional(rollbackFor = Exception.class)
    public boolean deleteFunnel(Long funnelId) {
        AnalyticsFunnel funnel = funnelMapper.selectById(funnelId);
        if (funnel == null) {
            return false;
        }
        if ("1".equals(funnel.getBuiltin())) {
            throw new ServiceException("内置漏斗不可删除, 可停用或另建");
        }
        return funnelMapper.deleteById(funnelId) > 0;
    }

    public Map<String, Object> funnelAnalysis(AnalyticsFunnelBo bo) {
        AnalyticsFunnel funnel = null;
        List<Map<String, Object>> steps = bo == null ? null : bo.getSteps();
        String source = bo == null ? null : bo.getSource();
        int windowDays = bo == null || bo.getWindowDays() == null ? 7 : clampWindow(bo.getWindowDays());
        if (bo != null && bo.getFunnelId() != null) {
            funnel = funnelMapper.selectById(bo.getFunnelId());
            if (funnel == null) {
                throw new ServiceException("漏斗不存在");
            }
            source = funnel.getSource();
            steps = parseSteps(funnel.getSteps());
            windowDays = clampWindow(funnel.getWindowDays());
        }
        requireSource(source);
        validateFunnel("临时漏斗", steps);
        int days = bo == null || bo.getDays() == null ? 30 : Math.min(Math.max(bo.getDays(), 1), 365);
        Date from = startOfDaysAgo(days);
        Date to = startOfTomorrow();
        List<String> names = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            names.add(String.valueOf(step.get("eventName")));
        }
        // 批4A 护栏: 载入前轻量 COUNT, 超阈值(默认 10 万)明确报错不进堆
        scaleGuard.checkAnalysisWindow(currentTenant(), source, from, to, names);
        // 批10 Q7: 事件名过滤下推——只拉步进相关行, 口径与全量载入后 Java 过滤完全等价
        List<AnalyticsEventRow> events = eventMapper.selectEventsByNames(currentTenant(), source, from, to, names);
        Map<String, List<AnalyticsEventRow>> byVisitor = new LinkedHashMap<>();
        for (AnalyticsEventRow event : events) {
            byVisitor.computeIfAbsent(event.getVisitorKey(), key -> new ArrayList<>()).add(event);
        }
        long[] counts = new long[names.size()];
        double[] seconds = new double[names.size()];
        long windowMs = windowDays * 24L * 3600L * 1000L;
        for (List<AnalyticsEventRow> list : byVisitor.values()) {
            list.sort(Comparator.comparing(AnalyticsEventRow::getOccurredTime));
            int step = 0;
            Date prev = null;
            for (AnalyticsEventRow event : list) {
                if (step >= names.size()) {
                    break;
                }
                if (!names.get(step).equals(event.getEventName())) {
                    continue;
                }
                if (step == 0) {
                    counts[0]++;
                    prev = event.getOccurredTime();
                    step = 1;
                } else if (event.getOccurredTime().getTime() - prev.getTime() <= windowMs) {
                    counts[step]++;
                    seconds[step] += (event.getOccurredTime().getTime() - prev.getTime()) / 1000.0;
                    prev = event.getOccurredTime();
                    step++;
                }
            }
        }
        List<Map<String, Object>> outSteps = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventName", names.get(i));
            item.put("name", steps.get(i).get("name") == null ? names.get(i) : steps.get(i).get("name"));
            item.put("count", counts[i]);
            item.put("rate", percent(counts[i], counts[0]));
            item.put("stepRate", i == 0 ? BigDecimal.valueOf(100) : percent(counts[i], counts[i - 1]));
            item.put("avgSeconds", i == 0 || counts[i] == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(seconds[i] / counts[i]).setScale(1, RoundingMode.HALF_UP));
            outSteps.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("funnelId", funnel == null ? null : funnel.getFunnelId());
        out.put("source", source);
        out.put("days", days);
        out.put("windowDays", windowDays);
        out.put("visitors", byVisitor.size());
        out.put("steps", outSteps);
        out.put("overallRate", percent(counts.length == 0 ? 0 : counts[counts.length - 1], counts.length == 0 ? 0 : counts[0]));
        return out;
    }

    public Map<String, Object> retention(String source, int days, int window) {
        requireSource(source);
        int span = Math.min(Math.max(days, 1), 180);
        int win = Math.min(Math.max(window, 1), 30);
        Date from = startOfDaysAgo(span);
        Date to = startOfTomorrow();
        List<Map<String, Object>> rows = sessionMapper.selectRetention(currentTenant(), source,
            new java.sql.Date(from.getTime()), new java.sql.Date(to.getTime()));
        Map<String, Map<Integer, Long>> byCohort = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            String cohort = String.valueOf(row.get("cohort"));
            int offset = (int) longValue(row.get("dayOffset"));
            if (offset < 0 || offset >= win) {
                continue;
            }
            byCohort.computeIfAbsent(cohort, key -> new LinkedHashMap<>())
                .put(offset, longValue(row.get("visitors")));
        }
        List<Map<String, Object>> cohorts = new ArrayList<>();
        for (Map.Entry<String, Map<Integer, Long>> entry : byCohort.entrySet()) {
            long total = entry.getValue().getOrDefault(0, 0L);
            List<Map<String, Object>> cells = new ArrayList<>();
            for (int i = 0; i < win; i++) {
                long visitors = entry.getValue().getOrDefault(i, 0L);
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("day", i);
                cell.put("visitors", visitors);
                cell.put("rate", percent(visitors, total));
                cells.add(cell);
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("cohort", entry.getKey());
            item.put("total", total);
            item.put("cells", cells);
            cohorts.add(item);
        }
        cohorts.sort(Comparator.comparing(item -> String.valueOf(item.get("cohort"))));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("days", span);
        out.put("window", win);
        out.put("cohorts", cohorts);
        return out;
    }

    public Map<String, Object> custom(AnalyticsCustomBo bo) {
        if (bo == null) {
            throw new ServiceException("自定义分析参数不能为空");
        }
        requireSource(bo.getSource());
        String dim = StringUtils.isBlank(bo.getDimension()) ? "day" : bo.getDimension().trim().toLowerCase();
        String metric = StringUtils.isBlank(bo.getMetric()) ? "pv" : bo.getMetric().trim().toLowerCase();
        if (!METRICS.contains(metric)) {
            throw new ServiceException("指标只支持 pv / uv / visits / events");
        }
        int days = bo.getDays() == null ? 30 : Math.min(Math.max(bo.getDays(), 1), 365);
        int limit = bo.getLimit() == null ? 20 : Math.min(Math.max(bo.getLimit(), 1), 100);
        List<Map<String, Object>> points = new ArrayList<>();
        if ("day".equals(dim)) {
            for (Map<String, Object> row : trend(bo.getSource(), days)) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("label", row.get("statDate"));
                point.put("value", row.get(metric));
                points.add(point);
            }
        } else {
            Map<String, Object> result = breakdown(bo.getSource(), days, dim, limit);
            @SuppressWarnings("unchecked")
            List<AnalyticsBreakdownRow> rows = (List<AnalyticsBreakdownRow>) result.get("rows");
            for (AnalyticsBreakdownRow row : rows) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("label", row.getLabel());
                point.put("value", metricValue(row, metric));
                points.add(point);
            }
            // 下钻接口固定按 PV 排序；自定义分析要按用户所选指标重排
            points.sort(Comparator.comparingLong(
                (Map<String, Object> point) -> longValue(point.get("value"))).reversed());
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dimension", dim);
        out.put("metric", metric);
        out.put("days", days);
        out.put("points", points);
        return out;
    }

    // ---------------------------------------------------------------- CSV 导入

    public Map<String, Object> importCsv(AnalyticsImportBo bo) {
        if (bo == null || StringUtils.isBlank(bo.getSource())) {
            throw new ServiceException("source 不能为空");
        }
        if (StringUtils.isBlank(bo.getCsv())) {
            throw new ServiceException("CSV 内容不能为空");
        }
        // 内存防线: 5000 行限制要在解析前拦住超大输入, 否则先全量解析已把内存吃满
        if (bo.getCsv().length() > 5_000_000) {
            throw new ServiceException("CSV 内容过大(>5MB), 请分批导入");
        }
        List<List<String>> rows = parseCsv(bo.getCsv());
        if (rows.size() < 2) {
            throw new ServiceException("CSV 至少需要表头和一行数据");
        }
        List<String> header = rows.get(0);
        // Excel/Windows 导出的 CSV 首列常带 BOM, 不剥掉会导致 eventName/eventUid 列名匹配失败
        if (!header.isEmpty() && header.get(0) != null && header.get(0).startsWith("\uFEFF")) {
            header.set(0, header.get(0).substring(1));
        }
        Map<String, Integer> index = new HashMap<>();
        for (int i = 0; i < header.size(); i++) {
            index.put(header.get(i).trim().toLowerCase(), i);
        }
        if (!index.containsKey("eventname") && !index.containsKey("event_name")) {
            throw new ServiceException("CSV 缺少 eventName 列");
        }
        int limit = Math.min(rows.size() - 1, MAX_CSV_ROWS);
        int invalidTime = 0;
        List<ProfileEventBo> events = new ArrayList<>();
        for (int i = 1; i <= limit; i++) {
            List<String> line = rows.get(i);
            String eventName = cell(line, index, "eventname", "event_name");
            if (StringUtils.isBlank(eventName)) {
                continue;
            }
            ProfileEventBo event = new ProfileEventBo();
            event.setSource(bo.getSource());
            event.setEventName(eventName.trim());
            event.setEventType(eventName.trim());
            // 尊重 CSV 里的 eventUid 列: 同一份文件重复导入时幂等键才一致, 不会重复计数
            String csvUid = cell(line, index, "eventuid", "event_uid");
            event.setEventUid(StringUtils.isNotBlank(csvUid) ? csvUid.trim() : "csv-" + UUID.randomUUID());
            event.setSubjectType(defaultIfBlank(cell(line, index, "subjecttype", "subject_type"), "user"));
            event.setSubjectId(defaultIfBlank(cell(line, index, "subjectid", "subject_id"),
                defaultIfBlank(cell(line, index, "anonymousid", "anonymous_id"), "csv-unknown")));
            event.setAnonymousId(cell(line, index, "anonymousid", "anonymous_id"));
            event.setSessionId(cell(line, index, "sessionid", "session_id"));
            event.setSubjectName(cell(line, index, "subjectname", "subject_name"));
            String occurred = cell(line, index, "occurredtime", "occurred_time", "time");
            if (StringUtils.isNotBlank(occurred)) {
                Date occurredAt = parseTime(occurred);
                if (occurredAt == null) {
                    // 非法时间静默取当前时间会把脏数据混进历史统计, 整行拒绝并计数
                    invalidTime++;
                    continue;
                }
                event.setOccurredTime(occurredAt);
            }
            Map<String, Object> props = parseJsonObject(cell(line, index, "props", "properties"));
            String pageUrl = cell(line, index, "pageurl", "page_url", "url");
            if (StringUtils.isNotBlank(pageUrl)) {
                props.put("pageUrl", pageUrl);
            }
            String keyword = cell(line, index, "keyword", "searchterm");
            if (StringUtils.isNotBlank(keyword)) {
                props.put("keyword", keyword);
            }
            event.setProps(props);
            Map<String, Object> context = parseJsonObject(cell(line, index, "context"));
            if (StringUtils.isNotBlank(pageUrl)) {
                context.putIfAbsent("pageUrl", pageUrl);
            }
            if (StringUtils.isNotBlank(keyword)) {
                context.putIfAbsent("searchTerm", keyword);
            }
            copyIfPresent(context, cell(line, index, "referrer"), "referrer");
            copyIfPresent(context, cell(line, index, "channel", "sourcechannel"), "sourceChannel");
            copyIfPresent(context, cell(line, index, "campaign"), "campaign");
            copyIfPresent(context, cell(line, index, "devicetype", "device"), "deviceType");
            copyIfPresent(context, cell(line, index, "os"), "os");
            copyIfPresent(context, cell(line, index, "browser"), "browser");
            copyIfPresent(context, cell(line, index, "country"), "country");
            copyIfPresent(context, cell(line, index, "province"), "province");
            copyIfPresent(context, cell(line, index, "city"), "city");
            event.setContext(context);
            events.add(event);
        }
        int written = eventPublisherProvider.getObject().publishBatch(events);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("received", events.size());
        out.put("written", written);
        out.put("skipped", events.size() - written);
        out.put("dropped", Math.max(0, rows.size() - 1 - limit));
        out.put("invalidTime", invalidTime);
        return out;
    }

    // ---------------------------------------------------------------- 批8 分析深度

    /**
     * 路径分析（拍板 Q1-A）：页面桑基，口径与漏斗一致（每访客首次顺序，连续重复页折叠）。
     */
    public Map<String, Object> paths(String source, int days, String startPage) {
        requireSource(source);
        int clampedDays = Math.min(Math.max(days, 1), 90);
        Date from = startOfDaysAgo(clampedDays);
        Date to = startOfTomorrow();
        // 批4A 护栏: 载入前轻量 COUNT(同页面浏览事件口径)
        scaleGuard.checkAnalysisWindow(currentTenant(), source, from, to, List.of("page_view", "pageview", "pv"));
        // 批10 Q7: 只拉页面浏览事件(路径只用得上它们), 口径不变
        List<AnalyticsEventRow> events = eventMapper.selectEventsByNames(
            currentTenant(), source, from, to, List.of("page_view", "pageview", "pv"));
        List<AnalyticsPathBuilder.PathEvent> pageEvents = new ArrayList<>();
        for (AnalyticsEventRow event : events) {
            if (!isPageView(event.getEventName())) {
                continue;
            }
            String page = firstString(parse(event.getContext()), "pageUrl", "url");
            if (page == null) {
                page = firstString(parse(event.getProps()), "pageUrl");
            }
            if (page == null || page.isBlank()) {
                continue;
            }
            pageEvents.add(new AnalyticsPathBuilder.PathEvent(
                event.getVisitorKey(), event.getOccurredTime().getTime(), page));
        }
        Map<String, Object> graph = AnalyticsPathBuilder.build(pageEvents, startPage, 3, 30);
        graph.put("source", source);
        graph.put("days", clampedDays);
        graph.put("start", StringUtils.isBlank(startPage) ? null : startPage.trim());
        return graph;
    }

    /**
     * 漏斗流失下钻（拍板 Q2-A）：每步流失访客"接下来去了哪"Top5。
     * 步进算法与 funnelAnalysis 完全一致（同窗口语义），保证两边计数可交叉对账。
     */
    public Map<String, Object> funnelLoss(AnalyticsFunnelBo bo) {
        AnalyticsFunnel funnel = null;
        List<Map<String, Object>> steps = bo == null ? null : bo.getSteps();
        String source = bo == null ? null : bo.getSource();
        int windowDays = bo == null || bo.getWindowDays() == null ? 7 : clampWindow(bo.getWindowDays());
        if (bo != null && bo.getFunnelId() != null) {
            funnel = funnelMapper.selectById(bo.getFunnelId());
            if (funnel == null) {
                throw new ServiceException("漏斗不存在");
            }
            source = funnel.getSource();
            steps = parseSteps(funnel.getSteps());
            windowDays = clampWindow(funnel.getWindowDays());
        }
        requireSource(source);
        validateFunnel("临时漏斗", steps);
        int days = bo == null || bo.getDays() == null ? 30 : Math.min(Math.max(bo.getDays(), 1), 365);
        Date from = startOfDaysAgo(days);
        Date to = startOfTomorrow();
        List<String> names = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            names.add(String.valueOf(step.get("eventName")));
        }
        // 批4A 护栏: 与漏斗预览同一计数口径——预览被拦则下钻同拦, 不出现"预览过、下钻炸"
        scaleGuard.checkAnalysisWindow(currentTenant(), source, from, to, names);
        // 批10 Q7: 下钻只需要"碰过第一步"的访客(没碰过的本就不进任何步进统计),
        // 半连接收敛后大窗口不再全量载入内存
        List<AnalyticsEventRow> events = eventMapper.selectEventsForVisitors(
            currentTenant(), source, from, to, names.get(0));
        Map<String, List<AnalyticsEventRow>> all = new LinkedHashMap<>();
        for (AnalyticsEventRow event : events) {
            all.computeIfAbsent(event.getVisitorKey(), k -> new ArrayList<>()).add(event);
        }
        all.values().forEach(list -> list.sort(Comparator.comparing(AnalyticsEventRow::getOccurredTime)));
        long windowMs = windowDays * 24L * 3600L * 1000L;
        long[] counts = new long[names.size()];
        Map<String, Integer> reached = new LinkedHashMap<>();
        Map<String, Long> lastStepTime = new LinkedHashMap<>();
        for (Map.Entry<String, List<AnalyticsEventRow>> entry : all.entrySet()) {
            String visitor = entry.getKey();
            int step = 0;
            Date prev = null;
            for (AnalyticsEventRow event : entry.getValue()) {
                if (step >= names.size()) {
                    break;
                }
                if (!names.get(step).equals(event.getEventName())) {
                    continue;
                }
                if (step == 0) {
                    counts[0]++;
                    prev = event.getOccurredTime();
                    step = 1;
                } else if (event.getOccurredTime().getTime() - prev.getTime() <= windowMs) {
                    counts[step]++;
                    prev = event.getOccurredTime();
                    step++;
                }
            }
            int finalStep = step - 1;
            reached.put(visitor, finalStep);
            if (prev != null) {
                lastStepTime.put(visitor, prev.getTime());
            }
        }
        List<Map<String, Object>> outSteps = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("eventName", names.get(i));
            item.put("count", counts[i]);
            item.put("lost", i + 1 < names.size() ? counts[i] - counts[i + 1] : 0L);
            if (i + 1 < names.size()) {
                Map<String, Long> nextCount = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> r : reached.entrySet()) {
                    if (r.getValue() != i) {
                        continue;
                    }
                    Long anchor = lastStepTime.get(r.getKey());
                    if (anchor == null) {
                        continue;
                    }
                    String next = "(离开)";
                    for (AnalyticsEventRow event : all.get(r.getKey())) {
                        if (event.getOccurredTime().getTime() > anchor) {
                            next = labelOf(event);
                            break;
                        }
                    }
                    nextCount.merge(next, 1L, Long::sum);
                }
                List<Map<String, Object>> top = new ArrayList<>();
                nextCount.entrySet().stream()
                    .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                    .limit(5)
                    .forEach(e -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("label", e.getKey());
                        row.put("count", e.getValue());
                        top.add(row);
                    });
                item.put("next", top);
            }
            outSteps.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("funnelId", funnel == null ? null : funnel.getFunnelId());
        out.put("source", source);
        out.put("days", days);
        out.put("windowDays", windowDays);
        out.put("steps", outSteps);
        return out;
    }

    /**
     * 渠道归因（拍板 Q3-A）：触点=会话渠道来源，转化=目标事件首次发生（默认 signup）。
     */
    public Map<String, Object> attribution(String source, int days, String conversionEvent) {
        requireSource(source);
        int clampedDays = Math.min(Math.max(days, 1), 90);
        String conversion = StringUtils.isBlank(conversionEvent) ? "signup" : conversionEvent.trim();
        Date from = startOfDaysAgo(clampedDays);
        Date to = startOfTomorrow();
        String tenantId = currentTenant();
        List<AttributionCalculator.Touch> touches = new ArrayList<>();
        for (Map<String, Object> row : sessionMapper.selectChannelTouchpoints(tenantId, source, from, to)) {
            touches.add(new AttributionCalculator.Touch(
                String.valueOf(row.get("visitorId")),
                ((Date) row.get("startedAt")).getTime(),
                String.valueOf(row.get("channel"))));
        }
        Map<String, Long> firstConversion = new LinkedHashMap<>();
        // 批10 Q7: 归因只消费转化事件, 事件名下推后不再全量载入
        for (AnalyticsEventRow event : eventMapper.selectEventsByNames(
            tenantId, source, from, to, List.of(conversion))) {
            if (conversion.equals(event.getEventName()) && event.getVisitorKey() != null
                && !firstConversion.containsKey(event.getVisitorKey())) {
                firstConversion.put(event.getVisitorKey(), event.getOccurredTime().getTime());
            }
        }
        List<AttributionCalculator.Conversion> conversions = new ArrayList<>();
        firstConversion.forEach((visitor, time) -> conversions.add(new AttributionCalculator.Conversion(visitor, time)));
        Map<String, Object> out = AttributionCalculator.attribute(touches, conversions);
        out.put("source", source);
        out.put("days", clampedDays);
        out.put("conversionEvent", conversion);
        return out;
    }

    /**
     * 活跃口径（拍板 Q4-A）：DAU/WAU/MAU 滚动 + 粘性 + 最新日新老构成。
     */
    public Map<String, Object> activity(String source, int days) {
        requireSource(source);
        int clampedDays = Math.min(Math.max(days, 1), 90);
        Date from = startOfDaysAgo(clampedDays);
        Date to = startOfTomorrow();
        String tenantId = currentTenant();
        List<ActivityCalculator.ActiveDay> activeDays = new ArrayList<>();
        for (Map<String, Object> row : eventMapper.selectDailyActiveVisitors(tenantId, source, from, to)) {
            activeDays.add(new ActivityCalculator.ActiveDay(
                String.valueOf(row.get("day")), String.valueOf(row.get("visitorKey"))));
        }
        Map<String, Object> out = ActivityCalculator.compute(activeDays, 30);
        List<Map<String, Object>> trend = dailyMapper.selectTrend(tenantId, source,
            new java.sql.Date(from.getTime()), new java.sql.Date(to.getTime()));
        out.put("newVsReturning", lastActiveMix(trend));
        out.put("source", source);
        out.put("days", clampedDays);
        return out;
    }

    /**
     * R2-1: 最新日新老构成与 DAU 同锚——DAU 卡取事件表最后有数据天, 构成卡原取趋势尾行,
     * 会被 rebuild 补写的全 0 行顶位(0/0 观感缺陷)。跳过尾部全 0 行, 取最后一个有会话的天。
     */
    static Map<String, Object> lastActiveMix(List<Map<String, Object>> trend) {
        Map<String, Object> mix = new LinkedHashMap<>();
        for (int i = trend.size() - 1; i >= 0; i--) {
            Map<String, Object> row = trend.get(i);
            if (toLong(row.get("newVisitors")) + toLong(row.get("returningVisitors")) <= 0) {
                continue;
            }
            mix.put("statDate", row.get("statDate"));
            mix.put("newVisitors", toLong(row.get("newVisitors")));
            mix.put("returningVisitors", toLong(row.get("returningVisitors")));
            break;
        }
        return mix;
    }

    private static long toLong(Object v) {
        return v instanceof Number n ? n.longValue() : 0L;
    }

    /**
     * 实时看板（拍板 Q5-A）：近 5 分钟直查事件账本，不经会话重建，最坏延迟≈轮询间隔。
     */
    public Map<String, Object> realtime(String source) {
        requireSource(source);
        String tenantId = currentTenant();
        Date now = new Date();
        Date from = new Date(now.getTime() - 5 * 60_000L);
        long events = 0;
        long pv = 0;
        List<Map<String, Object>> minutes = eventMapper.selectRealtimeBuckets(tenantId, source, from, now);
        for (Map<String, Object> bucket : minutes) {
            events += longValue(bucket.get("events"));
            pv += longValue(bucket.get("pv"));
        }
        int visitors = eventMapper.selectWindowVisitors(tenantId, source, from, now).size();
        // 批10 Q7: 新访客直查首见物化表(只数窗口内首见的行), 不再全表行进 Java 过滤
        long newVisitors = firstSeenMapper.countSince(tenantId, source, from);
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("visitors", visitors);
        summary.put("pv", pv);
        summary.put("events", events);
        summary.put("newVisitors", newVisitors);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("now", now);
        out.put("windowMinutes", 5);
        out.put("summary", summary);
        out.put("minutes", minutes);
        return out;
    }

    /**
     * 全域总览（拍板 Q6-A）：跨业务源事件量/访客数/最近写入（直查账本，无需逐源重建）。
     */
    public Map<String, Object> crossSource(int days) {
        int clampedDays = Math.min(Math.max(days, 1), 90);
        Date from = startOfDaysAgo(clampedDays);
        List<Map<String, Object>> rows = eventMapper.selectCrossSourceSummary(currentTenant(), from);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", clampedDays);
        out.put("sources", rows);
        return out;
    }

    private static boolean isPageView(String eventName) {
        return "page_view".equals(eventName) || "pageview".equals(eventName) || "pv".equals(eventName);
    }

    /**
     * 下钻标签：页面浏览事件显示页面路径，其余显示事件名。
     */
    private String labelOf(AnalyticsEventRow event) {
        if (isPageView(event.getEventName())) {
            String page = firstString(parse(event.getContext()), "pageUrl", "url");
            if (page == null) {
                page = firstString(parse(event.getProps()), "pageUrl");
            }
            if (page != null && !page.isBlank()) {
                return page;
            }
        }
        return event.getEventName();
    }

    // ---------------------------------------------------------------- R2-5: 保留期水位 + 报表导出

    /** R2-5 项11: 该源事件保留期天数(源配置覆盖全局默认; ≤0 = 不清理, 无水位限制)。 */
    private int eventRetentionDaysOf(String tenantId, String source) {
        return TenantHelper.ignore(() -> {
            ProfileEngineConfig config = engineConfigMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ProfileEngineConfig>()
                    .eq(ProfileEngineConfig::getTenantId, tenantId)
                    .eq(ProfileEngineConfig::getSource, source)
                    .last("LIMIT 1"))
                .stream().findFirst().orElse(null);
            return config == null || config.getEventRetentionDays() == null
                ? defaultEventRetentionDays : config.getEventRetentionDays();
        });
    }

    private void guardRebuildWatermark(String tenantId, String source, Date from) {
        int days = eventRetentionDaysOf(tenantId, source);
        if (days <= 0) {
            return;
        }
        Date watermark = new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(days));
        if (from.before(watermark)) {
            java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("yyyy-MM-dd");
            throw new ServiceException("重建窗口起点 " + fmt.format(from) + " 早于该源事件保留期水位 "
                + fmt.format(watermark) + "（保留 " + days + " 天）：该窗口事件已出账，重建会把派生表清零且无法恢复，"
                + "请缩小重建窗口或调整该源保留期配置");
        }
    }

    /**
     * R2-5 项10: 访问明细 CSV 导出(默认脱敏; 搜索词始终按 PII 规则打码, 与页面同口径)。
     * 行数上限 5 万, 超限明确报错引导缩窗——报表级导出不做全量事件导出。
     */
    public byte[] exportSessions(AnalyticsSessionQuery query, boolean plain) {
        requireSource(query.getSource());
        Date[] range = resolveRange(query.getDays(), query.getFromDate(), query.getToDate());
        Page<AnalyticsSession> page = new Page<>(1, EXPORT_MAX_ROWS + 1);
        page.setOptimizeCountSql(false);
        IPage<AnalyticsSession> rows = sessionMapper.selectSessionPage(
            page, currentTenant(), query.getSource(), range[0], range[1], query);
        if (rows.getRecords().size() > EXPORT_MAX_ROWS) {
            throw new ServiceException("匹配会话 " + rows.getTotal() + " 条超过导出上限 "
                + EXPORT_MAX_ROWS + "，请缩小时间范围或筛选条件后再导出");
        }
        StringBuilder csv = new StringBuilder("\uFEFF"); // Excel 兼容 BOM
        csv.append("会话ID,访客ID,主体类型,主体ID,新访客,开始时间,最后事件,时长秒,页面浏览,事件数,跳出,")
            .append("进入页,退出页,来源渠道,活动,设备,系统,浏览器,地域,搜索词\r\n");
        for (AnalyticsSession s : rows.getRecords()) {
            csv.append(csvCell(string(s.getSessionId()))).append(',')
                .append(csvCell(plain ? string(s.getVisitorId()) : mask(string(s.getVisitorId())))).append(',')
                .append(csvCell(nullSafe(s.getSubjectType()))).append(',')
                .append(csvCell(plain ? nullSafe(s.getSubjectId()) : mask(nullSafe(s.getSubjectId())))).append(',')
                .append(csvCell("1".equals(s.getIsNewVisitor()) ? "是" : "否")).append(',')
                .append(csvCell(string(s.getStartedAt()))).append(',')
                .append(csvCell(string(s.getLastEventAt()))).append(',')
                .append(csvCell(string(s.getDurationSeconds()))).append(',')
                .append(csvCell(string(s.getPageViews()))).append(',')
                .append(csvCell(string(s.getEventCount()))).append(',')
                .append(csvCell("1".equals(s.getIsBounce()) ? "是" : "否")).append(',')
                .append(csvCell(nullSafe(s.getEntryPage()))).append(',')
                .append(csvCell(nullSafe(s.getExitPage()))).append(',')
                .append(csvCell(nullSafe(s.getSourceChannel()))).append(',')
                .append(csvCell(nullSafe(s.getCampaign()))).append(',')
                .append(csvCell(nullSafe(s.getDeviceType()))).append(',')
                .append(csvCell(nullSafe(s.getOs()))).append(',')
                .append(csvCell(nullSafe(s.getBrowser()))).append(',')
                .append(csvCell(geoOf(s))).append(',')
                .append(csvCell(piiService.maskValueForKey("search_term", s.getSearchTerm()))).append("\r\n");
        }
        byte[] bytes = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeExportAudit(query.getSource(), "分析中心-访问明细导出", rows.getRecords().size(), !plain, bytes);
        return bytes;
    }

    /** R2-5 项10: 自定义分析结果 CSV 导出(聚合值, 无主体级 PII)。 */
    public byte[] exportCustom(AnalyticsCustomBo bo) {
        Map<String, Object> result = custom(bo);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) result.get("points");
        StringBuilder csv = new StringBuilder("\uFEFF");
        csv.append("排名,标签,数值\r\n");
        int rank = 1;
        for (Map<String, Object> point : points) {
            csv.append(rank++).append(',')
                .append(csvCell(String.valueOf(point.get("label")))).append(',')
                .append(csvCell(String.valueOf(point.get("value")))).append("\r\n");
        }
        byte[] bytes = csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        writeExportAudit(bo.getSource(), "分析中心-自定义分析导出", points.size(), true, bytes);
        return bytes;
    }

    /** 导出审计行(复用画像侧 profile_export_log, 合规页可见); 写失败即失败——审计不许静默丢。 */
    private void writeExportAudit(String source, String title, int rowCount, boolean masked, byte[] bytes) {
        ProfileExportLog logRow = new ProfileExportLog();
        logRow.setTenantId(currentTenant());
        logRow.setSource(source);
        // 表列 NOT NULL 且无默认; 分析中心导出不挂分群, 0=全源/非分群哨兵
        logRow.setSegmentId(0L);
        logRow.setSegmentName(title);
        logRow.setRowCount((long) rowCount);
        logRow.setMasked(masked ? "1" : "0");
        logRow.setFileHash(cn.hutool.crypto.digest.DigestUtil.sha256Hex(bytes).substring(0, 16));
        logRow.setExportBy(currentUser());
        logRow.setExportAt(new Date());
        exportLogMapper.insert(logRow);
    }

    private String geoOf(AnalyticsSession s) {
        String geo = java.util.stream.Stream.of(s.getCountry(), s.getProvince(), s.getCity())
            .filter(StringUtils::isNotBlank).collect(java.util.stream.Collectors.joining(" / "));
        return geo.isEmpty() ? "" : geo;
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String mask(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (value.length() <= 4) {
            return value.charAt(0) + "***";
        }
        return value.substring(0, 3) + "***" + value.substring(value.length() - 2);
    }

    private String csvCell(String value) {
        String v = value == null ? "" : value;
        // CSV 公式注入防御(对齐画像侧批11 口径): =/+/-/@/TAB/CR 开头前置单引号
        if (!v.isEmpty() && "=+-@\t\r".indexOf(v.charAt(0)) >= 0) {
            v = "'" + v;
        }
        if (v.contains(",") || v.contains("\"") || v.contains("\n")) {
            return '"' + v.replace("\"", "\"\"") + '"';
        }
        return v;
    }

    // ---------------------------------------------------------------- 内部工具

    private Map<String, Object> normalizeOverview(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            raw = Map.of();
        }
        long visits = longValue(raw.get("visits"));
        long pv = longValue(raw.get("pv"));
        long duration = longValue(raw.get("durationSeconds"));
        out.put("pv", pv);
        out.put("uv", longValue(raw.get("uv")));
        out.put("visits", visits);
        out.put("events", longValue(raw.get("events")));
        out.put("newVisitors", longValue(raw.get("newVisitors")));
        out.put("returningVisitors", longValue(raw.get("returningVisitors")));
        out.put("bounces", longValue(raw.get("bounces")));
        out.put("durationSeconds", duration);
        out.put("bounceRate", rate(longValue(raw.get("bounces")), visits));
        out.put("avgDuration", avg(duration, visits));
        out.put("pagesPerVisit", visits == 0 ? BigDecimal.ZERO
            : BigDecimal.valueOf(pv).divide(BigDecimal.valueOf(visits), 2, RoundingMode.HALF_UP));
        return out;
    }

    private String sessionColumn(String dim) {
        return switch (dim) {
            case "channel" -> "source_channel";
            case "device" -> "device_type";
            case "browser" -> "browser";
            case "os" -> "os";
            case "geo" -> "concat_ws(' / ', nullif(country, ''), nullif(province, ''), nullif(city, ''))";
            case "entry" -> "entry_page";
            case "exit" -> "exit_page";
            case "campaign" -> "campaign";
            default -> throw new ServiceException("不支持的分析维度: " + dim);
        };
    }

    private Object metricValue(AnalyticsBreakdownRow row, String metric) {
        return switch (metric) {
            case "pv" -> row.getPv();
            case "uv" -> row.getUv();
            case "visits" -> row.getVisits();
            default -> row.getEvents();
        };
    }

    private Date[] resolveRange(Integer days, String fromDate, String toDate) {
        if (StringUtils.isNotBlank(fromDate) && StringUtils.isNotBlank(toDate)) {
            LocalDate from = LocalDate.parse(fromDate);
            LocalDate to = LocalDate.parse(toDate);
            return new Date[]{
                Date.from(from.atStartOfDay(ZoneId.systemDefault()).toInstant()),
                Date.from(to.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant())
            };
        }
        return new Date[]{startOfDaysAgo(days == null ? 30 : days), startOfTomorrow()};
    }

    private List<Map<String, Object>> distinctPages(List<Map<String, Object>> timeline) {
        Map<String, Map<String, Object>> pages = new LinkedHashMap<>();
        for (Map<String, Object> item : timeline) {
            Object page = item.get("pageUrl");
            if (page == null || StringUtils.isBlank(String.valueOf(page))) {
                continue;
            }
            String key = String.valueOf(page);
            Map<String, Object> pageItem = pages.computeIfAbsent(key, k -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pageUrl", k);
                m.put("title", item.get("title"));
                m.put("views", 0);
                m.put("firstSeen", item.get("occurredTime"));
                m.put("lastSeen", item.get("occurredTime"));
                return m;
            });
            pageItem.put("views", (Integer) pageItem.get("views") + 1);
            pageItem.put("lastSeen", item.get("occurredTime"));
        }
        return new ArrayList<>(pages.values());
    }

    private void validateFunnel(String name, List<Map<String, Object>> steps) {
        if (StringUtils.isBlank(name)) {
            throw new ServiceException("漏斗名称不能为空");
        }
        if (steps == null || steps.size() < 2) {
            throw new ServiceException("漏斗至少需要两个步骤");
        }
        if (steps.size() > 10) {
            throw new ServiceException("漏斗最多十个步骤");
        }
        for (Map<String, Object> step : steps) {
            if (step == null || StringUtils.isBlank(String.valueOf(step.get("eventName")))) {
                throw new ServiceException("每个步骤都必须填写 eventName");
            }
        }
    }

    private int clampWindow(Integer window) {
        return Math.min(Math.max(window == null ? 7 : window, 1), 90);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseSteps(String json) {
        if (StringUtils.isBlank(json)) {
            return List.of();
        }
        try {
            Object list = JsonUtils.parseObject(json, List.class);
            return list instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    private Date startOfDaysAgo(int days) {
        // 统一 clamp 1..365: 防止恶意/误传超大 days 逐日循环拖垮数据源
        LocalDate day = LocalDate.now().minusDays(Math.min(Math.max(days, 1), 365) - 1L);
        return Date.from(day.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private Date startOfTomorrow() {
        return Date.from(LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    /** 迟到事件窗口起点: 落到事件发生的自然日零点(rebuildRecent 拉宽窗口用)。 */
    private Date startOfDay(Date time) {
        return Date.from(time.toInstant().atZone(ZoneId.systemDefault())
            .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private List<LocalDate> datesBetween(Date from, Date to) {
        LocalDate start = from.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        LocalDate end = to.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
        List<LocalDate> out = new ArrayList<>();
        for (LocalDate day = start; day.isBefore(end); day = day.plusDays(1)) {
            out.add(day);
        }
        return out;
    }

    private Map<String, Object> parse(String json) {
        if (StringUtils.isBlank(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JsonUtils.parseMap(json);
            return parsed == null ? Map.of() : parsed;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String firstString(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && StringUtils.isNotBlank(String.valueOf(value))) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value == null) {
            return 0L;
        }
        try {
            return new BigDecimal(String.valueOf(value)).longValue();
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal b) {
            return b;
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.doubleValue());
        }
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal rate(long part, long total) {
        return percent(part, total);
    }

    private BigDecimal avg(long sum, long count) {
        return count == 0 ? BigDecimal.ZERO
            : BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal percent(long part, long total) {
        return total == 0 ? BigDecimal.ZERO
            : BigDecimal.valueOf(part * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal change(double current, double previous) {
        if (previous == 0) {
            // 没有上一周期基线时不能谎报 +100%，返回 null 由前端显示“无对比数据”
            return current == 0 ? BigDecimal.ZERO : null;
        }
        return BigDecimal.valueOf((current - previous) * 100.0 / previous).setScale(2, RoundingMode.HALF_UP);
    }

    private List<List<String>> parseCsv(String csv) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        String text = csv.replace("\r\n", "\n").replace('\r', '\n');
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (ch == '\n') {
                row.add(cell.toString());
                cell.setLength(0);
                if (!row.isEmpty() && row.stream().anyMatch(StringUtils::isNotBlank)) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                cell.append(ch);
            }
        }
        row.add(cell.toString());
        if (!row.isEmpty() && row.stream().anyMatch(StringUtils::isNotBlank)) {
            rows.add(row);
        }
        return rows;
    }

    private String cell(List<String> line, Map<String, Integer> index, String... keys) {
        for (String key : keys) {
            Integer i = index.get(key);
            if (i != null && i < line.size() && StringUtils.isNotBlank(line.get(i))) {
                return line.get(i).trim();
            }
        }
        return null;
    }

    private String defaultIfBlank(String value, String fallback) {
        return StringUtils.isBlank(value) ? fallback : value;
    }

    private void copyIfPresent(Map<String, Object> map, String value, String key) {
        if (StringUtils.isNotBlank(value)) {
            map.putIfAbsent(key, value);
        }
    }

    private Map<String, Object> parseJsonObject(String raw) {
        if (StringUtils.isBlank(raw)) {
            return new LinkedHashMap<>();
        }
        try {
            Map<String, Object> parsed = JsonUtils.parseMap(raw);
            return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
        } catch (Exception e) {
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("value", raw);
            return fallback;
        }
    }

    /** @return 解析失败返回 null, 由调用方决定拒绝策略 */
    private Date parseTime(String raw) {
        try {
            return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(raw);
        } catch (Exception ignored) {
            try {
                return new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").parse(raw);
            } catch (Exception e) {
                return null;
            }
        }
    }

    private void requireSource(String source) {
        if (StringUtils.isBlank(source)) {
            throw new ServiceException("source 不能为空");
        }
    }

    private Long currentUser() {
        try {
            return LoginHelper.getUserId();
        } catch (Exception e) {
            return null;
        }
    }

    private String currentTenant() {
        try {
            String tenant = TenantHelper.getTenantId();
            if (StringUtils.isNotBlank(tenant)) {
                return tenant;
            }
        } catch (Exception ignored) {
            // 无租户上下文
        }
        try {
            String tenant = LoginHelper.getTenantId();
            if (StringUtils.isNotBlank(tenant)) {
                return tenant;
            }
        } catch (Exception ignored) {
            // 非登录上下文
        }
        return com.smartdock.profile.engine.ProfileTenantContext.require();
    }
}
