package com.smartdock.assistant.infrastructure;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.smartdock.assistant.domain.gateway.ToolCatalogGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.smartdock.assistant.legacy.core.ToolRegistry;
import com.smartdock.assistant.domain.model.DcsMcpCallLog;
import com.smartdock.assistant.domain.model.DcsMcpServer;
import com.smartdock.assistant.mapper.DcsMcpCallLogMapper;
import com.smartdock.assistant.legacy.service.AssistantConfigService;
import com.smartdock.assistant.service.McpServerConfigService;
import com.smartdock.assistant.legacy.tools.WebSearchTools;
import org.dromara.common.core.service.ConfigService;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.tenant.helper.TenantHelper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ToolCatalogGateway} 的唯一实现（2026-11-17 结构批 1 ④）。
 *
 * <p>本类与新根里 {@code RunFeedGatewayImpl} 是仅有的两个允许 import legacy
 * 工具注册表 / MCP 件的地方；工具清单 service 从此不再摸 legacy 类型。
 * 各方法的判定口径与下沉前逐行一致，只是搬了家。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolCatalogGatewayImpl implements ToolCatalogGateway {

    /** 挂载记录动作：每次建会话都会写，不算“调用” */
    private static final List<String> MOUNT_ACTIONS = List.of("MOUNT_OK", "MOUNT_ERROR");

    private final AssistantConfigService assistantConfigService;
    /** 租户系统配置（联网搜索 key 的读取口；与建会话时 WebSearchTools 同一来源） */
    private final ConfigService configService;
    private final McpServerConfigService mcpServerConfigService;
    private final DcsMcpCallLogMapper callLogMapper;

    @Override
    public TenantGate tenantGate(String tenantId) {
        AssistantConfigService.EffectiveConfig cfg = assistantConfigService.effective(tenantId);
        return new TenantGate(cfg.toolGate(),
            "1".equals(cfg.memoryEnabled()),
            cfg.toolGate().containsKey(AssistantConfigService.POISONED_GATE_SENTINEL));
    }

    @Override
    public List<PlatformTool> platformTools(Map<String, String> gate) {
        List<PlatformTool> out = new ArrayList<>();
        for (ToolRegistry.ToolSpec spec : ToolRegistry.SPECS_VIEW()) {
            // 多方法 spec 时 Set 迭代序不稳定，取字典序首个当展示键（当前都是单元素，行为不变）
            String firstName = spec.toolNames().stream().sorted().findFirst().orElse("");
            ToolRegistry.ToolMeta meta = ToolRegistry.TOOL_META.get(firstName);
            int effective = ToolRegistry.effectiveMinRole(spec, gate);
            out.add(new PlatformTool(
                firstName,
                meta != null ? meta.displayName() : firstName,
                meta != null ? meta.userDesc() : "",
                meta != null ? meta.category() : "其他",
                meta != null ? meta.riskLevel() : "mid",
                effective));
        }
        return out;
    }

    @Override
    public boolean webSearchKeyReady(String tenantId) {
        // readTenantKey 内部 TenantHelper.dynamic(...) 切租户，finally 会 clearDynamic()——
        // 登录线程上这会把超管的「切换单位」持久态（Redis+SaStorage）一起删掉，同请求后面的
        // 计数查询就被打回登录租户。先存后还（同 AssistantStatusController.callInTenant）。
        String prevDynamic = TenantHelper.getDynamic();
        try {
            return WebSearchTools.shouldRegisterWebSearch(
                true, tenantId, () -> WebSearchTools.readTenantKey(configService, tenantId));
        } finally {
            if (StringUtils.isNotBlank(prevDynamic)) {
                TenantHelper.setDynamic(prevDynamic, true);
            }
        }
    }

    @Override
    public List<McpServerView> mcpServers() {
        List<McpServerView> out = new ArrayList<>();
        for (DcsMcpServer s : TenantHelper.ignore(mcpServerConfigService::listAll)) {
            out.add(new McpServerView(s.getServerName(), s.getDisplayName(), s.getRemark(),
                s.getEnabled(), s.getReadOnly()));
        }
        return out;
    }

    @Override
    public Map<String, Long> mcpCallCounts(String tenantId) {
        Map<String, Long> counts = new HashMap<>();
        // 没有租户就不查：放开条件会把全租户的数加在一起，和页面「本租户」口径不符
        if (StringUtils.isBlank(tenantId)) {
            log.warn("[工具清单] 租户为空，外部服务调用次数按 0 显示");
            return counts;
        }
        try {
            List<Map<String, Object>> maps = callLogMapper.selectMaps(new QueryWrapper<DcsMcpCallLog>()
                .select("server_name", "count(*) as cnt")
                .eq("tenant_id", tenantId)
                .notIn("action", MOUNT_ACTIONS)
                .groupBy("server_name"));
            for (Map<String, Object> m : maps) {
                Object server = m.get("server_name") != null ? m.get("server_name") : m.get("SERVER_NAME");
                Object cnt = m.get("cnt") != null ? m.get("cnt") : m.get("CNT");
                if (server != null && cnt instanceof Number n) {
                    counts.put(String.valueOf(server), n.longValue());
                }
            }
        } catch (Exception e) {
            log.warn("[工具清单] 外部服务调用次数读取失败，前端显示「—」", e);
            return null;
        }
        return counts;
    }
}
