package org.dromara.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.domain.DcsMcpCallLog;
import org.dromara.assistant.domain.DcsMcpServer;
import org.dromara.assistant.mapper.DcsMcpCallLogMapper;
import org.dromara.assistant.service.McpPilotService;
import org.dromara.common.core.domain.R;
import org.dromara.common.log.annotation.Log;
import org.dromara.common.log.enums.BusinessType;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.mybatis.core.page.PageQuery;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 批2 Q2=A（2026-10-21）：管理台「外部工具」页签接口（MCP 试点）。
 * 权限复用 dcs:assistant:config（与工具开关同页同权限域，零菜单 SQL）；
 * 铁律#21：全部 GET/POST。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/dcs/assistant/mcp")
public class AssistantMcpController extends BaseController {

    private final McpPilotService mcpPilotService;
    private final DcsMcpCallLogMapper callLogMapper;
    private final org.dromara.assistant.service.AssistantConfigService assistantConfigService;

    /** 服务行 VO：headers 只回键名不回值（免密钥试点仍防误回显） */
    public record McpServerRow(Long id, String serverName, String displayName, String transportType,
                               String command, String args, String url, String headerKeys,
                               String enabled, String readOnly, String remark, String updateTime) {
    }

    /**
     * 成员可见行（旧板题 2 乙 2026-11-06）：清单 + 带来的工具 + 现在能不能用（外部服务恒标高危）。
     * tools 来自真实调用账本的去重工具名（最近 90 天），不是注册时猜的。
     */
    public record McpMemberRow(String serverName, String displayName, String transportType, String readOnly,
                               String status, String statusText, String whoCanUse,
                               java.util.List<String> tools) {
    }

    /**
     * 成员只读清单：只回**已启用**的服务（停用的对成员等于不存在）。
     * 状态口径与工具清单（AssistantToolViewerController）一致：租户闸 off → 已停用；
     * 闸 admin → 管理员限定；tool_gate 污染 → fail-closed。
     */
    @SaCheckPermission("dcs:assistant:use")
    @GetMapping("/member-list")
    public R<java.util.List<McpMemberRow>> memberList() {
        String dynamic = org.dromara.common.tenant.helper.TenantHelper.getTenantId();
        String tenantId = org.dromara.common.core.utils.StringUtils.isNotBlank(dynamic)
            ? dynamic : LoginHelper.getTenantId();
        var cfg = assistantConfigService.effective(tenantId);
        java.util.Map<String, String> gate = cfg.toolGate();
        boolean poisoned = gate != null
            && gate.containsKey(org.dromara.assistant.service.AssistantConfigService.POISONED_GATE_SENTINEL);
        // 工具名按本租户真实调用去重（排除每次建会话都写的挂载行）——没有账本的服务 tools 为空
        java.util.Map<String, java.util.List<String>> toolsByServer = new java.util.HashMap<>();
        try {
            java.util.List<java.util.Map<String, Object>> maps = callLogMapper.selectMaps(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<DcsMcpCallLog>()
                    .select("server_name", "tool_name")
                    .eq("tenant_id", tenantId)
                    // 三审 P2：javadoc 写的「最近 90 天」此前没落进 SQL——账本 append-only，
                    // 不设窗口会让已删服务的历史工具名永久回显，且每次打开页都全量 group by
                    .ge("create_time", java.time.LocalDateTime.now().minusDays(90))
                    .notIn("action", java.util.List.of("MOUNT_OK", "MOUNT_ERROR"))
                    .isNotNull("tool_name")
                    .groupBy("server_name", "tool_name"));
            for (java.util.Map<String, Object> m : maps) {
                Object server = m.get("server_name") != null ? m.get("server_name") : m.get("SERVER_NAME");
                Object tool = m.get("tool_name") != null ? m.get("tool_name") : m.get("TOOL_NAME");
                if (server == null || tool == null) {
                    continue;
                }
                java.util.List<String> list = toolsByServer.computeIfAbsent(
                    String.valueOf(server), k -> new java.util.ArrayList<>());
                if (list.size() < 20) {
                    list.add(String.valueOf(tool));
                }
            }
        } catch (Exception e) {
            // 账本读失败：清单照常返回，工具名留空（不冒充「没有工具」）
            org.slf4j.LoggerFactory.getLogger(AssistantMcpController.class)
                .warn("[MCP] 成员工具名读取失败，本次只回服务清单", e);
        }
        java.util.List<McpMemberRow> rows = new java.util.ArrayList<>();
        for (DcsMcpServer s : org.dromara.common.tenant.helper.TenantHelper.ignore(mcpPilotService::listAll)) {
            if (!"1".equals(s.getEnabled())) {
                continue;
            }
            String override = gate == null ? null : gate.get("mcp:" + s.getServerName());
            int effective = "off".equals(override) ? -1
                : "admin".equals(override) ? org.dromara.assistant.tools.ToolIdentity.LEVEL_ADMIN
                : org.dromara.assistant.tools.ToolIdentity.LEVEL_MEMBER;
            if (poisoned) {
                effective = -1;
            }
            String status;
            String statusText;
            if (effective < 0) {
                status = "off";
                statusText = "已停用";
            } else if (effective >= org.dromara.assistant.tools.ToolIdentity.LEVEL_ADMIN) {
                status = "admin";
                statusText = "管理员限定";
            } else {
                status = "ready";
                statusText = "可用";
            }
            String who = effective < 0 ? "—"
                : effective >= org.dromara.assistant.tools.ToolIdentity.LEVEL_ADMIN ? "管理员" : "成员";
            rows.add(new McpMemberRow(s.getServerName(),
                s.getDisplayName() == null || s.getDisplayName().isBlank() ? s.getServerName() : s.getDisplayName(),
                s.getTransportType(), s.getReadOnly(), status, statusText, who,
                toolsByServer.getOrDefault(s.getServerName(), java.util.List.of())));
        }
        return R.ok(rows);
    }

    @SaCheckPermission("dcs:assistant:config")
    @GetMapping("/list")
    public R<List<McpServerRow>> list() {
        // 三审 P2：平台级配置的取数口径与 member-list 统一——save() 恒写 000000 租户，
        // 超管切到别家租户后若不 ignore，管理清单会空而成员清单照旧有行，自相矛盾
        List<McpServerRow> rows = org.dromara.common.tenant.helper.TenantHelper
            .ignore(mcpPilotService::listAll).stream().map(s -> new McpServerRow(
            s.getId(), s.getServerName(), s.getDisplayName(), s.getTransportType(),
            s.getCommand(), s.getArgs(), s.getUrl(),
            // G4-#1：落库是 ENC_ 密文——先解密再取键名（service 内同款显式解密，与挂载链路一致）
            mcpPilotService.headerKeysOf(s), s.getEnabled(), s.getReadOnly(), s.getRemark(),
            s.getUpdateTime() == null ? null : String.valueOf(s.getUpdateTime().getTime()))).toList();
        return R.ok(rows);
    }

    /** 三审回修（dspro P1-3）：平台级配置仅超管可管理（当前权限本就只授超管，此处补结构性闸） */
    private void requireSuperAdmin() {
        if (!LoginHelper.isSuperAdmin()) {
            throw new org.dromara.common.core.exception.ServiceException("外部工具为平台级配置，仅超级管理员可管理");
        }
    }

    @SaCheckPermission("dcs:assistant:config")
    @Log(title = "AI助手-外部工具", businessType = BusinessType.INSERT, isSaveRequestData = false)
    @PostMapping("/save")
    public R<Void> save(@RequestBody DcsMcpServer row) {
        requireSuperAdmin();
        mcpPilotService.save(row);
        return R.ok();
    }

    @SaCheckPermission("dcs:assistant:config")
    @Log(title = "AI助手-外部工具", businessType = BusinessType.UPDATE)
    @PostMapping("/toggle")
    public R<Void> toggle(@RequestParam Long id, @RequestParam String enabled) {
        requireSuperAdmin();
        mcpPilotService.toggle(id, enabled);
        return R.ok();
    }

    @SaCheckPermission("dcs:assistant:config")
    @Log(title = "AI助手-外部工具", businessType = BusinessType.DELETE)
    @PostMapping("/delete")
    public R<Void> delete(@RequestParam Long id) {
        requireSuperAdmin();
        mcpPilotService.delete(id);
        return R.ok();
    }

    @SaCheckPermission("dcs:assistant:config")
    @GetMapping("/calllog")
    public R<java.util.Map<String, Object>> callLog(@RequestParam(required = false) String serverName,
                                                    @RequestParam(required = false) Boolean realOnly,
                                                    PageQuery pageQuery) {
        // 三审回修（dspro P1-4）：非超管只看本租户审计（超管全量）；pageSize 兜 cap 防
        // PageQuery 缺省 MAX_VALUE 全表扫（dsflash P2）
        boolean superAdmin = LoginHelper.isSuperAdmin();
        pageQuery.setPageSize(Math.min(pageQuery.getPageSize() == null || pageQuery.getPageSize() > 200 ? 20 : pageQuery.getPageSize(), 200));
        LambdaQueryWrapper<DcsMcpCallLog> q = new LambdaQueryWrapper<DcsMcpCallLog>()
            .eq(!superAdmin, DcsMcpCallLog::getTenantId, LoginHelper.getTenantId())
            .eq(serverName != null && !serverName.isBlank(), DcsMcpCallLog::getServerName, serverName)
            // 管理页默认只看真实调用与失败：挂载成功/失败是每次建会话的例行记录，
            // 混在调用里会把真正要看的一屏淹掉。realOnly=true 时排除两条 MOUNT_*。
            .notIn(Boolean.TRUE.equals(realOnly), DcsMcpCallLog::getAction, java.util.List.of("MOUNT_OK", "MOUNT_ERROR"))
            .orderByDesc(DcsMcpCallLog::getCreateTime);
        var page = callLogMapper.selectPage(pageQuery.build(), q);
        // 摘要三格：全部 / 挂载记录 / 真实调用——同 where 口径计数，前端不必自己猜占比
        long all = callLogMapper.selectCount(new LambdaQueryWrapper<DcsMcpCallLog>()
            .eq(!superAdmin, DcsMcpCallLog::getTenantId, LoginHelper.getTenantId())
            .eq(serverName != null && !serverName.isBlank(), DcsMcpCallLog::getServerName, serverName));
        long mounts = callLogMapper.selectCount(new LambdaQueryWrapper<DcsMcpCallLog>()
            .eq(!superAdmin, DcsMcpCallLog::getTenantId, LoginHelper.getTenantId())
            .eq(serverName != null && !serverName.isBlank(), DcsMcpCallLog::getServerName, serverName)
            .in(DcsMcpCallLog::getAction, java.util.List.of("MOUNT_OK", "MOUNT_ERROR")));
        // R.ok 统一包装（同 /list 口径）——TableDataInfo 顶层 rows 结构与前端 request 层解析不符（老坑）
        return R.ok(java.util.Map.of(
            "rows", page.getRecords(),
            "total", page.getTotal(),
            "stats", java.util.Map.of("all", all, "mounts", mounts, "real", all - mounts)));
    }
}
