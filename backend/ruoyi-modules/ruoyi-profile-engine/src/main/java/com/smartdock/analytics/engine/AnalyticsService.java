package com.smartdock.analytics.engine;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdock.analytics.domain.AnalyticsDaily;
import com.smartdock.analytics.domain.AnalyticsFunnel;
import com.smartdock.analytics.domain.AnalyticsSession;
import com.smartdock.analytics.domain.AnalyticsSite;
import com.smartdock.analytics.mapper.AnalyticsDailyMapper;
import com.smartdock.analytics.mapper.AnalyticsEventMapper;
import com.smartdock.analytics.mapper.AnalyticsFunnelMapper;
import com.smartdock.analytics.mapper.AnalyticsSessionMapper;
import com.smartdock.analytics.mapper.AnalyticsSiteMapper;
import com.smartdock.analytics.query.AnalyticsBreakdownRow;
import com.smartdock.analytics.query.AnalyticsCustomBo;
import com.smartdock.analytics.query.AnalyticsEventRow;
import com.smartdock.analytics.query.AnalyticsFunnelBo;
import com.smartdock.analytics.query.AnalyticsImportBo;
import com.smartdock.analytics.query.AnalyticsSessionQuery;
import com.smartdock.profile.ProfileEventPublisher;
import com.smartdock.profile.event.ProfileEventBo;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

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
    private final AnalyticsSiteMapper siteMapper;
    private final com.smartdock.analytics.mapper.AnalyticsCollectLogMapper collectLogMapper;
    private final AnalyticsSessionizer sessionizer;
    /** ObjectProvider 打破 AnalyticsService → 事件发布器 → 自动汇总触发器 的构造环 */
    private final ObjectProvider<ProfileEventPublisher> eventPublisherProvider;

    /**
     * 事务模板: 锁必须包住事务的提交边界。@Transactional 代理是"先开事务再进方法体拿锁",
     * unlock 早于 commit, 两个重建仍可能在提交阶段撞唯一键; 显式模板让 lock() 在 BEGIN 之前。
     */
    private final org.springframework.transaction.support.TransactionTemplate txTemplate;

    /**
     * 同一 (租户,业务源) 的重建互斥: 手动重建与自动汇总并发时会互相删插对方的会话。
     * 单 JVM 锁; 多实例分布式锁在二期批 10。
     */
    private final Map<String, ReentrantLock> rebuildLocks = new ConcurrentHashMap<>();

    private ReentrantLock rebuildLock(String tenantId, String source) {
        return rebuildLocks.computeIfAbsent(tenantId + "|" + source, k -> new ReentrantLock());
    }

    // ---------------------------------------------------------------- 重建 / 汇总

    public Map<String, Object> rebuild(String source, int days) {
        requireSource(source);
        int clampedDays = Math.min(Math.max(days, 1), 365);
        String tenantId = currentTenant();
        ReentrantLock lock = rebuildLock(tenantId, source);
        lock.lock();
        try {
            return txTemplate.execute(status -> doRebuild(tenantId, source, clampedDays));
        } finally {
            lock.unlock();
        }
    }

    private Map<String, Object> doRebuild(String tenantId, String source, int days) {
        Date from = startOfDaysAgo(days);
        Date to = startOfTomorrow();
        int sessions = sessionizer.rebuild(tenantId, source, from, to);
        int dailyRows = rollup(tenantId, source, from, to);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("days", days);
        out.put("sessions", sessions);
        out.put("dailyRows", dailyRows);
        out.put("from", from);
        out.put("to", to);
        return out;
    }

    /**
     * 事件写入后重建最近两天：会话与日汇总一起刷新。
     *
     * @return 是否真正执行; false = 手动重建持锁中被跳过, 调用方应保留脏标记等下一个事件补
     */
    public boolean rebuildRecent(String tenantId, String source) {
        ReentrantLock lock = rebuildLock(tenantId, source);
        if (!lock.tryLock()) {
            return false;
        }
        try {
            txTemplate.executeWithoutResult(status -> {
                Date from = startOfDaysAgo(2);
                Date to = startOfTomorrow();
                sessionizer.rebuild(tenantId, source, from, to);
                rollup(tenantId, source, from, to);
            });
            return true;
        } finally {
            lock.unlock();
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
        return sessionMapper.selectSessionPage(page, currentTenant(), query.getSource(),
            range[0], range[1], query);
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
            Map<String, Object> context = parse(event.getContext());
            Map<String, Object> props = parse(event.getProps());
            item.put("pageUrl", firstString(context, "pageUrl", "url"));
            item.put("title", firstString(context, "title", "pageTitle"));
            item.put("referrer", firstString(context, "referrer", "referer"));
            item.put("props", props);
            item.put("context", context);
            timeline.add(item);
        }
        Map<String, Object> out = new LinkedHashMap<>();
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
        List<AnalyticsEventRow> events = eventMapper.selectEvents(currentTenant(), source, from, to);
        List<String> names = new ArrayList<>();
        for (Map<String, Object> step : steps) {
            names.add(String.valueOf(step.get("eventName")));
        }
        Map<String, List<AnalyticsEventRow>> byVisitor = new LinkedHashMap<>();
        for (AnalyticsEventRow event : events) {
            if (names.contains(event.getEventName())) {
                byVisitor.computeIfAbsent(event.getVisitorKey(), key -> new ArrayList<>()).add(event);
            }
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

    public List<AnalyticsSite> sites() {
        return siteMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AnalyticsSite>()
            .eq(AnalyticsSite::getTenantId, currentTenant())
            .eq(AnalyticsSite::getEnabled, "1")
            .orderByAsc(AnalyticsSite::getSiteName));
    }

    /**
     * 批7 采集质量报表: 丢弃原因分布 / Top 机器人 UA / Top 跨域来源 / 最近留痕 / 丢弃率。
     * 丢弃率分母 = 窗口内已入账事件数 + 丢弃事件数(来自日汇总, 与口径六数字同源)。
     */
    public Map<String, Object> quality(String source, int days) {
        requireSource(source);
        int clampedDays = Math.min(Math.max(days, 1), 90);
        String tenantId = currentTenant();
        Date from = startOfDaysAgo(clampedDays);
        Date to = startOfTomorrow();

        List<Map<String, Object>> byReason = collectLogMapper.summaryByReason(tenantId, source, from);
        long droppedEvents = 0;
        for (Map<String, Object> row : byReason) {
            droppedEvents += longValue(row.get("events"));
        }
        // 已入账事件数直接从日汇总行求和(≤90 行, 无需新 SQL)
        long acceptedEvents = 0;
        for (Map<String, Object> row : dailyMapper.selectTrend(tenantId, source,
            new java.sql.Date(from.getTime()), new java.sql.Date(to.getTime()))) {
            acceptedEvents += longValue(row.get("events"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("source", source);
        out.put("days", clampedDays);
        out.put("from", from);
        out.put("to", to);
        out.put("byReason", byReason);
        out.put("topRobots", collectLogMapper.topRobots(tenantId, source, from, 10));
        out.put("topMismatches", collectLogMapper.topMismatches(tenantId, source, from, 10));
        out.put("latestLogs", collectLogMapper.latestLogs(tenantId, source, from, 10));
        out.put("acceptedEvents", acceptedEvents);
        out.put("droppedEvents", droppedEvents);
        out.put("discardRate", rate(droppedEvents, acceptedEvents + droppedEvents));
        return out;
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
        return "000000";
    }
}
