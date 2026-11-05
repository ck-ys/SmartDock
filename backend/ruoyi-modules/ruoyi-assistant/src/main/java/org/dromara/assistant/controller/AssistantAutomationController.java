package org.dromara.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.automation.domain.AutomationBo;
import org.dromara.assistant.automation.domain.vo.AutomationRunVo;
import org.dromara.assistant.automation.domain.vo.AutomationVo;
import org.dromara.assistant.automation.service.AssistantAutomationService;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.domain.model.LoginUser;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
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
 * AI 助手自动化端点（2026-11-05 拍板 题7 乙）。
 *
 * <p>可见性：只看自己的自建项 + 本租户内置项；内置项开关走管理权限
 * {@code dcs:assistant:config}（成员看到卡片但开关不可点）。</p>
 */
@Tag(name = "助手自动化", description = "到点跑一轮只读 AI 任务：列表/新建/编辑/开关/立即运行/运行历史")
@RestController
@RequestMapping("/dcs/assistant/automations")
@RequiredArgsConstructor
public class AssistantAutomationController extends BaseController {

    private final AssistantAutomationService automationService;

    @SaCheckPermission("dcs:assistant:automation")
    @GetMapping
    public R<List<AutomationVo>> list(@RequestParam(required = false) String filter) {
        return R.ok(automationService.list(LoginHelper.getTenantId(), LoginHelper.getUserId(),
            filter, canManageBuiltin()));
    }

    /** 未来 5 次预览（新建/编辑页日程卡；不落库） */
    @SaCheckPermission("dcs:assistant:automation")
    @GetMapping("/preview")
    public R<List<String>> preview(@RequestParam String scheduleType,
                                   @RequestParam String scheduleTime,
                                   @RequestParam(required = false) String weekdays) {
        return R.ok(automationService.preview(scheduleType, scheduleTime, weekdays));
    }

    /** 最近运行（跨本人自建项 + 内置摘要） */
    @SaCheckPermission("dcs:assistant:automation")
    @GetMapping("/runs/recent")
    public R<List<AutomationRunVo>> recentRuns(@RequestParam(defaultValue = "5") int limit) {
        return R.ok(automationService.recentRuns(LoginHelper.getTenantId(), LoginHelper.getUserId(), limit));
    }

    @SaCheckPermission("dcs:assistant:automation")
    @GetMapping("/{id}")
    public R<AutomationVo> detail(@PathVariable Long id) {
        return R.ok(automationService.detail(LoginHelper.getTenantId(), LoginHelper.getUserId(),
            id, canManageBuiltin()));
    }

    @SaCheckPermission("dcs:assistant:automation")
    @GetMapping("/{id}/runs")
    public R<List<AutomationRunVo>> runs(@PathVariable Long id,
                                         @RequestParam(defaultValue = "10") int limit) {
        return R.ok(automationService.runs(LoginHelper.getTenantId(), LoginHelper.getUserId(), id, limit));
    }

    @SaCheckPermission("dcs:assistant:automation")
    @Log(title = "AI助手-自动化", businessType = BusinessType.INSERT)
    @PostMapping
    public R<AutomationVo> create(@RequestBody AutomationBo bo) {
        return R.ok(automationService.create(LoginHelper.getTenantId(), LoginHelper.getUserId(), bo));
    }

    @SaCheckPermission("dcs:assistant:automation")
    @Log(title = "AI助手-自动化", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}")
    public R<AutomationVo> update(@PathVariable Long id, @RequestBody AutomationBo bo) {
        return R.ok(automationService.update(LoginHelper.getTenantId(), LoginHelper.getUserId(), id, bo));
    }

    @SaCheckPermission("dcs:assistant:automation")
    @Log(title = "AI助手-自动化", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/toggle")
    public R<AutomationVo> toggle(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        return R.ok(automationService.toggle(LoginHelper.getTenantId(), LoginHelper.getUserId(), id, enabled));
    }

    @SaCheckPermission("dcs:assistant:automation")
    @Log(title = "AI助手-自动化", businessType = BusinessType.DELETE)
    @PostMapping("/{id}/delete")
    public R<Void> delete(@PathVariable Long id) {
        automationService.delete(LoginHelper.getTenantId(), LoginHelper.getUserId(), id);
        return R.ok();
    }

    /** 立即运行一次（题6 甲：失败不自动重试，手动重跑走这里） */
    @SaCheckPermission("dcs:assistant:automation")
    @Log(title = "AI助手-自动化", businessType = BusinessType.OTHER)
    @PostMapping("/{id}/run")
    public R<Map<String, Object>> run(@PathVariable Long id) {
        Long runId = automationService.runNow(LoginHelper.getTenantId(), LoginHelper.getUserId(), id);
        return R.ok(Map.of("runId", runId));
    }

    /** 内置每日摘要开关（写租户 digest_enabled） */
    @SaCheckPermission("dcs:assistant:config")
    @Log(title = "AI助手-自动化", businessType = BusinessType.UPDATE)
    @PostMapping("/builtin/toggle")
    public R<AutomationVo> toggleBuiltin(@RequestBody Map<String, Object> body) {
        boolean enabled = Boolean.parseBoolean(String.valueOf(body.get("enabled")));
        return R.ok(automationService.toggleBuiltin(LoginHelper.getTenantId(), LoginHelper.getUserId(),
            enabled, true));
    }

    private boolean canManageBuiltin() {
        LoginUser user = LoginHelper.getLoginUser();
        if (user == null || user.getMenuPermission() == null) {
            return false;
        }
        return user.getMenuPermission().contains("*:*:*")
            || user.getMenuPermission().contains("dcs:assistant:config");
    }
}
