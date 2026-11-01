package org.dromara.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.domain.AssistantActionLogVo;
import org.dromara.common.core.domain.R;
import org.dromara.assistant.service.AssistantActionService;
import org.dromara.common.idempotent.annotation.RepeatSubmit;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.mybatis.core.page.TableDataInfo;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.List;

/**
 * 助手运行记录/写操作审计端点（二站① AU-52 + 三批③ 2026-11-01）。
 * 运行记录（/action/log）：动作账本按租户倒序分页；写操作审计（/action/audit/*）：
 * 四维筛选（成员昵称/工具/放行方式/时间闭区间）+ CSV 导出，args_json 不出网。
 *
 * <p><b>本文件三类租户源口径表（施工者照抄最近端点前先看这张表）</b>：</p>
 * <ul>
 *   <li>/action/log（既有，AU-52）= {@code LoginHelper.getTenantId()} 登录快照——
 *       管理页页签与管理权限面同源（dcs:assistant:config）；</li>
 *   <li>/action/audit/page + /action/audit/export（三批③）= {@code TenantHelper.getTenantId()}
 *       动态优先（超管切租户看切换后租户，与 stop 端点同口径）——审计页是独立菜单
 *       （11936，perm=dcs:assistant:audit:query）；</li>
 *   <li>/action/revoke（三批④撤回）= {@code LoginHelper.getLoginUser().getTenantId()}
 *       快照——与账本执行行 tenant_id 同源（执行行租户=建 agent 登录快照）。</li>
 * </ul>
 *
 * <p>只读端点无 @Log（查询不留操作日志，与 feedback/list 同口径；动作本身在账本表里
 * 就是留痕主体）；导出是敏感动作，@Log(EXPORT)+@RepeatSubmit 留痕防重。</p>
 */
@Tag(name = "助手运行记录与写操作审计", description = "动作账本分页（运行记录页签）+ 写操作审计四维查询/CSV 导出")
@RestController
@RequestMapping("/dcs/assistant")
@RequiredArgsConstructor
public class AssistantActionController extends BaseController {

    private final AssistantActionService actionService;

    /** 管理端运行记录分页（时间/动作/状态/结果/操作人）
     * 2026-10-11 日常查询：keyword 模糊命中 动作/结果摘要/操作人，status 精确，beginTime/endTime 闭区间（yyyy-MM-dd HH:mm:ss） */
    @SaCheckPermission("dcs:assistant:config")
    @GetMapping("/action/log")
    public TableDataInfo<AssistantActionLogVo> list(
        @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String beginTime,
        @RequestParam(required = false) String endTime) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        List<AssistantActionLogVo> rows = actionService.listLogForTenant(
            LoginHelper.getTenantId(), safeSize, (safePage - 1) * safeSize, keyword, status, beginTime, endTime);
        TableDataInfo<AssistantActionLogVo> data = TableDataInfo.build(rows);
        data.setTotal(actionService.countLogForTenant(LoginHelper.getTenantId(), keyword, status, beginTime, endTime));
        return data;
    }

    /**
     * 三批③：写操作审计分页（独立菜单 11936）——四维筛选：keyword=成员昵称模糊、
     * toolName/releaseMode 精确、beginTime/endTime 闭区间（yyyy-MM-dd HH:mm:ss）。
     * 租户=TenantHelper 动态优先（见类头口径表）；args_json 不出网。
     */
    @SaCheckPermission("dcs:assistant:audit:query")
    @GetMapping("/action/audit/page")
    public TableDataInfo<AssistantActionLogVo> auditPage(
        @RequestParam(defaultValue = "1") int pageNum, @RequestParam(defaultValue = "20") int pageSize,
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String toolName,
        @RequestParam(required = false) String releaseMode,
        @RequestParam(required = false) String beginTime,
        @RequestParam(required = false) String endTime) {
        int safePage = Math.max(1, pageNum);
        int safeSize = Math.min(Math.max(1, pageSize), 100);
        String tenantId = TenantHelper.getTenantId();
        List<AssistantActionLogVo> rows = actionService.listAuditForTenant(tenantId, safeSize,
            (safePage - 1) * safeSize, keyword, toolName, releaseMode, beginTime, endTime);
        TableDataInfo<AssistantActionLogVo> data = TableDataInfo.build(rows);
        data.setTotal(actionService.countAuditForTenant(tenantId, keyword, toolName, releaseMode, beginTime, endTime));
        return data;
    }

    /**
     * 三批③：审计 CSV 导出——与分页同筛选口径；列=时间/成员/工具/放行方式/状态/结果摘要
     * （无 args_json）；BOM+转义+5 万行上限超限报错引导缩窗（照 AnalyticsController.exportSessions
     * 口径）。POST+表单与前端 download() 管道对齐。
     */
    @SaCheckPermission("dcs:assistant:audit:query")
    @Log(title = "助手写操作审计导出", businessType = BusinessType.EXPORT)
    @RepeatSubmit
    @PostMapping("/action/audit/export")
    public void exportAudit(
        @RequestParam(required = false) String keyword,
        @RequestParam(required = false) String toolName,
        @RequestParam(required = false) String releaseMode,
        @RequestParam(required = false) String beginTime,
        @RequestParam(required = false) String endTime,
        HttpServletResponse response) throws IOException {
        byte[] csv = actionService.exportAuditCsv(TenantHelper.getTenantId(),
            keyword, toolName, releaseMode, beginTime, endTime);
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader("Content-Disposition",
            "attachment; filename=assistant-action-audit-" + System.currentTimeMillis() + ".csv");
        response.getOutputStream().write(csv);
        response.getOutputStream().flush();
    }

    /**
     * 三批④（2026-11-01）：撤回自己会话里 AI 自动执行的操作（完成卡撤回按钮）。
     *
     * <p><b>租户源钉死=登录快照</b> {@code LoginHelper.getLoginUser().getTenantId()}——
     * 与账本执行行 tenant_id 同源（ToolIdentity.from 建 agent 时快照→hook 建行同值），
     * <b>不得照抄最近审计端点的动态值</b>：超管切租户态动态 B≠快照 A 会查不到行→403，
     * 可撤回功能失效（fail-closed 不误删）。见类头三面口径表。</p>
     */
    @SaCheckPermission("dcs:assistant:use")
    @Log(title = "AI助手-撤回操作", businessType = BusinessType.DELETE)
    @RepeatSubmit(interval = 1000)
    @PostMapping("/action/revoke")
    public R<String> revoke(@RequestBody RevokeBody body) {
        if (body == null || body.getId() == null || body.getId().isBlank()) {
            return R.fail("参数不完整（id 必填）");
        }
        Long id;
        try {
            id = Long.parseLong(body.getId().trim());
        } catch (NumberFormatException e) {
            return R.fail("id 不是合法的操作编号");
        }
        return R.ok(actionService.revoke(LoginHelper.getLoginUser().getTenantId(),
            LoginHelper.getUserId(), id));
    }

    /** 撤回请求体：id=动作账本行雪花 id（19 位超 JS 安全整数，字符串承载） */
    @lombok.Data
    public static class RevokeBody {
        private String id;
    }
}
