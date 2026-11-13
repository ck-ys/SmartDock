package org.dromara.assistant.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.domain.DcsAssistantMemory;
import org.dromara.assistant.service.AssistantMemoryService;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * AI 助手记忆页接口（二站④ AU-53，2026-10-08；ckys3 解锁板题7~题10 扩，2026-11-12）：
 * 本人长期记忆可查、可改、可删、可导出、可清空，并可单独开关。
 * 权限=dcs:assistant:memory:manage（菜单 11922，全员分配——端点内只操作本人数据）。
 */
@Tag(name = "助手长期记忆", description = "本人长期记忆可查可改可删可导出")
@RestController
@RequestMapping("/dcs/assistant/memory")
@RequiredArgsConstructor
public class AssistantMemoryController extends BaseController {

    private final AssistantMemoryService memoryService;

    /** 我的记忆清单（生效中，新→旧） */
    @SaCheckPermission("dcs:assistant:memory:manage")
    @GetMapping("/list")
    public R<List<DcsAssistantMemory>> list() {
        return R.ok(memoryService.listForUser(LoginHelper.getTenantId(), LoginHelper.getUserId()));
    }

    /** 历史清单（题7/题8/题10：被同主题替代 + 90 天归档；新→旧） */
    @SaCheckPermission("dcs:assistant:memory:manage")
    @GetMapping("/history")
    public R<List<DcsAssistantMemory>> history() {
        return R.ok(memoryService.listHistory(LoginHelper.getTenantId(), LoginHelper.getUserId()));
    }

    /** 条数用量（题7「3 / 50 条」） */
    @SaCheckPermission("dcs:assistant:memory:manage")
    @GetMapping("/usage")
    public R<Map<String, Integer>> usage() {
        String tenantId = LoginHelper.getTenantId();
        Long userId = LoginHelper.getUserId();
        return R.ok(Map.of(
            "count", memoryService.countActive(tenantId, userId),
            "max", AssistantMemoryService.MAX_PER_USER));
    }

    /** 行内编辑（题7）：与保存口同闸（长度+脱敏+去重+属主谓词） */
    @SaCheckPermission("dcs:assistant:memory:manage")
    @Log(title = "AI助手-编辑记忆", businessType = BusinessType.UPDATE)
    @PostMapping("/{id}/edit")
    public R<Void> edit(@PathVariable Long id, @RequestBody Map<String, String> body) {
        boolean ok = memoryService.edit(LoginHelper.getTenantId(), LoginHelper.getUserId(),
            id, body == null ? null : body.get("content"));
        return ok ? R.ok() : R.fail("没有更新（内容为空/重复/不属于你）");
    }

    /** 个人开关读取（题10） */
    @SaCheckPermission("dcs:assistant:memory:manage")
    @GetMapping("/pref")
    public R<Map<String, Boolean>> pref() {
        boolean enabled = memoryService.isUserEnabled(LoginHelper.getTenantId(), LoginHelper.getUserId());
        return R.ok(Map.of("enabled", enabled));
    }

    /** 个人开关（题10）：与租户总闸与运算，租户关=全关 */
    @SaCheckPermission("dcs:assistant:memory:manage")
    @Log(title = "AI助手-记忆开关", businessType = BusinessType.UPDATE)
    @PostMapping("/pref")
    public R<Map<String, Boolean>> setPref(@RequestBody Map<String, Boolean> body) {
        boolean enabled = body != null && Boolean.TRUE.equals(body.get("enabled"));
        memoryService.setUserEnabled(LoginHelper.getTenantId(), LoginHelper.getUserId(), enabled);
        return R.ok(Map.of("enabled", enabled));
    }

    /** 导出本人全部记忆（题10：含历史/归档；前端存 JSON 文件） */
    @SaCheckPermission("dcs:assistant:memory:manage")
    @GetMapping("/export")
    public R<List<DcsAssistantMemory>> export() {
        return R.ok(memoryService.exportRows(LoginHelper.getTenantId(), LoginHelper.getUserId()));
    }

    /** 清空本人全部记忆（题10：含历史；二次确认在前端） */
    @SaCheckPermission("dcs:assistant:memory:manage")
    @Log(title = "AI助手-清空记忆", businessType = BusinessType.DELETE)
    @PostMapping("/clear")
    public R<Map<String, Integer>> clear() {
        int n = memoryService.clearAll(LoginHelper.getTenantId(), LoginHelper.getUserId());
        return R.ok(Map.of("deleted", n));
    }

    /** 删除一条我的记忆（属主谓词在 SQL，删他人=0 行）；铁律#21：DELETE→POST 保持路径（2026-10-16 批17 Q4，前端已同步） */
    @SaCheckPermission("dcs:assistant:memory:manage")
    @Log(title = "AI助手-删除记忆", businessType = BusinessType.DELETE)
    @PostMapping("/{id}")
    public R<Void> remove(@PathVariable Long id) {
        boolean ok = memoryService.remove(LoginHelper.getTenantId(), LoginHelper.getUserId(), id);
        return ok ? R.ok() : R.fail("该记忆不存在或已删除");
    }
}
