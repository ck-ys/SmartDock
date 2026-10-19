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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 助手记忆页接口（二站④ AU-53，2026-10-08）：本人长期记忆可查可删。
 * 权限=dcs:assistant:memory:manage（菜单 11922，全员分配——端点内只操作本人数据）。
 */
@Tag(name = "助手长期记忆", description = "本人长期记忆可查可删")
@RestController
@RequestMapping("/dcs/assistant/memory")
@RequiredArgsConstructor
public class AssistantMemoryController extends BaseController {

    private final AssistantMemoryService memoryService;

    /** 我的记忆清单（新→旧） */
    @SaCheckPermission("dcs:assistant:memory:manage")
    @GetMapping("/list")
    public R<List<DcsAssistantMemory>> list() {
        return R.ok(memoryService.listForUser(LoginHelper.getTenantId(), LoginHelper.getUserId()));
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
