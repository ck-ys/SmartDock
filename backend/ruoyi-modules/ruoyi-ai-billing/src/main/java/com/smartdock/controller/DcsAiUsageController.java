package com.smartdock.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.excel.utils.ExcelUtil;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.web.core.BaseController;
import com.smartdock.domain.bo.DcsAiUsageQueryBo;
import com.smartdock.domain.DcsAiUsage;
import com.smartdock.domain.vo.AiUsageAnalysisExportVo;
import com.smartdock.domain.vo.DcsAiUsageExportVo;
import com.smartdock.domain.vo.DcsAiUsageVo;
import com.smartdock.service.support.AiBillingService;
import com.smartdock.service.support.AiUsageAnalysisService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * AI 调用消耗台账控制器。
 *
 * <p>菜单: 数据采集 → AI 用量明细。供租户管理员查看本租户 AI 消耗。
 * AI 缺口批 Q5/Q6/Q7（2026-10-14 拍板全A）：新增「用量分析」聚合端点 + 三个导出端点。</p>
 *
 * @author smartdock
 */
@RequiredArgsConstructor
@RestController
@RequestMapping("/dcs/ai-usage")
public class DcsAiUsageController extends BaseController {

    private final AiBillingService billingService;
    private final AiUsageAnalysisService analysisService;

    /**
     * 分页查询消耗明细。
     *
     * @param bo         AI 用量查询条件
     * @param pageQuery 分页参数
     * @return 分页的 AI 用量列表
     */
    @SaCheckPermission("dcs:ai-config:query")
    @GetMapping("/list")
    public TableDataInfo<DcsAiUsageVo> list(DcsAiUsageQueryBo bo, PageQuery pageQuery) {
        return billingService.usagePage(bo, pageQuery);
    }

    /**
     * 消耗统计(总量/总金额/成功率/各调用方分布/近7天每日)。
     *
     * @return 汇总统计数据
     */
    @SaCheckPermission("dcs:ai-config:query")
    @GetMapping("/summary")
    public R<Map<String, Object>> summary() {
        return R.ok(billingService.usageSummary());
    }

    /**
     * AI 缺口批 Q5/Q6：用量分析（近 30 天趋势/模型占比/Top10 用户含部门/部门占比/汇总）。
     * beginDate/endDate 缺省近 30 天；SQL 聚合下推，日期轴服务层补零。
     */
    @SaCheckPermission("dcs:ai-config:query")
    @GetMapping("/analysis")
    public R<Map<String, Object>> analysis(@RequestParam(required = false) String beginDate,
                                           @RequestParam(required = false) String endDate) {
        return R.ok(analysisService.analysis(org.dromara.common.satoken.utils.LoginHelper.getTenantId(), beginDate, endDate));
    }

    /** AI 缺口批 Q7：按当前筛选导出明细 Excel（上限 1 万行）。 */
    @SaCheckPermission("dcs:ai-config:query")
    @PostMapping("/export")
    public void export(jakarta.servlet.http.HttpServletResponse response, DcsAiUsageQueryBo bo) {
        List<DcsAiUsage> rows = billingService.usageExportList(bo);
        List<DcsAiUsageExportVo> vos = new ArrayList<>(rows.size());
        for (DcsAiUsage u : rows) {
            DcsAiUsageExportVo vo = new DcsAiUsageExportVo();
            vo.setCreateTime(fmt(u.getCreateTime()));
            vo.setCaller(callerLabel(u.getCaller()));
            vo.setScene(u.getScene() == null ? "" : u.getScene());
            vo.setProvider(u.getProvider() == null ? "" : u.getProvider());
            vo.setModelName(u.getModelName() == null ? "" : u.getModelName());
            vo.setBillingMode(billingModeLabel(u.getBillingMode()));
            vo.setPromptTokens(num(u.getPromptTokens()));
            vo.setCachedPromptTokens(num(u.getCachedPromptTokens()));
            vo.setCompletionTokens(num(u.getCompletionTokens()));
            vo.setTotalTokens(num(u.getTotalTokens()));
            vo.setLatencyMs(num(u.getLatencyMs()));
            vo.setBaseCostAmount(money(u.getBaseCostAmount()));
            vo.setServiceFeeAmount(money(u.getServiceFeeAmount()));
            vo.setCostAmount(money(u.getCostAmount()));
            vo.setCurrency(u.getCurrency() == null ? "CNY" : u.getCurrency());
            vo.setSuccess("1".equals(u.getSuccess()) ? "成功" : "失败");
            vo.setErrorMsg(u.getErrorMsg() == null ? "" : u.getErrorMsg());
            vos.add(vo);
        }
        ExcelUtil.exportExcel(vos, "AI用量明细", DcsAiUsageExportVo.class, response);
    }

    /** AI 缺口批 Q5：分析报表导出（按日×模型聚合行）。 */
    @SaCheckPermission("dcs:ai-config:query")
    @PostMapping("/export-analysis")
    public void exportAnalysis(jakarta.servlet.http.HttpServletResponse response,
                               @RequestParam(required = false) String beginDate,
                               @RequestParam(required = false) String endDate) {
        List<Map<String, Object>> rows = analysisService.dailyModelRows(org.dromara.common.satoken.utils.LoginHelper.getTenantId(), beginDate, endDate);
        List<AiUsageAnalysisExportVo> vos = new ArrayList<>(rows.size());
        for (Map<String, Object> r : rows) {
            AiUsageAnalysisExportVo vo = new AiUsageAnalysisExportVo();
            vo.setDay(str(r.get("day")));
            vo.setProvider(str(r.get("provider")));
            vo.setModelName(str(r.get("model_name")));
            vo.setCalls(str(r.get("calls")));
            vo.setSuccessCalls(str(r.get("success_calls")));
            vo.setAvgLatencyMs(money(r.get("avg_latency_ms")));
            vo.setCost(money(r.get("cost")));
            vos.add(vo);
        }
        ExcelUtil.exportExcel(vos, "AI用量分析", AiUsageAnalysisExportVo.class, response);
    }

    private static final Map<String, String> CALLER_LABELS = Map.ofEntries(
        Map.entry("ASSISTANT_CHAT", "助手会话"),
        Map.entry("PROFILE_DIAGNOSE", "画像诊断"),
        Map.entry("PROFILE_EMBEDDING", "画像向量化"),
        Map.entry("FIELD_GENERATE", "字段生成"),
        Map.entry("SCORE_ENHANCE", "评分增强"),
        Map.entry("SCORE_RULE_RECOMMEND", "评分规则推荐"),
        Map.entry("TEST_CONNECTION", "连接测试"),
        Map.entry("SPEED_PROBE", "测速"),
        Map.entry("PRICE_DETECT", "价格探测"),
        Map.entry("AI_CALL", "通用调用"));

    private static String callerLabel(String caller) {
        if (caller == null || caller.isBlank()) {
            return "--";
        }
        return CALLER_LABELS.getOrDefault(caller, caller);
    }

    private static String billingModeLabel(String mode) {
        if (mode == null || mode.isBlank()) {
            return "--";
        }
        return switch (mode) {
            case "OFFICIAL_TOKEN" -> "官方Token";
            case "FIXED_PER_CALL" -> "固定单价";
            case "OFFICIAL_PLUS_FEE" -> "官方价＋每次服务费";
            default -> mode;
        };
    }

    private static String fmt(Date d) {
        return d == null ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    private static String num(Number n) {
        return n == null ? "0" : String.valueOf(n);
    }

    private static String money(Object v) {
        if (v == null) {
            return "0.00";
        }
        try {
            return new BigDecimal(String.valueOf(v)).setScale(2, RoundingMode.HALF_UP).toPlainString();
        } catch (Exception e) {
            return String.valueOf(v);
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
