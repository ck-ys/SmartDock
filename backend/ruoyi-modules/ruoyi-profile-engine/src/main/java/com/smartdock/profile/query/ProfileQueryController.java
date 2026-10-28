package com.smartdock.profile.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.analytics.mapper.AnalyticsEventMapper;
import com.smartdock.profile.engine.ProfileQueryService;
import com.smartdock.profile.mapper.ProfileSubjectMapper;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 画像查询接口(只读): 列表/总览/排行/详情。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileQueryController {

    private final ProfileQueryService queryService;
    private final ProfileSubjectMapper subjectMapper;
    private final AnalyticsEventMapper eventMapper;

    /**
     * 主体分页(带最新快照与筛选/排序)。
     */
    /** 风险分档阈值单源下发(批9 Q7-A: 消除前端+SQL 双处硬编码 75/60)。 */
    @SaCheckPermission("profile:score:query")
    @GetMapping("/risk-bands")
    public R<Map<String, Object>> riskBands() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mediumMin", ProfileQueryService.RISK_MEDIUM);
        out.put("highBelow", ProfileQueryService.RISK_MEDIUM);
        out.put("lowMin", ProfileQueryService.RISK_HIGH);
        return R.ok(out);
    }

    /**
     * 批2 列源: 画像系(有主体)/分析系(有事件)两份源清单, 前端各页源下拉单源化。
     * 登录即可见(下拉原料非敏感), 不挂权限注解。
     */
    @GetMapping("/source/list")
    public R<Map<String, Object>> sourceList() {
        Map<String, Object> out = new LinkedHashMap<>();
        // 三审 P2: 取当前生效租户(超管动态切换后下拉要跟着走), 登录租户在切换态下是错的
        String tenantId = org.dromara.common.tenant.helper.TenantHelper.getTenantId();
        if (tenantId == null || "null".equals(tenantId)) {
            // 五审回修: 租户上下文缺失时返回空清单, 不拿字面量 "null" 当租户号查库
            out.put("subjectSources", java.util.List.of());
            out.put("eventSources", java.util.List.of());
            out.put("subjectHidden", 0);
            out.put("eventHidden", 0);
            return R.ok(out);
        }
        // 过滤版(下拉用, 停用源剔除) + 停用计数(三审 dspro P2):
        // 清单为空但 hidden>0 = "全被停用了"而不是"没数据", 前端空态分两套文案
        java.util.List<java.util.Map<String, Object>> subjectFiltered = subjectMapper.selectSubjectSources(tenantId);
        java.util.List<java.util.Map<String, Object>> eventFiltered = eventMapper.selectEventSources(tenantId);
        out.put("subjectSources", subjectFiltered);
        out.put("eventSources", eventFiltered);
        out.put("subjectHidden", subjectMapper.selectSubjectSourcesRaw(tenantId).size() - subjectFiltered.size());
        out.put("eventHidden", eventMapper.selectEventSourcesRaw(tenantId).size() - eventFiltered.size());
        return R.ok(out);
    }

    @GetMapping("/subject/page")
    public TableDataInfo<ProfileSubjectRow> subjectPage(ProfileSubjectQuery query, PageQuery pageQuery) {
        return queryService.subjectPage(query, pageQuery);
    }

    /**
     * 单主体画像依据摘要(批9 Q7-A): 主体基本信息 + 最新维度快照, "查看依据"抽屉用。
     */
    @SaCheckPermission("profile:score:query")
    @GetMapping("/subject/summary")
    public R<Map<String, Object>> subjectSummary(@RequestParam(required = false, defaultValue = "user") String subjectType,
                                                 @RequestParam String subjectId,
                                                 @RequestParam(required = false) String source) {
        return R.ok(queryService.subjectSummary(subjectType, subjectId, source));
    }

    /**
     * 画像总览: 主体数/已算分/平均分/风险分层。
     */
    @SaCheckPermission("profile:score:query")
    @GetMapping("/overview")
    public R<ProfileOverview> overview(@RequestParam String source,
                                       @RequestParam(required = false) String subjectType) {
        return R.ok(queryService.overview(source, subjectType));
    }

    /**
     * 维度排行: dimension 为空按总分。
     */
    @SaCheckPermission("profile:score:query")
    @GetMapping("/ranking")
    public R<List<ProfileRankingRow>> ranking(@RequestParam String source,
                                              @RequestParam(required = false) String subjectType,
                                              @RequestParam(required = false) String dimension,
                                              @RequestParam(defaultValue = "10") int limit) {
        return R.ok(queryService.ranking(source, subjectType, dimension, limit));
    }

    /**
     * 单主体画像: 主体 + 最新快照 + 维度/证据 + 排名/风险 + 趋势。
     */
    @SaCheckPermission("profile:score:query")
    @GetMapping("/score/{source}/{subjectId}")
    public R<Map<String, Object>> score(@PathVariable String source,
                                        @PathVariable String subjectId,
                                        @RequestParam(defaultValue = "user") String subjectType,
                                        @RequestParam(defaultValue = "5") int tail) {
        return R.ok(queryService.detail(source, subjectType, subjectId, tail));
    }
}
