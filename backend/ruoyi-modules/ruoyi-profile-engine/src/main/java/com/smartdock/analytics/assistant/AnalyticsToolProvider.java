package com.smartdock.analytics.assistant;

import com.smartdock.analytics.domain.AnalyticsFunnel;
import com.smartdock.analytics.engine.AnalyticsService;
import com.smartdock.analytics.query.AnalyticsCustomBo;
import com.smartdock.analytics.query.AnalyticsFunnelBo;
import com.smartdock.assistant.spi.BizParamSpec;
import com.smartdock.assistant.spi.BizToolCall;
import com.smartdock.assistant.spi.BizToolProvider;
import com.smartdock.assistant.spi.BizToolResult;
import com.smartdock.assistant.spi.BizToolSpec;
import com.smartdock.profile.event.ProfileSources;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * 分析中心喂给 AI 助手的只读工具（「问数据」批·题1甲/题2A）：六个工具覆盖六类高频问题——
 * 总览 / 趋势 / 漏斗 / 留存 / 页面路径 / 自定义组查（白名单表单，题3 C′）。
 *
 * <p><b>权限口径（题5A，与 UI 完全同源）</b>: 注册门槛 = {@code analytics:center:query}
 * （与分析中心页面同串），没有该菜单的人模型看不见这些工具; 全部 readOnly=true 只读直行。</p>
 *
 * <p><b>口径护栏（题3A，只报数不算数）</b>: 所有算术（环比 delta / 漏斗转化率 / 留存占比）
 * 均由分析中心既有接口算好返回，工具与模型只转述; 每个工具返回带 {@code sourceNote} 口径脚注
 * 与 {@code chart} 现成图表协议（模型被要求整段照抄，不在图里自己编数）。
 * 自定义组查只收白名单枚举（11 维度 × 4 指标），越权组查直接人话拒绝。</p>
 *
 * <p><b>租户与超时（SPI 纪律，与 ProfileToolProvider 同款）</b>: 查询全部包在
 * {@code TenantHelper.dynamic(tenantId, ...)}（工具线程无租户上下文）; execute 自带 8s 超时。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsToolProvider implements BizToolProvider {

    /** 与分析中心页面同一张通行证 */
    static final String PERM_QUERY = "analytics:center:query";

    static final String TOOL_OVERVIEW = "analytics_overview";
    static final String TOOL_TREND = "analytics_trend";
    static final String TOOL_FUNNEL = "analytics_funnel";
    static final String TOOL_RETENTION = "analytics_retention";
    static final String TOOL_PATHS = "analytics_paths";
    static final String TOOL_CUSTOM = "analytics_custom";

    private static final long TIMEOUT_MS = 8_000L;

    /** 自定义组查白名单（题3 C′）：与分析中心 AnalyticsCustomBo 注释同源，越权维度一律拒绝 */
    static final Set<String> CUSTOM_DIMENSIONS = Set.of(
        "day", "page", "channel", "device", "browser", "os", "geo", "entry", "exit", "search", "event");
    static final Set<String> CUSTOM_METRICS = Set.of("pv", "uv", "visits", "events");

    /** 口径护栏句：拼进每个工具描述，模型层面双保险（与 chart.md 硬规则同口径） */
    static final String GUARDRAIL = "硬规矩：数字一律原样转述，禁止自己对工具数字做算术（环比/占比/差值用返回里算好的字段）；"
        + "返回里有 chart 字段时，把它的 JSON 用 ```chart 围栏整段照抄进回答，不要自己改数拼图。";

    private final AnalyticsService analyticsService;

    @Override
    public String namespace() {
        return "analytics";
    }

    @Override
    public List<BizToolSpec> tools() {
        List<BizToolSpec> out = new ArrayList<>();

        BizToolSpec overview = BizToolSpec.readOnly(TOOL_OVERVIEW, "数据总览",
            "回答「最近 N 天用得怎么样」类总量问题：浏览量/访客数/访问次数/事件数/新访客/单页离开率，"
                + "以及对比上一个同等窗口的环比（delta 已由后端算好）。\n"
                + "- days 缺省 7; 问「上周/最近一周」传 7, 问「这个月」传 30;\n"
                + "- 活跃人数（去重）优先看本工具的 uv 与 wau 字段，不要拿每日数字自己加总。\n" + GUARDRAIL);
        overview.setMinRoleLevel(0);
        overview.setRequiredPermission(PERM_QUERY);
        overview.setParams(List.of(
            BizParamSpec.number("days", "统计窗口天数, 缺省 7, 上限 90", false)
        ));
        out.add(overview);

        BizToolSpec trend = BizToolSpec.readOnly(TOOL_TREND, "趋势",
            "回答「最近走势怎么样」类问题：按天的浏览量/访客数/访问次数/事件数/新访客序列。\n"
                + "- metric 缺省 uv（去重访客，最接近「活跃人数」）; 问「趋势/变化/画条线」用它;\n"
                + "- 序列含今天（今天是不完整的一天，属正常现象，照实说明即可）;\n"
                + "- 环比类问题（比上周多了多少）用「数据总览」，它带算好的 delta。\n" + GUARDRAIL);
        trend.setMinRoleLevel(0);
        trend.setRequiredPermission(PERM_QUERY);
        trend.setParams(List.of(
            BizParamSpec.number("days", "天数, 缺省 30, 上限 365", false),
            BizParamSpec.string("metric", "指标: uv(去重访客)/pv(浏览量)/visits(访问次数)/events(事件数), 缺省 uv", false)
        ));
        out.add(trend);

        BizToolSpec funnel = BizToolSpec.readOnly(TOOL_FUNNEL, "漏斗",
            "回答「哪一步掉的人最多」类问题：按保存过的漏斗（一组按顺序的事件步骤）统计每步还剩多少人、"
                + "每步转化率（后端算好）。\n"
                + "- funnel 传漏斗名称或 ID; 不知道有哪些漏斗时传空串, 会返回漏斗清单;\n"
                + "- 问「流失到哪去了」可让用户到分析中心页看下钻, 本工具只给到步骤级。\n" + GUARDRAIL);
        funnel.setMinRoleLevel(0);
        funnel.setRequiredPermission(PERM_QUERY);
        funnel.setParams(List.of(
            BizParamSpec.string("funnel", "漏斗名称或 ID; 空串=列出已保存的漏斗", false),
            BizParamSpec.number("days", "统计窗口天数, 缺省 30, 上限 365", false)
        ));
        out.add(funnel);

        BizToolSpec retention = BizToolSpec.readOnly(TOOL_RETENTION, "留存",
            "回答「来过的人后面还来吗」类问题：按首访日分组（同期群），看第 N 天/周/月还有多少人活跃。\n"
                + "- cells 里 day=第几个周期, visitors=仍活跃人数, 占比后端算好;\n"
                + "- granularity: day/week/month, 缺省 day。\n" + GUARDRAIL);
        retention.setMinRoleLevel(0);
        retention.setRequiredPermission(PERM_QUERY);
        retention.setParams(List.of(
            BizParamSpec.number("days", "拉多少天的首访 cohort, 缺省 14, 上限 365", false),
            BizParamSpec.number("window", "看几个周期, 缺省 7", false),
            BizParamSpec.string("granularity", "周期粒度: day/week/month, 缺省 day", false)
        ));
        out.add(retention);

        BizToolSpec paths = BizToolSpec.readOnly(TOOL_PATHS, "页面路径",
            "回答「大家都在哪些页面之间流动」类问题：页面流向图（从哪页到哪页，宽窄代表人数）。\n"
                + "- 该数据依赖页面浏览事件；时间窗内没有页面事件时返回空并如实说明，不要硬编;\n"
                + "- start 可传某个页面路径, 只看从该页出发的流向。\n" + GUARDRAIL);
        paths.setMinRoleLevel(0);
        paths.setRequiredPermission(PERM_QUERY);
        paths.setParams(List.of(
            BizParamSpec.number("days", "天数, 缺省 30, 上限 90", false),
            BizParamSpec.string("start", "起始页面路径(可空=看入口整体)", false)
        ));
        out.add(paths);

        BizToolSpec custom = BizToolSpec.readOnly(TOOL_CUSTOM, "自定义组查",
            "自己组一个「维度 × 指标 × 时间窗」的问题（受控白名单，不是随便查库）：\n"
                + "- 维度 dimension 只能选: " + String.join("/", CUSTOM_DIMENSIONS) + ";\n"
                + "- 指标 metric 只能选: " + String.join("/", CUSTOM_METRICS) + ";\n"
                + "- 例：「按渠道看最近两周的访问次数」= dimension=channel, metric=visits, days=14。\n" + GUARDRAIL);
        custom.setMinRoleLevel(0);
        custom.setRequiredPermission(PERM_QUERY);
        custom.setParams(List.of(
            BizParamSpec.string("dimension", "维度: " + String.join("/", CUSTOM_DIMENSIONS) + ", 缺省 day", false),
            BizParamSpec.string("metric", "指标: " + String.join("/", CUSTOM_METRICS) + ", 缺省 pv", false),
            BizParamSpec.number("days", "天数, 缺省 30, 上限 365", false),
            BizParamSpec.number("limit", "返回条数, 缺省 20, 上限 100", false)
        ));
        out.add(custom);

        return out;
    }

    @Override
    public BizToolResult execute(BizToolCall call) {
        // 空租户 fail-closed（SPI 纪律，与 ProfileToolProvider 同款：插件见空租户不注入租户条件）
        if (call.getTenantId() == null || call.getTenantId().isBlank()) {
            return BizToolResult.fail("缺少租户上下文, 无法执行分析工具");
        }
        CompletableFuture<BizToolResult> future = null;
        try {
            future = CompletableFuture.supplyAsync(() ->
                    org.dromara.common.tenant.helper.TenantHelper.dynamic(call.getTenantId(),
                        () -> dispatch(call)), TOOL_POOL);
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 审查席实证：cancel(false) 对已开始执行的查询是 no-op（JDK 语义）——超时后查询仍会跑完，
            // 但六工具全只读无落库副作用，结果被丢弃即可，不存在写脏；排队中的任务会被作废。
            if (future != null) {
                future.cancel(false);
            }
            log.warn("[AnalyticsTool] timeout tool={} tenant={}", call.getToolName(), call.getTenantId());
            return BizToolResult.fail("查询超时(" + TIMEOUT_MS + "ms), 建议重试一次");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return BizToolResult.fail("查询被中断, 请重试");
        } catch (java.util.concurrent.RejectedExecutionException e) {
            return BizToolResult.fail("分析查询排队已满, 请稍后再试");
        } catch (Exception e) {
            Throwable cause = unwrap(e);
            if (cause instanceof ServiceException se) {
                return BizToolResult.fail(se.getMessage());
            }
            log.warn("[AnalyticsTool] {} failed: {}", call.getToolName(), cause);
            return BizToolResult.fail("查询失败(系统异常), 请稍后再试");
        }
    }

    private BizToolResult dispatch(BizToolCall call) {
        return switch (call.getToolName()) {
            case TOOL_OVERVIEW -> overview(call);
            case TOOL_TREND -> trend(call);
            case TOOL_FUNNEL -> funnel(call);
            case TOOL_RETENTION -> retention(call);
            case TOOL_PATHS -> paths(call);
            case TOOL_CUSTOM -> custom(call);
            default -> BizToolResult.fail("未知工具: " + call.getToolName());
        };
    }

    // ---------------------------------------------------------------- 数据总览

    private BizToolResult overview(BizToolCall call) {
        Map<String, Object> args = call.getArgs();
        int days = clampDays(intArg(args, "days"), 7, 90);
        String source = source(call);
        Map<String, Object> ov = analyticsService.overview(source, days);
        Map<String, Object> activity = analyticsService.activity(source, days);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", days);
        data.put("overview", ov);
        data.put("activityLatest", activity.get("latest"));
        data.put("sourceNote", "口径：近 " + days + " 天对比前 " + days + " 天（delta 已算好）；"
            + "uv=去重访客数；单页离开率=只看一页就走的会话占比。来自分析中心总览/活跃接口。");
        data.put("hint", "数字不多时直接正文说结论，不必给图");
        return BizToolResult.ok(data);
    }

    // ---------------------------------------------------------------- 趋势

    private BizToolResult trend(BizToolCall call) {
        Map<String, Object> args = call.getArgs();
        int days = clampDays(intArg(args, "days"), 30, 365);
        String metric = normalizeEnum(trimToNull(args, "metric"), CUSTOM_METRICS, "uv");
        if (metric == null) {
            return BizToolResult.fail("metric 只支持 pv / uv / visits / events");
        }
        String source = source(call);
        List<Map<String, Object>> rows = analyticsService.trend(source, days);
        List<Map<String, Object>> outRows = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("date", String.valueOf(row.get("statDate")));
            out.put("uv", row.get("uv"));
            out.put("pv", row.get("pv"));
            out.put("visits", row.get("visits"));
            out.put("events", row.get("events"));
            out.put("newVisitors", row.get("newVisitors"));
            outRows.add(out);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("metric", metric);
        data.put("days", days);
        // 合计/日均后端预计算（口径护栏：活体验收实测模型自算 685÷6 给出 137 的错数——
        // 派生指标一律后端算好，模型只引用不计算）。基于全区间算完再截明细。
        Map<String, Object> agg = aggregate(outRows, metric);
        // 审查席 P1：uv 是「当日去重」，跨天相加=人次不是窗口去重人数——sum 对 uv 无意义，
        // 置 null 并指向总览的去重口径（avgPerDay 日均有意义，保留）。
        // 全 0 判断先取数（置 null 前的 sum）再对外隐藏。
        long chartSum = numberOrZero(agg.get("sum"));
        if ("uv".equals(metric)) {
            agg.put("sum", null);
            agg.put("sumNote", "uv 跨天不可加总（同一人每天来会重复计）；全窗口去重人数看 analytics_overview 返回的 uv。");
        }
        // 审查席 P2：rows 超 60 行截断（sum/avgPerDay 仍覆盖全区间，明细只留近段）
        boolean truncated = outRows.size() > TREND_MAX_ROWS;
        if (truncated) {
            outRows = new ArrayList<>(outRows.subList(outRows.size() - TREND_MAX_ROWS, outRows.size()));
            data.put("hint", "明细只保留最近 " + TREND_MAX_ROWS + " 天（合计/日均覆盖全区间）；完整明细到分析中心页看。");
        }
        data.put("total", outRows.size());
        if (agg.get("sum") != null) {
            data.put("sum", agg.get("sum"));
        }
        if (agg.get("sumNote") != null) {
            data.put("sumNote", agg.get("sumNote"));
        }
        data.put("avgPerDay", agg.get("avgPerDay"));
        data.put("rows", outRows);
        data.put("truncated", truncated);
        data.put("asOf", asOfNow());
        // 审查席 P1：全 0 序列不下发 chart（前端校验器会拒并顶红字错误行）——只给文字结论
        if (chartSum > 0) {
            data.put("chart", buildTrendChart(outRows, metric, CHART_MAX_POINTS));
        } else {
            data.put("hint", "这段日期内该指标全是 0，画出来只是贴底直线，没有信息量；请如实告诉用户，不要画图。");
        }
        data.put("sourceNote", "口径：按日汇总，uv=当日去重访客；序列含今天（不完整的一天）。"
            + ("uv".equals(metric)
                ? "日均 avgPerDay 已由后端算好直接引用；uv 跨天不加总（见 sumNote），全窗口去重人数看数据总览。"
                : "合计 sum 与日均 avgPerDay 已由后端算好，直接引用，不要自己加总或相除。")
            + "来自分析中心趋势接口。");
        return BizToolResult.ok(data);
    }

    /** 查询时刻（前端 DataCard 头行「数据截止」用；显式 +8 时区，不随部署容器漂移——审查席 P2） */
    static String asOfNow() {
        return java.time.LocalDateTime.now(java.time.ZoneOffset.ofHours(8))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    // ---------------------------------------------------------------- 漏斗

    private BizToolResult funnel(BizToolCall call) {
        Map<String, Object> args = call.getArgs();
        String funnelArg = trimToNull(args, "funnel");
        int days = clampDays(intArg(args, "days"), 30, 365);
        String source = source(call);
        List<AnalyticsFunnel> all = analyticsService.funnels(source);
        if (funnelArg == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("funnels", funnelSummaries(all));
            data.put("asOf", asOfNow());
            data.put("sourceNote", "口径：列出的是当前租户保存过的漏斗清单；传 funnel(名称或 ID)看具体转化。来自分析中心漏斗接口。");
            if (all.isEmpty()) {
                data.put("hint", "这个租户还没有保存过漏斗; 到分析中心页建一个, 或直接告诉用户暂无漏斗数据");
            } else {
                data.put("hint", "传 funnel(名称或 ID)看具体漏斗");
            }
            return BizToolResult.ok(data);
        }
        AnalyticsFunnel target = resolveFunnel(all, funnelArg);
        if (target == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("found", false);
            data.put("message", "没有叫「" + funnelArg + "」的漏斗; 现有漏斗如下");
            data.put("funnels", funnelSummaries(all));
            data.put("asOf", asOfNow());
            data.put("sourceNote", "口径：漏斗按名称或 ID 精确匹配当前租户保存的漏斗。来自分析中心漏斗接口。");
            return BizToolResult.ok(data);
        }
        AnalyticsFunnelBo bo = new AnalyticsFunnelBo();
        bo.setFunnelId(target.getFunnelId());
        bo.setDays(days);
        Map<String, Object> res = analyticsService.funnelAnalysis(bo);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> steps = (List<Map<String, Object>>) res.get("steps");
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("funnel", target.getFunnelName());
        data.put("days", res.get("days"));
        data.put("windowDays", res.get("windowDays"));
        data.put("visitors", res.get("visitors"));
        data.put("overallRate", res.get("overallRate"));
        data.put("total", steps == null ? 0 : steps.size());
        data.put("rows", steps);
        data.put("truncated", false);
        data.put("asOf", asOfNow());
        // 审查席 P1：空 steps / 首步 0 人不下发 chart（前端会拒并顶红字行）
        long firstCount = steps == null || steps.isEmpty() ? 0 : numberOrZero(steps.get(0).get("count"));
        if (steps != null && !steps.isEmpty() && firstCount > 0) {
            data.put("chart", buildFunnelChart(steps));
        } else {
            data.put("hint", "漏斗没有任何访客走完第一步，画图没有意义；请如实告诉用户这个窗口内无人进入漏斗。");
        }
        data.put("sourceNote", "口径：访客在 " + res.get("windowDays") + " 天窗口内按顺序走完各步才算通过；"
            + "整体转化率=最后一步人数/第一步人数（后端算好）。来自分析中心漏斗接口。");
        return BizToolResult.ok(data);
    }

    // ---------------------------------------------------------------- 留存

    private BizToolResult retention(BizToolCall call) {
        Map<String, Object> args = call.getArgs();
        int days = clampDays(intArg(args, "days"), 14, 365);
        int window = clampDays(intArg(args, "window"), 7, 30);
        String gran = normalizeEnum(trimToNull(args, "granularity"), Set.of("day", "week", "month"), "day");
        if (gran == null) {
            return BizToolResult.fail("granularity 只支持 day / week / month");
        }
        String source = source(call);
        Map<String, Object> res = analyticsService.retention(source, days, window, gran, false);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", res.get("days"));
        data.put("window", res.get("window"));
        data.put("granularity", gran);
        data.put("asOf", asOfNow());
        Object cohorts = res.get("cohorts");
        boolean hasCohorts = cohorts instanceof List<?> lc && !lc.isEmpty();
        if (hasCohorts) {
            // 审查席 P2：cohorts 与 chart 同封顶（days=365 时全量 ≈ 十万级字符灌模型上下文），只回最近 N 群
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> allCohorts = (List<Map<String, Object>>) cohorts;
            boolean truncated = allCohorts.size() > RETENTION_MAX_COHORTS;
            data.put("cohorts", truncated
                ? new ArrayList<>(allCohorts.subList(allCohorts.size() - RETENTION_MAX_COHORTS, allCohorts.size()))
                : allCohorts);
            data.put("truncated", truncated);
            if (truncated) {
                data.put("hint", "同期群只保留最近 " + RETENTION_MAX_COHORTS + " 个；要看更早的到分析中心页查。");
            }
            data.put("chart", buildRetentionChart(gran, res));
        } else {
            data.put("hint", "这段日期内没有新访客同期群，留存表是空的；如实告诉用户，不要画图。");
        }
        data.put("sourceNote", "口径：同期群=按首访日分组；格子=第 N 个" + ("day".equals(gran) ? "天" : ("week".equals(gran) ? "周" : "月"))
            + "仍活跃的人数；占比已由后端算好。来自分析中心留存接口。");
        return BizToolResult.ok(data);
    }

    // ---------------------------------------------------------------- 页面路径

    private BizToolResult paths(BizToolCall call) {
        Map<String, Object> args = call.getArgs();
        int days = clampDays(intArg(args, "days"), 30, 90);
        String start = trimToNull(args, "start");
        String source = source(call);
        Map<String, Object> res = analyticsService.paths(source, days, start);
        Object nodes = res.get("nodes");
        Object links = res.get("links");
        // 审查席 P1：nodes 非空但 links 全空（每个访客只看一页/长尾裁剪把连线端点全裁掉）同样画不出图
        boolean empty = !(nodes instanceof List<?> l1 && !l1.isEmpty())
            || !(links instanceof List<?> l2 && !l2.isEmpty());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("days", res.get("days"));
        data.put("start", res.get("start"));
        data.put("visitors", res.get("visitors"));
        data.put("nodes", nodes);
        data.put("links", links);
        data.put("entries", res.get("entries"));
        data.put("asOf", asOfNow());
        if (empty) {
            data.put("hint", "该时间窗内没有页面浏览事件（或没人看过第二个页面）, 画不出流向图; 如实告诉用户, 不要编造页面");
        } else {
            data.put("chart", buildSankeyChart(res));
        }
        data.put("sourceNote", "口径：页面浏览事件按访客时间顺序连线；服务端只保留流量最大的页面。来自分析中心路径接口。");
        return BizToolResult.ok(data);
    }

    // ---------------------------------------------------------------- 自定义组查（题3 C′ 白名单）

    /** 维度/指标中文口径（零黑话：sourceNote 与图表标题用户可见，英文枚举直出会被审查席打回） */
    static final Map<String, String> DIMENSION_CN = Map.ofEntries(
        java.util.Map.entry("day", "日期"), java.util.Map.entry("page", "页面"),
        java.util.Map.entry("channel", "渠道"), java.util.Map.entry("device", "设备"),
        java.util.Map.entry("browser", "浏览器"), java.util.Map.entry("os", "操作系统"),
        java.util.Map.entry("geo", "地区"), java.util.Map.entry("entry", "入口页"),
        java.util.Map.entry("exit", "出口页"), java.util.Map.entry("search", "搜索词"),
        java.util.Map.entry("event", "事件名"));
    static final Map<String, String> METRIC_CN = Map.of(
        "pv", "浏览量", "uv", "活跃人数", "visits", "访问次数", "events", "事件数");

    private BizToolResult custom(BizToolCall call) {
        Map<String, Object> args = call.getArgs();
        String dimension = normalizeEnum(trimToNull(args, "dimension"), CUSTOM_DIMENSIONS, "day");
        if (dimension == null) {
            return BizToolResult.fail("dimension 只支持: " + String.join(" / ", CUSTOM_DIMENSIONS));
        }
        String metric = normalizeEnum(trimToNull(args, "metric"), CUSTOM_METRICS, "pv");
        if (metric == null) {
            return BizToolResult.fail("metric 只支持: " + String.join(" / ", CUSTOM_METRICS));
        }
        int days = clampDays(intArg(args, "days"), 30, 365);
        int limit = clampDays(intArg(args, "limit"), 20, 100);
        AnalyticsCustomBo bo = new AnalyticsCustomBo();
        bo.setSource(source(call));
        bo.setDimension(dimension);
        bo.setMetric(metric);
        bo.setDays(days);
        bo.setLimit(limit);
        Map<String, Object> res = analyticsService.custom(bo);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> points = (List<Map<String, Object>>) res.get("points");
        List<Map<String, Object>> rows = new ArrayList<>();
        if (points != null) {
            for (Map<String, Object> p : points) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("label", p.get("label"));
                row.put("value", p.get("value"));
                rows.add(row);
            }
        }
        // 审查席 P1+P2：limit 真截断且方向分维度——day 升序序列保留近段（与 trend 60 行同口径），
        // 其余维度服务端已按指标量排序，保留头部 TopN；truncated 用 >= 保守真值
        // （非 day 维度服务端在 SQL 层 LIMIT，返回行数==limit 即大概率被截，明说防「一共就 20 个页面」）
        boolean truncated = rows.size() >= limit;
        if (rows.size() > limit) {
            int from = "day".equals(dimension) ? rows.size() - limit : 0;
            rows = new ArrayList<>(rows.subList(from, from + limit));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dimension", dimension);
        data.put("metric", metric);
        data.put("days", days);
        data.put("total", rows.size());
        data.put("rows", rows);
        data.put("truncated", truncated);
        data.put("asOf", asOfNow());
        long sum = 0;
        for (Map<String, Object> r : rows) {
            sum += numberOrZero(r.get("value"));
        }
        if (sum > 0) {
            data.put("chart", buildCustomChart(dimension, rows, CHART_MAX_POINTS));
        } else {
            data.put("hint", "该维度该指标在这段日期内全是 0 或没有数据；如实告诉用户，不要画图。");
        }
        data.put("sourceNote", "口径：按" + DIMENSION_CN.getOrDefault(dimension, dimension) + "统计"
            + METRIC_CN.getOrDefault(metric, metric) + "，近 " + days + " 天（白名单组查, 只走分析中心自定义分析接口）。"
            + (truncated && !"day".equals(dimension) ? "只列按量排序的前 " + limit + " 项，不代表全部。" : ""));
        return BizToolResult.ok(data);
    }

    // ---------------------------------------------------------------- 纯函数（单测锚点）

    /** 图表协议单图数据点上限（chart.md 教学同款 12） */
    static final int CHART_MAX_POINTS = 12;

    /** 留存图同期群上限（与前端 RETENTION_MAX_COHORTS 对齐） */
    static final int RETENTION_MAX_COHORTS = 14;

    /** 趋势明细行上限（超此截断置 truncated；sum/avgPerDay 仍全区间） */
    static final int TREND_MAX_ROWS = 60;

    /** 趋势聚合（sum/avgPerDay/覆盖天数）——口径护栏的真身：派生指标唯一算术发生地，单测直调钉死 */
    static Map<String, Object> aggregate(List<Map<String, Object>> rows, String metric) {
        long sum = 0;
        for (Map<String, Object> r : rows) {
            sum += numberOrZero(r.get(metric));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sum", sum);
        out.put("avgPerDay", rows.isEmpty() ? 0 : Math.round(sum * 10.0 / rows.size()) / 10.0);
        out.put("days", rows.size());
        return out;
    }

    /** limit/days 归一: 空/非法取缺省, 越界夹到 [1, max] */
    static int clampDays(Integer raw, int def, int max) {
        if (raw == null) {
            return def;
        }
        return Math.min(Math.max(raw, 1), max);
    }

    /** 枚举白名单归一: 空/空白→缺省; 小写后命中→规范值; 未命中→null(调用方人话拒绝) */
    static String normalizeEnum(String raw, Set<String> allowed, String def) {
        if (raw == null || raw.isBlank()) {
            return def;
        }
        String key = raw.trim().toLowerCase();
        return allowed.contains(key) ? key : null;
    }

    /** 趋势图协议: 取最后 N 点的折线（chart 字段照抄进 ```chart 围栏） */
    static Map<String, Object> buildTrendChart(List<Map<String, Object>> rows, String metric, int lastN) {
        List<Map<String, Object>> data = new ArrayList<>();
        int from = Math.max(0, rows.size() - lastN);
        for (int i = from; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("name", shortDate(String.valueOf(row.get("date"))));
            point.put("value", numberOrZero(row.get(metric)));
            data.add(point);
        }
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("type", "line");
        chart.put("title", "近 " + data.size() + " 天" + metricLabel(metric));
        chart.put("data", data);
        return chart;
    }

    /** 漏斗图协议: 步骤名+人数（值天然不增） */
    static Map<String, Object> buildFunnelChart(List<Map<String, Object>> steps) {
        List<Map<String, Object>> data = new ArrayList<>();
        if (steps != null) {
            for (Map<String, Object> step : steps) {
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("name", String.valueOf(step.get("name")));
                point.put("value", numberOrZero(step.get("count")));
                data.add(point);
            }
        }
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("type", "funnel");
        chart.put("title", "漏斗各步人数");
        chart.put("data", data);
        return chart;
    }

    /** 留存格图协议: cohort/total/cells(day, visitors) 白名单字段 + 最近 N 个同期群截断
     *  （审查席 P1：days=365 时全量 cohort ≈ 万格灌给模型照抄必截断；N 与前端 RETENTION_MAX_COHORTS 对齐） */
    static Map<String, Object> buildRetentionChart(String granularity, Map<String, Object> retentionResult) {
        List<Map<String, Object>> cohortsOut = new ArrayList<>();
        Object cohorts = retentionResult.get("cohorts");
        if (cohorts instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> cohort)) {
                    continue;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("cohort", String.valueOf(cohort.get("cohort")));
                out.put("total", numberOrZero(cohort.get("total")));
                List<Map<String, Object>> cellsOut = new ArrayList<>();
                Object cells = cohort.get("cells");
                if (cells instanceof List<?> cellList) {
                    for (Object c : cellList) {
                        if (!(c instanceof Map<?, ?> cell)) {
                            continue;
                        }
                        Map<String, Object> co = new LinkedHashMap<>();
                        co.put("day", numberOrZero(cell.get("day")));
                        co.put("visitors", numberOrZero(cell.get("visitors")));
                        cellsOut.add(co);
                    }
                }
                out.put("cells", cellsOut);
                cohortsOut.add(out);
            }
        }
        // 只取最近 14 个同期群（前端同款上限；完整序列在 cohorts 字段里）
        if (cohortsOut.size() > RETENTION_MAX_COHORTS) {
            cohortsOut = new ArrayList<>(cohortsOut.subList(cohortsOut.size() - RETENTION_MAX_COHORTS, cohortsOut.size()));
        }
        String unitCn = "day".equals(granularity) ? "按天" : ("week".equals(granularity) ? "按周" : "按月");
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("type", "retention");
        // 窗口数取服务端实际值（服务端会按粒度压档，用入参会两个数打架——审查席 P2）
        chart.put("title", "留存（" + unitCn + " · 窗口 " + retentionResult.get("window") + "）");
        chart.put("unit", unitCn);
        chart.put("cohorts", cohortsOut);
        return chart;
    }

    /** 页面流向图协议: 桑基 nodes/links 白名单字段 */
    static Map<String, Object> buildSankeyChart(Map<String, Object> pathsResult) {
        List<Map<String, Object>> nodesOut = new ArrayList<>();
        Object nodes = pathsResult.get("nodes");
        if (nodes instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> n && n.get("name") != null) {
                    nodesOut.add(Map.of("name", String.valueOf(n.get("name"))));
                }
            }
        }
        List<Map<String, Object>> linksOut = new ArrayList<>();
        Object links = pathsResult.get("links");
        if (links instanceof List<?> list) {
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> l)) {
                    continue;
                }
                if (l.get("source") == null || l.get("target") == null) {
                    continue;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("source", String.valueOf(l.get("source")));
                out.put("target", String.valueOf(l.get("target")));
                out.put("value", numberOrZero(l.get("value")));
                linksOut.add(out);
            }
        }
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("type", "sankey");
        chart.put("title", "页面流向（宽窄=人数）");
        chart.put("nodes", nodesOut);
        chart.put("links", linksOut);
        return chart;
    }

    /** 自定义组查图协议: day 维度走折线（取最后 N 点）, 其余走横向对比柱（取前 N 项, 服务端已排序） */
    static Map<String, Object> buildCustomChart(String dimension, List<Map<String, Object>> rows, int maxPoints) {
        boolean asLine = "day".equals(dimension);
        List<Map<String, Object>> data = new ArrayList<>();
        if (asLine) {
            int from = Math.max(0, rows.size() - maxPoints);
            for (int i = from; i < rows.size(); i++) {
                data.add(point(rows.get(i)));
            }
        } else {
            for (int i = 0; i < Math.min(rows.size(), maxPoints); i++) {
                data.add(point(rows.get(i)));
            }
        }
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("type", asLine ? "line" : "bar");
        chart.put("title", "按" + DIMENSION_CN.getOrDefault(dimension, dimension) + "汇总");
        chart.put("data", data);
        return chart;
    }

    private static Map<String, Object> point(Map<String, Object> row) {
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("name", String.valueOf(row.get("label")));
        point.put("value", numberOrZero(row.get("value")));
        return point;
    }

    /** 漏斗解析: 纯数字先按 ID 再按名称精确/包含（与 ProfileToolProvider.resolveSegment 同法） */
    static AnalyticsFunnel resolveFunnel(List<AnalyticsFunnel> funnels, String q) {
        String key = q.trim();
        if (NUMERIC.matcher(key).matches()) {
            try {
                long id = Long.parseLong(key);
                for (AnalyticsFunnel f : funnels) {
                    if (id == f.getFunnelId()) {
                        return f;
                    }
                }
            } catch (NumberFormatException ignored) {
                // 超长数字串走名称兜底
            }
        }
        for (AnalyticsFunnel f : funnels) {
            if (key.equals(f.getFunnelName())) {
                return f;
            }
        }
        for (AnalyticsFunnel f : funnels) {
            if (f.getFunnelName() != null && f.getFunnelName().contains(key)) {
                return f;
            }
        }
        return null;
    }

    private static List<Map<String, Object>> funnelSummaries(List<AnalyticsFunnel> funnels) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (AnalyticsFunnel f : funnels) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("funnelId", String.valueOf(f.getFunnelId()));
            item.put("name", f.getFunnelName());
            item.put("windowDays", f.getWindowDays());
            out.add(item);
        }
        return out;
    }

    private static final Pattern NUMERIC = Pattern.compile("\\d{1,19}");

    static String metricLabel(String metric) {
        return switch (metric == null ? "" : metric) {
            case "uv" -> "活跃访客";
            case "pv" -> "浏览量";
            case "visits" -> "访问次数";
            case "events" -> "事件数";
            default -> metric;
        };
    }

    /** "2026-11-02" → "11-02"（图例横轴省年份; 非該格式原样返回） */
    static String shortDate(String date) {
        if (date != null && date.length() >= 10 && date.charAt(4) == '-') {
            return date.substring(5);
        }
        return date;
    }

    /** chart 数字统一整数（人数/次数/天数；活体验收实证 double 形态 1.0 会让模型照抄 JSON 变长变脆） */
    static long numberOrZero(Object v) {
        if (v instanceof Number n) {
            return Math.round(n.doubleValue());
        }
        try {
            return v == null ? 0 : Math.round(Double.parseDouble(String.valueOf(v)));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 工具缺省源=单源口径 UNIFIED（ProfileSources 单源重构 M1: 一切写入归一个源） */
    private static String source(BizToolCall call) {
        String s = trimToNull(call.getArgs(), "source");
        return s != null ? s : ProfileSources.UNIFIED;
    }

    private static String trimToNull(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer intArg(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? null : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Throwable unwrap(Exception e) {
        Throwable t = e;
        while ((t instanceof java.util.concurrent.ExecutionException
            || t instanceof java.util.concurrent.CompletionException) && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    /**
     * SPI 纪律: execute 必须自带超时且不占共享池（与 ProfileToolProvider 同参数 2/8/64;
     * Abort 拒绝——池满立即人话失败, 不让查询内联进助手执行线程）。
     */
    private static final java.util.concurrent.ExecutorService TOOL_POOL =
        new java.util.concurrent.ThreadPoolExecutor(
            2, 8, 60L, TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "analytics-biztool");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    @jakarta.annotation.PreDestroy
    void shutdownPool() {
        TOOL_POOL.shutdownNow();
    }
}
