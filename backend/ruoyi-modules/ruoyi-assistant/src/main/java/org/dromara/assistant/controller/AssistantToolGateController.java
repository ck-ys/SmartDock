package org.dromara.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.core.ToolRegistry;
import org.dromara.assistant.service.AssistantConfigService;
import org.dromara.common.core.domain.R;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.springframework.web.bind.annotation.PostMapping;
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
 * GET=合并视图（前端零拼装）；POST=整列覆盖（值域/工具名校验，只升不降由
 * effectiveMinRole 的 max 语义结构性保证）。生效时机=下一轮建会话。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/dcs/assistant/toolgate")
public class AssistantToolGateController extends BaseController {

    private final AssistantConfigService configService;
    private final org.dromara.assistant.service.McpPilotService mcpPilotService;

    /** 工具行（合并视图）：spec 粒度一行，toolNames 逗连便于前端展示与批量操作。
     *  G4-#4：MCP 服务以 server 粒度并入同一视图（键 mcp:<server>，内置档=member，
     *  off=跳过挂载、admin=仅管理员角色；askConfirm=服务含写类工具（read_only='0'）） */
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
        // G4-#4：MCP 外部服务并入闸矩阵视图（每服务一行；effective：off=-1/其余取覆盖或 member）。
        // 平台级表（tenant 恒 000000）——ignore 跨租户读，非 000000 租户管理员同样看得到全名单
        for (org.dromara.assistant.domain.DcsMcpServer s :
            org.dromara.common.tenant.helper.TenantHelper.ignore(mcpPilotService::listAll)) {
            String key = "mcp:" + s.getServerName();
            String override = gate.get(key);
            int effective = "off".equals(override) ? -1
                : "admin".equals(override) ? org.dromara.assistant.tools.ToolIdentity.LEVEL_ADMIN
                : org.dromara.assistant.tools.ToolIdentity.LEVEL_MEMBER;
            rows.add(new ToolGateRow(
                List.of(key), s.getServerName(), "外部工具",
                "MCP 外部服务（" + s.getTransportType() + "）；off=本租户不挂载，admin=仅管理员可用",
                org.dromara.assistant.tools.ToolIdentity.LEVEL_MEMBER,
                override, effective, false, "0".equals(s.getReadOnly())));
        }
        return R.ok(rows);
    }

    @SaCheckPermission("dcs:assistant:config")
    @Log(title = "AI助手-工具开关", businessType = BusinessType.UPDATE)
    @PostMapping
    public R<Void> save(@RequestBody Map<String, String> gate) {
        Set<String> known = ToolRegistry.allToolNames();
        Set<String> mcpServers;
        try {
            mcpServers = mcpPilotService.allServerNames();
        } catch (Exception e) {
            throw new org.dromara.common.core.exception.ServiceException("外部服务名单查询失败，稍后再试");
        }
        for (Map.Entry<String, String> e : gate.entrySet()) {
            String k = e.getKey();
            boolean isMcp = k != null && k.startsWith("mcp:");
            if (isMcp ? !mcpServers.contains(k.substring(4)) : !known.contains(k)) {
                throw new org.dromara.common.core.exception.ServiceException("未知工具：" + k);
            }
            String v = e.getValue();
            if (!"off".equals(v) && !"member".equals(v) && !"admin".equals(v)) {
                throw new org.dromara.common.core.exception.ServiceException(
                    "工具「" + k + "」档位只支持 off/member/admin");
            }
        }
        configService.updateToolGate(org.dromara.common.satoken.utils.LoginHelper.getTenantId(), gate);
        return R.ok();
    }
}
