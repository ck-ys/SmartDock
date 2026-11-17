package com.smartdock.assistant.domain.gateway;

import java.util.List;
import java.util.Map;

/**
 * 工具清单只读查询的接口菜单（2026-11-17 结构批 1 ④）：service 只看本接口，
 * 工具注册表 / MCP 名单 / 调用账本 / 联网 key 这些 legacy 依赖收口在 infrastructure 实现。
 *
 * <p>为什么建 gateway：工具注册表与 MCP 账本都是 legacy 引擎内核的内部件（批 9 才拍搬不搬），
 * 属「跨模块 / 会换实现」边界；自有单表 CRUD 不在此列。</p>
 */
public interface ToolCatalogGateway {

    /** 角色档位：与 {@code org.dromara.assistant.tools.ToolIdentity} 同值；AssistantToolViewerUnitTest 钉死同步 */
    int LEVEL_MEMBER = 0;
    int LEVEL_PUBLISHER = 1;
    int LEVEL_ADMIN = 2;

    /** 租户工具闸快照（来自助手生效配置） */
    record TenantGate(Map<String, String> toolGate, boolean memoryOn, boolean gatePoisoned) {
    }

    /** 平台工具目录条目（注册表视角 + 该租户生效档；多方法 spec 取字典序首个当展示键） */
    record PlatformTool(String key, String displayName, String userDesc,
                        String category, String riskLevel, int effective) {
    }

    /** 平台级 MCP 名单视图（只带清单页要展示的字段，不带密钥/请求头） */
    record McpServerView(String serverName, String displayName, String remark, String enabled, String readOnly) {
    }

    /** 读租户生效配置里的工具闸、长期记忆开关、闸污染标记 */
    TenantGate tenantGate(String tenantId);

    /** 平台工具目录（按注册序） */
    List<PlatformTool> platformTools(Map<String, String> gate);

    /**
     * 联网搜索 key 是否可用（与建会话时的注册闸同源）。
     * 实现内部必须做动态租户「先存后还」（readTenantKey 会清掉超管切换单位态）。
     */
    boolean webSearchKeyReady(String tenantId);

    /** 平台级 MCP 名单（跨租户读）；读失败抛出，由调用方决定降级 */
    List<McpServerView> mcpServers();

    /**
     * 本租户各 MCP 服务的真实调用次数（排除挂载行，近 90 天口径）。
     *
     * @return 服务名→次数；<b>读失败返回 null</b>（页面显示「—」，不能冒充「没调用过」）
     */
    Map<String, Long> mcpCallCounts(String tenantId);
}
