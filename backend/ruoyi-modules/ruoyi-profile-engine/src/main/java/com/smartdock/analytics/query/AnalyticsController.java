package com.smartdock.analytics.query;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.smartdock.analytics.domain.AnalyticsFunnel;
import com.smartdock.analytics.domain.AnalyticsSession;
import com.smartdock.analytics.domain.AnalyticsSite;
import com.smartdock.analytics.engine.AnalyticsIntakeService;
import com.smartdock.analytics.engine.AnalyticsService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.domain.R;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 分析中心接口。
 *
 * @author smartdock
 */
@RestController
@RequestMapping("/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final AnalyticsIntakeService intakeService;

    @SaCheckPermission("analytics:center:query")
    @GetMapping("/overview")
    public R<Map<String, Object>> overview(@RequestParam String source,
                                           @RequestParam(defaultValue = "30") int days) {
        return R.ok(analyticsService.overview(source, days));
    }

    @SaCheckPermission("analytics:center:query")
    @GetMapping("/trend")
    public R<List<Map<String, Object>>> trend(@RequestParam String source,
                                               @RequestParam(defaultValue = "30") int days) {
        return R.ok(analyticsService.trend(source, days));
    }

    @SaCheckPermission("analytics:center:query")
    @GetMapping("/breakdown")
    public R<Map<String, Object>> breakdown(@RequestParam String source,
                                            @RequestParam(defaultValue = "30") int days,
                                            @RequestParam(defaultValue = "channel") String type,
                                            @RequestParam(defaultValue = "20") int limit) {
        return R.ok(analyticsService.breakdown(source, days, type, limit));
    }

    @SaCheckPermission("analytics:center:query")
    @GetMapping("/sessions")
    public TableDataInfo<AnalyticsSession> sessions(AnalyticsSessionQuery query) {
        return TableDataInfo.build(analyticsService.sessionPage(query));
    }

    @SaCheckPermission("analytics:center:query")
    @GetMapping("/session/{sessionId}")
    public R<Map<String, Object>> session(@PathVariable Long sessionId) {
        return R.ok(analyticsService.sessionDetail(sessionId));
    }

    @SaCheckPermission("analytics:center:query")
    @GetMapping("/funnels")
    public R<List<AnalyticsFunnel>> funnels(@RequestParam String source) {
        return R.ok(analyticsService.funnels(source));
    }

    @SaCheckPermission("analytics:center:edit")
    @Log(title = "分析中心-漏斗", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/funnel/save")
    public R<AnalyticsFunnel> saveFunnel(@RequestBody AnalyticsFunnelBo bo) {
        return R.ok(analyticsService.saveFunnel(bo));
    }

    @SaCheckPermission("analytics:center:edit")
    @Log(title = "分析中心-漏斗", businessType = BusinessType.DELETE)
    @RepeatSubmit
    @PostMapping("/funnel/{funnelId}/delete")
    public R<Boolean> deleteFunnel(@PathVariable Long funnelId) {
        return R.ok(analyticsService.deleteFunnel(funnelId));
    }

    @SaCheckPermission("analytics:center:query")
    @PostMapping("/funnel/preview")
    public R<Map<String, Object>> funnelPreview(@RequestBody AnalyticsFunnelBo bo) {
        return R.ok(analyticsService.funnelAnalysis(bo));
    }

    @SaCheckPermission("analytics:center:query")
    @GetMapping("/retention")
    public R<Map<String, Object>> retention(@RequestParam String source,
                                            @RequestParam(defaultValue = "30") int days,
                                            @RequestParam(defaultValue = "7") int window) {
        return R.ok(analyticsService.retention(source, days, window));
    }

    @SaCheckPermission("analytics:center:query")
    @PostMapping("/custom")
    public R<Map<String, Object>> custom(@RequestBody AnalyticsCustomBo bo) {
        return R.ok(analyticsService.custom(bo));
    }

    @SaCheckPermission("analytics:center:edit")
    @Log(title = "分析中心-CSV导入", businessType = BusinessType.IMPORT)
    @RepeatSubmit
    @PostMapping("/import")
    public R<Map<String, Object>> importCsv(@RequestBody AnalyticsImportBo bo) {
        return R.ok(analyticsService.importCsv(bo));
    }

    @SaCheckPermission("analytics:center:edit")
    @Log(title = "分析中心-重建汇总", businessType = BusinessType.UPDATE)
    @RepeatSubmit
    @PostMapping("/rebuild")
    public R<Map<String, Object>> rebuild(@RequestParam String source,
                                          @RequestParam(defaultValue = "30") int days) {
        return R.ok(analyticsService.rebuild(source, days));
    }

    @SaCheckPermission("analytics:center:query")
    @GetMapping("/sites")
    public R<List<AnalyticsSite>> sites() {
        return R.ok(analyticsService.sites());
    }

    // ---- 数据接入页（2026-10-22 拍板·全按推荐）：建站 / 模拟 / 检测 ----

    /** 起名即建：siteKey 系统随机签发并随出参返回（界面只展示，用户无需手抄） */
    @SaCheckPermission("analytics:center:edit")
    @Log(title = "分析中心-新建站点", businessType = BusinessType.INSERT)
    @RepeatSubmit
    @PostMapping("/site/create")
    public R<AnalyticsSite> createSite(@Valid @RequestBody AnalyticsSiteCreateBo bo) {
        return R.ok(intakeService.createSite(bo));
    }

    /** 模拟访客流量（演示链路）：事件带 sim- 标记，可被 /simulate/clear 一键清掉 */
    @SaCheckPermission("analytics:center:edit")
    @Log(title = "分析中心-模拟访客", businessType = BusinessType.INSERT)
    @RepeatSubmit(interval = 3000)
    @PostMapping("/simulate")
    public R<Map<String, Object>> simulate(@RequestParam(defaultValue = "3") int visitors) {
        return R.ok(intakeService.simulate(visitors));
    }

    /** 清掉模拟数据并重建 web 源汇总（演示口径随之复原） */
    @SaCheckPermission("analytics:center:edit")
    @Log(title = "分析中心-清理模拟数据", businessType = BusinessType.CLEAN)
    @RepeatSubmit
    @PostMapping("/simulate/clear")
    public R<Map<String, Object>> clearSimulated() {
        return R.ok(intakeService.clearSimulated());
    }

    /** 检测接入：钥匙有效？测试事件收到？看板已有（非模拟）数据？ */
    @SaCheckPermission("analytics:center:edit")
    @Log(title = "分析中心-检测接入", businessType = BusinessType.OTHER)
    @RepeatSubmit(interval = 2000)
    @PostMapping("/site/detect")
    public R<Map<String, Object>> detect(@RequestParam String siteKey) {
        return R.ok(intakeService.detect(siteKey));
    }

    /**
     * 批7 采集质量报表：丢弃原因分布 / Top 机器人 / Top 跨域来源 / 最近留痕 / 丢弃率。
     */
    @SaCheckPermission("analytics:center:query")
    @GetMapping("/quality")
    public R<Map<String, Object>> quality(@RequestParam String source,
                                          @RequestParam(defaultValue = "7") int days) {
        return R.ok(analyticsService.quality(source, days));
    }

    /**
     * 批8 路径分析：页面桑基（start 为空 = 入口视角；非空 = 从该页出发）。
     */
    @SaCheckPermission("analytics:center:query")
    @GetMapping("/paths")
    public R<Map<String, Object>> paths(@RequestParam String source,
                                        @RequestParam(defaultValue = "30") int days,
                                        @RequestParam(required = false) String start) {
        return R.ok(analyticsService.paths(source, days, start));
    }

    /**
     * 批8 漏斗流失下钻：每步流失访客的下一页/下一事件 Top5。
     */
    @SaCheckPermission("analytics:center:query")
    @PostMapping("/funnel/loss")
    public R<Map<String, Object>> funnelLoss(@RequestBody AnalyticsFunnelBo bo) {
        return R.ok(analyticsService.funnelLoss(bo));
    }

    /**
     * 批8 渠道归因：首次/末次/线性三模型（event 为空 = signup）。
     */
    @SaCheckPermission("analytics:center:query")
    @GetMapping("/attribution")
    public R<Map<String, Object>> attribution(@RequestParam String source,
                                              @RequestParam(defaultValue = "30") int days,
                                              @RequestParam(required = false) String event) {
        return R.ok(analyticsService.attribution(source, days, event));
    }

    /**
     * 批8 活跃口径：DAU/WAU/MAU + 粘性 + 新老构成。
     */
    @SaCheckPermission("analytics:center:query")
    @GetMapping("/activity")
    public R<Map<String, Object>> activity(@RequestParam String source,
                                           @RequestParam(defaultValue = "30") int days) {
        return R.ok(analyticsService.activity(source, days));
    }

    /**
     * 批8 实时看板：近 5 分钟直查事件账本（前端 5 秒轮询）。
     */
    @SaCheckPermission("analytics:center:query")
    @GetMapping("/realtime")
    public R<Map<String, Object>> realtime(@RequestParam String source) {
        return R.ok(analyticsService.realtime(source));
    }

    /**
     * 批8 全域总览：跨业务源事件量/访客数/最近写入。
     */
    @SaCheckPermission("analytics:center:query")
    @GetMapping("/cross-source")
    public R<Map<String, Object>> crossSource(@RequestParam(defaultValue = "30") int days) {
        return R.ok(analyticsService.crossSource(days));
    }
}
