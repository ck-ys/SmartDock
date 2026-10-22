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

    /** 服务行 VO：headers 只回键名不回值（免密钥试点仍防误回显） */
    public record McpServerRow(Long id, String serverName, String displayName, String transportType,
                               String command, String args, String url, String headerKeys,
                               String enabled, String remark, String updateTime) {
    }

    @SaCheckPermission("dcs:assistant:config")
    @GetMapping("/list")
    public R<List<McpServerRow>> list() {
        List<McpServerRow> rows = mcpPilotService.listAll().stream().map(s -> new McpServerRow(
            s.getId(), s.getServerName(), s.getDisplayName(), s.getTransportType(),
            s.getCommand(), s.getArgs(), s.getUrl(),
            // G4-#1：落库是 ENC_ 密文——先解密再取键名（service 内同款显式解密，与挂载链路一致）
            mcpPilotService.headerKeysOf(s), s.getEnabled(), s.getRemark(),
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
    public R<java.util.Map<String, Object>> callLog(@RequestParam(required = false) String serverName, PageQuery pageQuery) {
        // 三审回修（dspro P1-4）：非超管只看本租户审计（超管全量）；pageSize 兜 cap 防
        // PageQuery 缺省 MAX_VALUE 全表扫（dsflash P2）
        boolean superAdmin = LoginHelper.isSuperAdmin();
        pageQuery.setPageSize(Math.min(pageQuery.getPageSize() == null || pageQuery.getPageSize() > 200 ? 20 : pageQuery.getPageSize(), 200));
        LambdaQueryWrapper<DcsMcpCallLog> q = new LambdaQueryWrapper<DcsMcpCallLog>()
            .eq(!superAdmin, DcsMcpCallLog::getTenantId, LoginHelper.getTenantId())
            .eq(serverName != null && !serverName.isBlank(), DcsMcpCallLog::getServerName, serverName)
            .orderByDesc(DcsMcpCallLog::getCreateTime);
        var page = callLogMapper.selectPage(pageQuery.build(), q);
        // R.ok 统一包装（同 /list 口径）——TableDataInfo 顶层 rows 结构与前端 request 层解析不符（老坑）
        return R.ok(java.util.Map.of("rows", page.getRecords(), "total", page.getTotal()));
    }
}
