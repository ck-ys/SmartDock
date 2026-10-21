package org.dromara.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.core.ToolRegistry;
import org.dromara.assistant.service.AssistantConfigService;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 批1 Q1=B（2026-10-21 开关矩阵）：租户级工具档位管理接口。
 * 权限复用 dcs:assistant:config（助手管理既有编辑权，零菜单 SQL）。
 * GET=合并视图（前端零拼装）；PUT=整列覆盖（值域/工具名校验，只升不降由
 * effectiveMinRole 的 max 语义结构性保证）。生效时机=下一轮建会话。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/dcs/assistant/toolgate")
public class AssistantToolGateController extends BaseController {

    private final AssistantConfigService configService;

    /** 工具行（合并视图）：spec 粒度一行，toolNames 逗连便于前端展示与批量操作 */
    public record ToolGateRow(List<String> toolNames, String displayName, String category,
                              String gateDesc, int builtinLevel, String override, int effective,
                              boolean locked, boolean askConfirm) {
    }

    @SaCheckPermission("dcs:assistant:config")
    @GetMapping
    public R<List<ToolGateRow>> view() {
        Map<String, String> gate = configService.effective(org.dromara.common.satoken.utils.LoginHelper.getTenantId()).toolGate();
        List<ToolGateRow> rows = new ArrayList<>();
        for (ToolRegistry.ToolSpec spec : ToolRegistry.SPECS_VIEW()) {
            String firstName = spec.toolNames().iterator().next();
            ToolRegistry.ToolMeta meta = ToolRegistry.TOOL_META.get(firstName);
            int effective = ToolRegistry.effectiveMinRole(spec, gate);
            rows.add(new ToolGateRow(
                List.copyOf(spec.toolNames()),
                meta != null ? meta.displayName() : firstName,
                meta != null ? meta.category() : "其他",
                meta != null ? meta.gateDesc() : "",
                spec.minRoleLevel(),
                gate.get(firstName),
                effective,
                spec.minRoleLevel() >= org.dromara.assistant.tools.ToolIdentity.LEVEL_ADMIN,
                spec.behavior() == io.agentscope.core.permission.PermissionBehavior.ASK));
        }
        return R.ok(rows);
    }

    @SaCheckPermission("dcs:assistant:config")
    @PutMapping
    public R<Void> save(@RequestBody Map<String, String> gate) {
        Set<String> known = ToolRegistry.allToolNames();
        for (Map.Entry<String, String> e : gate.entrySet()) {
            if (!known.contains(e.getKey())) {
                throw new org.dromara.common.core.exception.ServiceException("未知工具：" + e.getKey());
            }
            String v = e.getValue();
            if (!"off".equals(v) && !"member".equals(v) && !"admin".equals(v)) {
                throw new org.dromara.common.core.exception.ServiceException(
                    "工具「" + e.getKey() + "」档位只支持 off/member/admin");
            }
        }
        configService.updateToolGate(org.dromara.common.satoken.utils.LoginHelper.getTenantId(), gate);
        return R.ok();
    }
}
