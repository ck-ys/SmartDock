package com.smartdock.dcs.tool;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画像只读查询工具(批9 拍板 Q5/Q9-A): 助手能"回答画像问题", 不能改画像。
 *
 * <p>安全口径(风暴加固): 服务端固定查询 + 只收 ID 参数(无自由文本查询面);
 * 返回聚合摘要而非原始行; 租户用 TenantHelper.dynamic 包裹;
 * 全部 readOnly(无确认卡, 不碰并行窗口的 HITL 交互)。
 * 写操作(批量打标签/入队)等 HITL 稳定后另行接入。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProfileToolProvider implements BizToolProvider {

    private final ProfileSegmentService segmentService;
    private final ProfileReviewService reviewService;
    private final ProfileQueryService queryService;

    @Override
    public String namespace() {
        return "profile";
    }

    @Override
    public List<BizToolSpec> tools() {
        List<BizToolSpec> out = new ArrayList<>();

        BizToolSpec subjectSummary = BizToolSpec.readOnly("profile_subject_summary",
            "画像主体摘要", "查一个主体的画像: 当前分数、各维度贡献、规则版本、最近活跃");
        subjectSummary.getParams().add(BizParamSpec.string("subjectId", "主体 ID(如 demo-s-07 或系统用户ID)", true));
        subjectSummary.getParams().add(BizParamSpec.string("subjectType", "主体类型(默认 user)", false));
        out.add(subjectSummary);

        BizToolSpec segmentOverview = BizToolSpec.readOnly("profile_segment_overview",
            "画像分群概况", "查分群现状: 每个分群的人数/最近刷新/刷新模式; 传 segmentId 看单个分群的近 7 次人数趋势");
        segmentOverview.getParams().add(BizParamSpec.string("source", "业务源(默认 web)", false));
        segmentOverview.getParams().add(BizParamSpec.string("segmentId", "分群 ID(可空=列全部)", false));
        out.add(segmentOverview);

        BizToolSpec reviewOverview = BizToolSpec.readOnly("profile_review_overview",
            "低分复核概况", "查低分复核队列: 待处理数、超 SLA 数、最老一条入队时间");
        reviewOverview.getParams().add(BizParamSpec.string("source", "业务源(可空=全部)", false));
        out.add(reviewOverview);
        return out;
    }

    @Override
    public BizToolResult execute(BizToolCall call) {
        try {
            String tenantId = call.getTenantId();
            if (StringUtils.isBlank(tenantId)) {
                return BizToolResult.fail("缺少租户上下文, 无法执行画像工具");
            }
            return TenantHelper.dynamic(tenantId, () -> dispatch(call));
        } catch (Exception e) {
            log.warn("[ProfileTool] execute failed tool={} : {}", call.getToolName(), e.toString());
            return BizToolResult.fail("画像工具执行失败, 请稍后重试或到画像页面查看");
        }
    }

    private BizToolResult dispatch(BizToolCall call) {
        return switch (call.getToolName()) {
            case "profile_subject_summary" -> subjectSummary(call);
            case "profile_segment_overview" -> segmentOverview(call);
            case "profile_review_overview" -> reviewOverview(call);
            default -> BizToolResult.fail("未知画像工具: " + call.getToolName());
        };
    }

    private BizToolResult subjectSummary(BizToolCall call) {
        String subjectId = param(call, "subjectId");
        String subjectType = param(call, "subjectType");
        Map<String, Object> summary = queryService.subjectSummary(subjectType, subjectId);
        if (!Boolean.TRUE.equals(summary.get("found"))) {
            return BizToolResult.fail("没有找到该主体: " + subjectId);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("主体", summary.get("subjectId"));
        data.put("名称", summary.get("displayName"));
        data.put("业务源", summary.get("source"));
        data.put("事件数", summary.get("eventCount"));
        data.put("最近事件", summary.get("lastEventTime"));
        data.put("当前总分", summary.get("totalScore"));
        data.put("规则版本", summary.get("ruleVersion"));
        data.put("计算时间", summary.get("calcTime"));
        data.put("维度明细", summary.get("dimensionDetail"));
        return BizToolResult.ok(data);
    }

    private BizToolResult segmentOverview(BizToolCall call) {
        String source = StringUtils.isBlank(param(call, "source")) ? "web" : param(call, "source").trim();
        String segmentId = param(call, "segmentId");
        if (StringUtils.isNotBlank(segmentId)) {
            Long id = parseId(segmentId);
            if (id == null) {
                return BizToolResult.fail("segmentId 必须是数字");
            }
            ProfileSegment segment = segmentService.get(id);
            List<ProfileSegmentRun> runs = segmentService.runs(id);
            List<Object> trend = new ArrayList<>();
            int n = 0;
            for (ProfileSegmentRun run : runs) {
                if (n++ >= 7) {
                    break;
                }
                Map<String, Object> point = new LinkedHashMap<>();
                point.put("时间", run.getFinishedAt());
                point.put("人数", run.getMemberCount());
                point.put("状态", run.getStatus());
                trend.add(point);
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("分群", segment.getSegmentName());
            data.put("类型", segment.getSegmentType());
            data.put("当前人数", segment.getMemberCount());
            data.put("最近刷新", segment.getLastEvalTime());
            data.put("刷新模式", segment.getRefreshMode());
            data.put("近7次人数趋势(新→旧)", trend);
            return BizToolResult.ok(data);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (ProfileSegment segment : segmentService.list(source)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", segment.getSegmentId());
            row.put("分群", segment.getSegmentName());
            row.put("类型", segment.getSegmentType());
            row.put("人数", segment.getMemberCount());
            row.put("最近刷新", segment.getLastEvalTime());
            row.put("刷新模式", segment.getRefreshMode());
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("业务源", source);
        data.put("分群数", rows.size());
        data.put("分群列表", rows);
        return BizToolResult.ok(data);
    }

    private BizToolResult reviewOverview(BizToolCall call) {
        Map<String, Object> summary = reviewService.summary(param(call, "source"));
        return BizToolResult.ok(summary);
    }

    private String param(BizToolCall call, String name) {
        Map<String, Object> params = call.getArgs();
        if (params == null) {
            return null;
        }
        Object v = params.get(name);
        return v == null ? null : String.valueOf(v);
    }

    private Long parseId(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
