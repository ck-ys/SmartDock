package com.smartdock.profile.assistant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdock.assistant.spi.BizParamSpec;
import com.smartdock.assistant.spi.BizToolCall;
import com.smartdock.assistant.spi.BizToolProvider;
import com.smartdock.assistant.spi.BizToolResult;
import com.smartdock.assistant.spi.BizToolSpec;
import com.smartdock.profile.domain.ProfileSegment;
import com.smartdock.profile.domain.ProfileSegmentRun;
import com.smartdock.profile.engine.ProfileQueryService;
import com.smartdock.profile.engine.ProfileReviewService;
import com.smartdock.profile.engine.ProfileSegmentService;
import com.smartdock.profile.engine.ProfileSourceConfigService;
import com.smartdock.profile.mapper.ProfileSegmentMapper;
import com.smartdock.profile.query.ProfileSegmentMemberRow;
import com.smartdock.profile.query.ProfileSubjectQuery;
import com.smartdock.profile.query.ProfileSubjectRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.exception.ServiceException;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.tenant.helper.TenantHelper;
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
 * 画像引擎喂给 AI 助手的只读工具(画像方向第三批·题6): 查画像 + 圈人 + 迁入批9 两概况工具。
 *
 * <p>批9 曾在 dcs 演示模块落过三个无门工具(subject_summary/segment_overview/review_overview),
 * 本批整体迁入画像引擎并补上权限门: subject_summary 并入 profile_query_subject(subjectId 精确查),
 * segment_overview/review_overview 名字保留但语义有变(旧缺省源写死 web→现缺省全源;
 * 旧无权限门→现按菜单权限注册); dcs 演示模块不再拥有画像通用能力。</p>
 *
 * <p><b>权限口径(与 UI 完全同源)</b>: ①注册门槛=BizToolSpec.requiredPermission
 * (查画像=profile:score:query 与用户画像页同串; 圈人/分群概况=profile:segment:query 与画像分群页同串;
 * 复核概况=profile:review:query), 没有对应菜单的人模型看不见工具;
 * ②readOnly=true 只读直行不出确认卡;
 * ③行级分数可见性=菜单权限含 profile:pii:score(与列表/排行列级权限同口径)——
 * 工具线程无 Sa-Token 上下文, StpUtil 恒 false(保守), 故按 call.menuPermissions 自判。</p>
 *
 * <p><b>租户与超时(SPI 纪律)</b>: 查询全部包在 {@code TenantHelper.dynamic(tenantId, ...)}
 * (工具线程 TenantHelper.getTenantId() 恒 null, WebSearchTools 同款先例);
 * execute 自带 8s 超时, 到点返回人话失败不炸会话。</p>
 *
 * <p><b>PII 最小化</b>: 联系方式类 properties 一律不进工具输出(比 UI 更紧);
 * 行为只给 lastEventTime/eventCount 汇总数, 不给明细。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileToolProvider implements BizToolProvider {

    /** 助手查画像与用户画像页(9070)同串 */
    static final String PERM_QUERY = "profile:score:query";
    /** 助手圈人与画像分群页(11981)同串 */
    static final String PERM_SEGMENT = "profile:segment:query";
    /** 低分复核概况与复核页同串(批9 迁入工具的门) */
    static final String PERM_REVIEW = "profile:review:query";
    /** 分数可见性与 UI 列级权限同串(无此串=总分/维度一律不出) */
    static final String PERM_SCORE = "profile:pii:score";

    static final String TOOL_QUERY_SUBJECT = "profile_query_subject";
    static final String TOOL_SEGMENT_MEMBERS = "profile_segment_members";
    /** 批9 已上线工具名, 迁入本 provider 保留(名不变, 缺省源与权限可见性有变, 见类注释) */
    static final String TOOL_SEGMENT_OVERVIEW = "profile_segment_overview";
    static final String TOOL_REVIEW_OVERVIEW = "profile_review_overview";

    private static final long TIMEOUT_MS = 8_000L;

    private final ProfileQueryService queryService;
    private final ProfileSegmentService segmentService;
    private final ProfileSegmentMapper segmentMapper;
    private final ProfileReviewService reviewService;
    private final ProfileSourceConfigService sourceConfigService;

    @Override
    public String namespace() {
        return "profile";
    }

    @Override
    public List<BizToolSpec> tools() {
        BizToolSpec query = BizToolSpec.readOnly(TOOL_QUERY_SUBJECT, "查画像",
            "查一个人的用户画像, 或按最近活跃排序列人。数据来自画像引擎的真实业务事件。\n"
            + "- 精确查: 传 subjectId(主体的 ID)可选配 subjectType, 返回这个人最新一次打分的总分与各维度明细;\n"
            + "- 模糊找: 传 keyword(人名或 ID 片段), 返回最匹配的前几人, 适合「找一下姓王的」;\n"
            + "- 都不传=按最近活跃列出前几人, 直接回答「最近谁最活跃/最近 30 天谁最活跃」;\n"
            + "- source 不传=跨全部来源查。用户没开分数权限时结果里不会有分数, 属正常现象, 照实转告即可。");
        query.setMinRoleLevel(0);
        query.setRequiredPermission(PERM_QUERY);
        query.setParams(List.of(
            BizParamSpec.string("keyword", "人名或主体 ID 的模糊搜索词(与 subjectId 二选一)", false),
            BizParamSpec.string("subjectId", "精确主体 ID(与 keyword 二选一, 优先)", false),
            BizParamSpec.string("subjectType", "主体类型, 缺省 user", false),
            BizParamSpec.string("source", "数据来源(如 dcs), 不传=全部来源", false),
            BizParamSpec.number("limit", "列表返回条数(模糊找与最近活跃榜通用), 缺省 5, 上限 20", false)
        ));

        BizToolSpec segment = BizToolSpec.readOnly(TOOL_SEGMENT_MEMBERS, "圈人",
            "把某个画像分群(按规则圈出的一批人)的成员名单拉出来。\n"
            + "- segment 传分群名称或分群 ID; 不知道有哪些分群时先传空串, 会返回分群清单;\n"
            + "- 返回成员的主体 ID/姓名/入群原因/最近活跃时间, 满足「把 90 天没来的人圈出来」一类名单需求;\n"
            + "- 用户没开分数权限时名单里没有分数列, 属正常现象。");
        segment.setMinRoleLevel(0);
        segment.setRequiredPermission(PERM_SEGMENT);
        segment.setParams(List.of(
            BizParamSpec.string("segment", "分群名称或分群 ID; 空串=只列分群清单不拉成员", false),
            BizParamSpec.number("limit", "成员返回条数, 缺省 20, 上限 100", false),
            BizParamSpec.string("keyword", "在成员里按人名/ID 再过滤, 可选", false)
        ));

        BizToolSpec overview = BizToolSpec.readOnly(TOOL_SEGMENT_OVERVIEW, "分群概况",
            "看画像分群现状: 每个分群现在有多少人、最近一次刷新时间、刷新模式; 传 segmentId 看单个分群近 7 次人数趋势。\n"
            + "要具体成员名单用「圈人」工具, 本工具只看人数不动名单。");
        overview.setRequiredPermission(PERM_SEGMENT);
        overview.setParams(List.of(
            BizParamSpec.string("source", "数据来源(如 dcs), 不传=全部来源", false),
            BizParamSpec.string("segmentId", "分群 ID(可空=列全部分群)", false)
        ));

        BizToolSpec review = BizToolSpec.readOnly(TOOL_REVIEW_OVERVIEW, "低分复核概况",
            "查低分复核队列概况: 待处理数、超时(SLA)数、最老一条入队时间。只看概况, 处理复核请到画像页面。");
        review.setRequiredPermission(PERM_REVIEW);
        review.setParams(List.of(
            BizParamSpec.string("source", "数据来源(可空=全部)", false)
        ));

        return List.of(query, segment, overview, review);
    }

    @Override
    public BizToolResult execute(BizToolCall call) {
        // 三审 dspro P2-1/dsflash P2: 空租户必须 fail-closed——插件见空租户会整条 SQL 不注入租户条件(越权开口),
        // 旧 dcs 实现与 DcsToolProvider 均有此门, 迁移时丢了, 现补回
        if (call.getTenantId() == null || call.getTenantId().isBlank()) {
            return BizToolResult.fail("缺少租户上下文, 无法执行画像工具");
        }
        CompletableFuture<BizToolResult> future = null;
        try {
            future = CompletableFuture.supplyAsync(() -> TenantHelper.dynamic(call.getTenantId(),
                    () -> dispatch(call)), TOOL_POOL);
            return future.get(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 三审 dspro P1-1: 排队中的任务到点作废(不再白跑一次查询); 超时率可观测
            if (future != null) {
                future.cancel(false);
            }
            log.warn("[ProfileTool] timeout tool={} tenant={}", call.getToolName(), call.getTenantId());
            return BizToolResult.fail("查询超时(" + TIMEOUT_MS + "ms), 建议重试一次");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return BizToolResult.fail("查询被中断, 请重试");
        } catch (java.util.concurrent.RejectedExecutionException e) {
            // 池满即人话失败——绝不用 CallerRuns 把阻塞转移到助手执行线程(那会让 8s 超时形同虚设)
            return BizToolResult.fail("画像查询排队已满, 请稍后再试");
        } catch (Exception e) {
            Throwable cause = unwrap(e);
            if (cause instanceof ServiceException se) {
                // 含中文的人话业务文案(分群不存在/来源不存在等)原样给模型转告
                return BizToolResult.fail(se.getMessage());
            }
            log.warn("[ProfileTool] {} failed: {}", call.getToolName(), cause);
            return BizToolResult.fail("查询失败(系统异常), 请稍后再试");
        }
    }

    private BizToolResult dispatch(BizToolCall call) {
        if (TOOL_QUERY_SUBJECT.equals(call.getToolName())) {
            return querySubject(call);
        }
        if (TOOL_SEGMENT_MEMBERS.equals(call.getToolName())) {
            return segmentMembers(call);
        }
        if (TOOL_SEGMENT_OVERVIEW.equals(call.getToolName())) {
            return segmentOverview(call);
        }
        if (TOOL_REVIEW_OVERVIEW.equals(call.getToolName())) {
            return reviewOverview(call);
        }
        return BizToolResult.fail("未知工具: " + call.getToolName());
    }

    // ---------------------------------------------------------------- 查画像

    private BizToolResult querySubject(BizToolCall call) {
        Map<String, Object> args = call.getArgs();
        boolean scoreVisible = canSee(call.getMenuPermissions(), PERM_SCORE);
        String subjectId = trimToNull(args, "subjectId");
        String keyword = trimToNull(args, "keyword");
        // 都不传=「最近谁最活跃」: 按最近活跃列前几人, 不算错参
        String source = trimToNull(args, "source");
        String subjectType = trimToNull(args, "subjectType");
        if (subjectId != null) {
            Map<String, Object> summary = queryService.subjectSummary(subjectType, subjectId, source, scoreVisible);
            if (!Boolean.TRUE.equals(summary.get("found"))) {
                summary.put("hint", "没找到精确 ID, 改传 keyword(人名/ID 片段)模糊找");
                return BizToolResult.ok(summary);
            }
            if (!scoreVisible) {
                // 活体实证(E2E): 缺这句模型会把「分数被权限剥掉」误读成「此人没打过分的记录」
                summary.put("scoreNote", "此人可能已打分; 你没开分数查看权限, 分数与维度明细不返回, 别说成没打过分的记录");
            }
            return BizToolResult.ok(Map.of("match", summary, "scoreVisible", scoreVisible));
        }
        ProfileSubjectQuery query = new ProfileSubjectQuery();
        query.setKeyword(keyword);
        query.setSource(source);
        // 与 subjectId 精确查同默认: 不传主体类型=按 user 查(三审 dspro P3-6——两分支口径一致)
        query.setSubjectType(subjectType != null ? subjectType : "user");
        // 缺省按最近活跃排序——「最近 30 天谁最活跃」直接命中
        query.setSortBy("lastEvent");
        query.setSortOrder("desc");
        int limit = clampLimit(intArg(args, "limit"), 5, 20);
        // 引擎列表是单源接口(requireSource), 跨源=逐源拉同页再按最近活跃合并
        List<ProfileSubjectRow> merged = new ArrayList<>();
        long total = 0;
        List<String> sources = subjectSources(call, source);
        for (String src : sources) {
            query.setSource(src);
            TableDataInfo<ProfileSubjectRow> page = queryService.subjectPage(query, new PageQuery(limit, 1), scoreVisible);
            total += page.getTotal();
            merged.addAll(page.getRows());
        }
        merged.sort(java.util.Comparator.comparing(ProfileSubjectRow::getLastEventTime,
            java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())));
        if (merged.size() > limit) {
            merged = merged.subList(0, limit);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProfileSubjectRow row : merged) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("subjectId", row.getSubjectId());
            out.put("subjectType", row.getSubjectType());
            out.put("displayName", row.getDisplayName());
            out.put("source", row.getSource());
            out.put("eventCount", row.getEventCount());
            out.put("lastEventTime", ToolDates.iso(row.getLastEventTime()));
            if (scoreVisible) {
                out.put("totalScore", row.getTotalScore());
            }
            rows.add(out);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("keyword", keyword);
        data.put("total", total);
        data.put("rows", rows);
        data.put("scoreVisible", scoreVisible);
        StringBuilder hint = new StringBuilder();
        if (total > rows.size()) {
            hint.append("共 ").append(total).append(" 人, 只列前 ").append(rows.size()).append(" 人; ");
        }
        if (sources.size() > 1) {
            hint.append("总人数是各来源合计, 同一人出现在多个来源会被分别计入; ");
        }
        hint.append("要维度明细就传 subjectId 精确查");
        data.put("hint", hint.toString());
        return BizToolResult.ok(data);
    }

    /** 工具用的源清单: 指定源=单源; 未指定=全部真实有主体的源(含停用, 数据都在) */
    private List<String> subjectSources(BizToolCall call, String source) {
        if (source != null) {
            return List.of(source);
        }
        List<String> out = new ArrayList<>();
        for (Map<String, Object> row : sourceConfigService.listForAdmin(call.getTenantId())) {
            Object subjects = row.get("subjects");
            if (subjects instanceof Number n && n.longValue() > 0) {
                out.add(String.valueOf(row.get("source")));
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- 圈人

    private BizToolResult segmentMembers(BizToolCall call) {
        Map<String, Object> args = call.getArgs();
        boolean scoreVisible = canSee(call.getMenuPermissions(), PERM_SCORE);
        String segmentArg = trimToNull(args, "segment");
        // 圈人天然跨源(service.list 强制单源, 这里走 mapper 跨源列全部——租户由插件注入)
        List<ProfileSegment> all = listSegments(null);
        if (segmentArg == null) {
            return BizToolResult.ok(Map.of("segments", segmentSummaries(all),
                "hint", "传 segment(名称或 ID)拉成员名单"));
        }
        ProfileSegment segment = resolveSegment(all, segmentArg);
        if (segment == null) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("found", false);
            data.put("message", "没有叫「" + segmentArg + "」的分群; 现有分群如下");
            data.put("segments", segmentSummaries(all));
            return BizToolResult.ok(data);
        }
        if (segment.getLastRunId() == null) {
            return BizToolResult.fail("分群「" + segment.getSegmentName() + "」还没跑过, 没有成员名单");
        }
        int limit = clampLimit(intArg(args, "limit"), 20, 100);
        String keyword = trimToNull(args, "keyword");
        var page = segmentService.memberPage(segment.getSegmentId(), 1, limit, keyword);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProfileSegmentMemberRow row : page.getRecords()) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("subjectId", row.getSubjectId());
            out.put("subjectType", row.getSubjectType());
            out.put("displayName", row.getDisplayName());
            out.put("reason", row.getReason());
            out.put("lastEventTime", ToolDates.iso(row.getLastEventTime()));
            if (scoreVisible) {
                out.put("totalScore", row.getTotalScore());
            }
            rows.add(out);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("segment", segmentSummary(segment));
        data.put("total", page.getTotal());
        data.put("rows", rows);
        data.put("scoreVisible", scoreVisible);
        data.put("truncated", page.getTotal() > rows.size());
        return BizToolResult.ok(data);
    }

    // ------------------------------------------------- 分群概况(批9 迁入, 原名保留)

    private BizToolResult segmentOverview(BizToolCall call) {
        Map<String, Object> args = call.getArgs();
        String segmentArg = trimToNull(args, "segmentId");
        if (segmentArg != null) {
            if (!NUMERIC.matcher(segmentArg).matches()) {
                return BizToolResult.fail("segmentId 必须是数字 ID; 先不传 segmentId 列出全部分群再挑");
            }
            ProfileSegment segment;
            try {
                // get() 找不到时直接抛「分群不存在」人话 ServiceException, 由 execute 统一透传
                segment = segmentService.get(Long.parseLong(segmentArg));
            } catch (NumberFormatException e) {
                return BizToolResult.fail("segmentId 无法解析: " + segmentArg);
            }
            List<Map<String, Object>> trend = new ArrayList<>();
            int n = 0;
            for (ProfileSegmentRun run : segmentService.runs(segment.getSegmentId())) {
                if (n++ >= 7) {
                    break;
                }
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("finishedAt", ToolDates.iso(run.getFinishedAt()));
                point.put("memberCount", run.getMemberCount());
                point.put("status", run.getStatus());
                trend.add(point);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("segment", segmentSummary(segment));
            data.put("runs", trend);
            return BizToolResult.ok(data);
        }
        List<ProfileSegment> all = listSegments(trimToNull(args, "source"));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("segmentCount", all.size());
        data.put("segments", segmentSummaries(all));
        data.put("hint", "要具体成员名单用「圈人」工具传 segment");
        return BizToolResult.ok(data);
    }

    // ------------------------------------------------- 低分复核概况(批9 迁入, 原名保留)

    private BizToolResult reviewOverview(BizToolCall call) {
        // 已知既定副作用(三审 dspro P2-6 登记): summary() 首查会 getOrCreate 一行引擎配置(幂等 insert),
        // 不改数据语义, 复核页同款口径
        return BizToolResult.ok(reviewService.summary(trimToNull(call.getArgs(), "source")));
    }

    // ---------------------------------------------------------------- 纯函数(单测锚点)

    /** 权限三态(语义与助手 ToolRegistry.wildcardOrContains 同源, 本地实现避免跨模块依赖):
     *  通配 * / *:*:* / 精确串 含其一即过; null/不含=不过 */
    static boolean canSee(Set<String> menuPermissions, String requiredPermission) {
        return menuPermissions != null
            && (menuPermissions.contains("*")
                || menuPermissions.contains("*:*:*")
                || menuPermissions.contains(requiredPermission));
    }

    /** limit 归一: 空/非法取缺省, 越界夹到 [1, max] */
    static int clampLimit(Integer raw, int def, int max) {
        if (raw == null) {
            return def;
        }
        return Math.min(Math.max(raw, 1), max);
    }

    /** 分群解析: 纯数字先按 ID 精确(未命中不急着回 null, 落到名称匹配——名字叫"2024"的分群也能找到);
     *  否则按名称精确再退包含匹配; 找不到=null */
    static ProfileSegment resolveSegment(List<ProfileSegment> segments, String q) {
        String key = q.trim();
        if (NUMERIC.matcher(key).matches()) {
            try {
                long id = Long.parseLong(key);
                for (ProfileSegment s : segments) {
                    if (id == s.getSegmentId()) {
                        return s;
                    }
                }
                // ID 未命中→继续走名称匹配兜底
            } catch (NumberFormatException ignored) {
                // 超长数字串走名称匹配兜底
            }
        }
        for (ProfileSegment s : segments) {
            if (key.equals(s.getSegmentName())) {
                return s;
            }
        }
        for (ProfileSegment s : segments) {
            if (s.getSegmentName() != null && s.getSegmentName().contains(key)) {
                return s;
            }
        }
        return null;
    }

    private static final Pattern NUMERIC = Pattern.compile("\\d{1,19}");

    /** 跨源列分群(service.list 强制单源, 工具一律走 mapper; source 可空=全部); 上限 50 防大租户灌爆模型上下文 */
    private List<ProfileSegment> listSegments(String source) {
        LambdaQueryWrapper<ProfileSegment> wrapper = new LambdaQueryWrapper<ProfileSegment>()
            .orderByDesc(ProfileSegment::getBuiltin)
            .orderByAsc(ProfileSegment::getSegmentName)
            .last("LIMIT 50");
        if (source != null) {
            wrapper.eq(ProfileSegment::getSource, source);
        }
        return segmentMapper.selectList(wrapper);
    }

    private static List<Map<String, Object>> segmentSummaries(List<ProfileSegment> segments) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ProfileSegment s : segments) {
            out.add(segmentSummary(s));
        }
        return out;
    }

    private static Map<String, Object> segmentSummary(ProfileSegment s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("segmentId", String.valueOf(s.getSegmentId()));
        out.put("name", s.getSegmentName());
        out.put("source", s.getSource());
        out.put("type", "1".equals(s.getBuiltin()) ? "builtin" : "custom");
        out.put("segmentType", s.getSegmentType());
        out.put("memberCount", s.getMemberCount());
        out.put("lastEvalTime", ToolDates.iso(s.getLastEvalTime()));
        out.put("refreshMode", s.getRefreshMode());
        return out;
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
     * SPI 纪律: execute 必须自带超时且不占共享池。参数与助手侧 ToolSupport 同款(2/8/64);
     * 拒绝策略=Abort——池满立即人话失败, CallerRuns 会把查询内联进调用线程让 8s 超时形同虚设(三审 dspro P1-1/P2-2)。
     */
    private static final java.util.concurrent.ExecutorService TOOL_POOL =
        new java.util.concurrent.ThreadPoolExecutor(
            2, 8, 60L, TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(64),
            r -> {
                Thread t = new Thread(r, "profile-biztool");
                t.setDaemon(true);
                return t;
            },
            new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());

    /** 优雅停机: 在途查询随 JVM 退出作废(daemon 池本就不阻塞退出, 这里只做干净收口) */
    @jakarta.annotation.PreDestroy
    void shutdownPool() {
        TOOL_POOL.shutdownNow();
    }

    /** 日期 ISO 化(画像实体用 java.util.Date) */
    static final class ToolDates {
        private ToolDates() {
        }

        static String iso(java.util.Date date) {
            return date == null ? null
                : java.time.OffsetDateTime.ofInstant(date.toInstant(),
                    java.time.ZoneOffset.ofHours(8)).toString();
        }
    }
}
