package com.smartdock.assistant.service;

import com.smartdock.assistant.domain.gateway.ToolCatalogGateway;
import com.smartdock.assistant.domain.gateway.ToolCatalogGateway.McpServerView;
import com.smartdock.assistant.domain.gateway.ToolCatalogGateway.PlatformTool;
import com.smartdock.assistant.domain.gateway.ToolCatalogGateway.TenantGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 工作台设置页·工具清单只读查询（2026-11-17 结构治理批 Q5：从 Controller 下沉；
 * 批 1 ④ 起 legacy 依赖经 {@link ToolCatalogGateway} 收口）。
 *
 * <p>Controller 只留权限、租户解析与 {@code R.ok}；状态推导、租户闸、外部服务名单与
 * 调用次数都归这里。返回的仍是展示所需字段，<b>不回 tool_gate 覆盖键、不回内部枚举/密钥/请求头</b>。</p>
 *
 * <p><b>调用次数</b>：只有外部服务（MCP）有真实账本（{@code dcs_mcp_call_log}）。
 * 计数与「AI 助手管理 → 能力 → 调用日志」的“真实调用”同一口径：排除每次建会话都会
 * 写的 MOUNT_OK/MOUNT_ERROR 挂载记录（2026-11-05 实测：weather_demo 共 223 行里
 * 218 行是挂载记录，真实调用只有 5 次）。平台工具没有账本，一律不显示次数。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolViewerService {

    private final ToolCatalogGateway catalog;

    /**
     * 一行工具：状态用 code+中文双写（前端只负责配色，不自己翻译）；
     * calls 仅外部服务有值（null=没有账本，不显示次数）。
     */
    public record ToolRow(String key, String displayName, String userDesc, String category,
                          String riskLevel, String status, String statusText,
                          String whoCanUse, String kind, Long calls) {
    }

    /** 状态推导结果（纯函数返回，便于单测直调） */
    public record ToolStatus(String code, String text) {
    }

    /**
     * 状态推导（纯函数）：租户关掉 → 已停用；注册闸未开（如租户没开长期记忆/没配联网 key）
     * → 等租户开启；生效档≥管理员 → 管理员限定；其余 → 可用。
     *
     * <p>闸门判断放在角色判断前面：闸没开时这个工具对谁（包括管理员）都不可见，
     * 此时写「等租户开启」比「管理员限定」更贴近事实。</p>
     *
     * @param effective 生效角色门槛（-1=本租户已关闭）
     * @param gateOpen  注册闸是否打开（模型这一轮能不能看见它）
     */
    public static ToolStatus deriveStatus(int effective, boolean gateOpen) {
        if (effective < 0) {
            return new ToolStatus("off", "已停用");
        }
        if (!gateOpen) {
            return new ToolStatus("waiting", "等租户开启");
        }
        if (effective >= ToolCatalogGateway.LEVEL_ADMIN) {
            return new ToolStatus("admin", "管理员限定");
        }
        return new ToolStatus("ready", "可用");
    }

    /** 角色门槛 → 人话（off 时「谁能用」是没有，给灰杠；不假装还有一个档） */
    public static String whoCanUse(int effective) {
        if (effective < 0) {
            return "—";
        }
        if (effective >= ToolCatalogGateway.LEVEL_ADMIN) {
            return "管理员";
        }
        if (effective >= ToolCatalogGateway.LEVEL_PUBLISHER) {
            return "发布人及以上";
        }
        return "成员";
    }

    /**
     * 本租户的工具清单（平台工具 + 外部服务），按「现在真能用」排序。
     *
     * @param tenantId 已经解析好的展示租户（动态租户优先，空则回落登录身份；由 Controller 解析）
     */
    public List<ToolRow> list(String tenantId) {
        TenantGate tg = catalog.tenantGate(tenantId);
        List<ToolRow> rows = platformRows(tg.toolGate(), tg.memoryOn(), catalog.webSearchKeyReady(tenantId));
        rows.addAll(mcpRows(tenantId, tg.toolGate(), tg.gatePoisoned()));
        // 统一排序：能用的在前（等开启/已停用沉底），平台工具在同类里优先，再按分类+名字
        rows.sort(Comparator
            .comparingInt((ToolRow t) -> statusRank(t.status()))
            .thenComparingInt(t -> "platform".equals(t.kind()) ? 0 : 1)
            .thenComparingInt(t -> "查询".equals(t.category()) ? 0 : 1)
            .thenComparing(ToolRow::displayName));
        return rows;
    }

    /** 平台工具行（注册闸：长期记忆 / 联网 key） */
    private List<ToolRow> platformRows(Map<String, String> gate, boolean memoryOn, boolean webSearchKeyReady) {
        List<ToolRow> rows = new ArrayList<>();
        for (PlatformTool t : catalog.platformTools(gate)) {
            boolean gateOpen = switch (t.key()) {
                case "save_memory" -> memoryOn;
                // 清单是租户配置视角：只判租户 key 是否可用，不叠某个会话里手动关搜索的临时开关
                case "web_search" -> webSearchKeyReady;
                default -> true;
            };
            ToolStatus st = deriveStatus(t.effective(), gateOpen);
            rows.add(new ToolRow(
                t.key(),
                t.displayName(),
                t.userDesc(),
                t.category(),
                t.riskLevel(),
                st.code(), st.text(),
                whoCanUse(t.effective()),
                "platform",
                null));
        }
        return rows;
    }

    /** 外部服务行：平台级名单跨租户读，次数只按本租户账本算 */
    private List<ToolRow> mcpRows(String tenantId, Map<String, String> gate, boolean gatePoisoned) {
        // 名单读失败不能拖垮整页：平台工具照常返回，外部服务这一段留空。
        List<McpServerView> servers;
        try {
            servers = catalog.mcpServers();
        } catch (Exception e) {
            log.warn("[工具清单] 外部服务名单读取失败，本次只返回平台工具", e);
            servers = List.of();
        }
        Map<String, Long> callCounts = catalog.mcpCallCounts(tenantId);
        List<ToolRow> rows = new ArrayList<>();
        for (McpServerView s : servers) {
            rows.add(mcpRow(s, gate, gatePoisoned, callCounts));
        }
        return rows;
    }

    /** 单个外部服务行：生效档 与 次数（null=读失败） */
    private ToolRow mcpRow(McpServerView s, Map<String, String> gate,
                           boolean gatePoisoned, Map<String, Long> callCounts) {
        String key = "mcp:" + s.serverName();
        String override = gate.get(key);
        int effective = "off".equals(override) ? -1
            : "admin".equals(override) ? ToolCatalogGateway.LEVEL_ADMIN
            : ToolCatalogGateway.LEVEL_MEMBER;
        if (!"1".equals(s.enabled()) || gatePoisoned) {
            effective = -1;
        }
        ToolStatus st = deriveStatus(effective, true);
        String remark = s.remark() == null ? "" : s.remark().trim();
        if (remark.length() > 60) {
            remark = remark.substring(0, 60) + "…";
        }
        // remark 是管理端「备注」，可能带内部话术：加「外部服务：」前缀，避免被当成对外描述
        String userDesc = (remark.isEmpty() ? "外部服务" : "外部服务：" + remark)
            + " · " + ("0".equals(s.readOnly()) ? "可写" : "只读");
        // -1 = 次数没读出来（DB 抖动/权限异常），前端显示「—」，不要冒充「没调用过」
        Long calls = callCounts == null ? -1L : callCounts.getOrDefault(s.serverName(), 0L);
        return new ToolRow(
            key,
            s.displayName() != null && !s.displayName().isBlank()
                ? s.displayName() : s.serverName(),
            userDesc,
            "外部服务",
            "high",
            st.code(), st.text(),
            whoCanUse(effective),
            "mcp",
            calls);
    }

    /** 可用 → 管理员限定 → 等开启 → 已停用：把「现在真能用」排前面 */
    private static int statusRank(String status) {
        return switch (status) {
            case "ready" -> 0;
            case "admin" -> 1;
            case "waiting" -> 2;
            default -> 3;
        };
    }
}
