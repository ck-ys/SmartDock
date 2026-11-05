package org.dromara.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.core.ToolRegistry;
import org.dromara.assistant.domain.DcsMcpCallLog;
import org.dromara.assistant.domain.DcsMcpServer;
import org.dromara.assistant.mapper.DcsMcpCallLogMapper;
import org.dromara.assistant.service.AssistantConfigService;
import org.dromara.assistant.service.McpPilotService;
import org.dromara.assistant.tools.ToolIdentity;
import org.dromara.assistant.tools.WebSearchTools;
import org.dromara.common.core.domain.R;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.tenant.helper.TenantHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * 工作台设置页·工具清单只读端点（2026-11-05 拍板 题0-4 全甲）。
 *
 * <p>为什么不复用 {@code /dcs/assistant/toolgate}：那是管理端点（perm=config），成员 403；
 * 工作台设置页按题 1 甲让全员看到「助手会做哪些事」，所以这里与技能目录同权限
 * （{@code dcs:assistant:use}）。</p>
 *
 * <p><b>脱敏</b>：不回 tool_gate 覆盖键、不回内部枚举/密钥/请求头；只回展示所需的
 * 中文名、一句人话、分类、风险档、状态、谁能用。状态按「模型实际看不看得见」推导——
 * 租户关了（off）/提到管理员档/租户开关未开（长期记忆、联网 key）都如实写。</p>
 *
 * <p><b>调用次数</b>：只有外部服务（MCP）有真实账本（{@code dcs_mcp_call_log}）。
 * 计数与「AI 助手管理 → 能力 → 调用日志」的“真实调用”同一口径：排除每次建会话都会
 * 写的 MOUNT_OK/MOUNT_ERROR 挂载记录（2026-11-05 实测：weather_demo 共 223 行里
 * 218 行是挂载记录，真实调用只有 5 次）。平台工具没有账本，一律不显示次数。</p>
 */
@Slf4j
@Tag(name = "助手工具清单", description = "工作台设置页工具只读清单（成员可读）")
@RestController
@RequestMapping("/dcs/assistant/tools")
@RequiredArgsConstructor
public class AssistantToolViewerController extends BaseController {

    /** 挂载记录动作：每次建会话都会写，不算“调用” */
    private static final List<String> MOUNT_ACTIONS = List.of("MOUNT_OK", "MOUNT_ERROR");

    private final AssistantConfigService assistantConfigService;
    /** 租户系统配置（联网搜索 key 的读取口；与建会话时 WebSearchTools 同一来源） */
    private final org.dromara.common.core.service.ConfigService configService;
    private final McpPilotService mcpPilotService;
    private final DcsMcpCallLogMapper callLogMapper;

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
        if (effective >= ToolIdentity.LEVEL_ADMIN) {
            return new ToolStatus("admin", "管理员限定");
        }
        return new ToolStatus("ready", "可用");
    }

    /** 角色门槛 → 人话（off 时「谁能用」是没有，给灰杠；不假装还有一个档） */
    public static String whoCanUse(int effective) {
        if (effective < 0) {
            return "—";
        }
        if (effective >= ToolIdentity.LEVEL_ADMIN) {
            return "管理员";
        }
        if (effective >= ToolIdentity.LEVEL_PUBLISHER) {
            return "发布人及以上";
        }
        return "成员";
    }

    @SaCheckPermission("dcs:assistant:use")
    @GetMapping
    public R<List<ToolRow>> list() {
        // 动态租户优先：超管切到某租户时，要显示那个租户的开关与次数，而不是登录时的租户快照
        String dynamicTenantId = TenantHelper.getTenantId();
        final String tenantId = StringUtils.isNotBlank(dynamicTenantId)
            ? dynamicTenantId
            : ToolIdentity.from(LoginHelper.getLoginUser()).getTenantId();
        AssistantConfigService.EffectiveConfig cfg = assistantConfigService.effective(tenantId);
        Map<String, String> gate = cfg.toolGate();
        boolean gatePoisoned = gate.containsKey(AssistantConfigService.POISONED_GATE_SENTINEL);
        // 与建会话时同一个闸：租户没开长期记忆 / 没配可用搜索 key → 模型看不见
        boolean memoryOn = "1".equals(cfg.memoryEnabled());
        // 注意：readTenantKey 内部用 TenantHelper.dynamic(...) 切租户，finally 会 clearDynamic()——
        // 登录线程上这会把超管的「切换单位」持久态（Redis+SaStorage）一起删掉，同请求后面的
        // 计数查询就被打回登录租户。先存后还（同 AssistantStatusController.callInTenant）。
        String prevDynamic = TenantHelper.getDynamic();
        boolean webSearchKeyReady;
        try {
            webSearchKeyReady = WebSearchTools.shouldRegisterWebSearch(
                true, tenantId, () -> WebSearchTools.readTenantKey(configService, tenantId));
        } finally {
            if (StringUtils.isNotBlank(prevDynamic)) {
                TenantHelper.setDynamic(prevDynamic, true);
            }
        }

        List<ToolRow> rows = new ArrayList<>();
        for (ToolRegistry.ToolSpec spec : ToolRegistry.SPECS_VIEW()) {
            // 多方法 spec 时 Set 迭代序不稳定，取字典序首个当展示键（当前都是单元素，行为不变）
            String firstName = spec.toolNames().stream().sorted().findFirst().orElse("");
            ToolRegistry.ToolMeta meta = ToolRegistry.TOOL_META.get(firstName);
            int effective = ToolRegistry.effectiveMinRole(spec, gate);
            boolean gateOpen = switch (firstName) {
                case "save_memory" -> memoryOn;
                // 清单是租户配置视角：只判租户 key 是否可用，不叠某个会话里手动关搜索的临时开关
                case "web_search" -> webSearchKeyReady;
                default -> true;
            };
            ToolStatus st = deriveStatus(effective, gateOpen);
            rows.add(new ToolRow(
                firstName,
                meta != null ? meta.displayName() : firstName,
                meta != null ? meta.userDesc() : "",
                meta != null ? meta.category() : "其他",
                meta != null ? meta.riskLevel() : "mid",
                st.code(), st.text(),
                whoCanUse(effective),
                "platform",
                null));
        }

        // 外部服务：平台级名单（tenant 恒 000000）——跨租户读名单，但次数只按本租户账本算。
        // 名单读失败不能拖垮整页：平台工具照常返回，外部服务这一段留空。
        List<DcsMcpServer> servers;
        try {
            servers = TenantHelper.ignore(mcpPilotService::listAll);
        } catch (Exception e) {
            log.warn("[工具清单] 外部服务名单读取失败，本次只返回平台工具", e);
            servers = List.of();
        }
        Map<String, Long> callCounts = loadCallCounts(tenantId);
        for (DcsMcpServer s : servers) {
            String key = "mcp:" + s.getServerName();
            String override = gate.get(key);
            int effective = "off".equals(override) ? -1
                : "admin".equals(override) ? ToolIdentity.LEVEL_ADMIN
                : ToolIdentity.LEVEL_MEMBER;
            if (!"1".equals(s.getEnabled()) || gatePoisoned) {
                effective = -1;
            }
            ToolStatus st = deriveStatus(effective, true);
            String remark = s.getRemark() == null ? "" : s.getRemark().trim();
            if (remark.length() > 60) {
                remark = remark.substring(0, 60) + "…";
            }
            // remark 是管理端「备注」，可能带内部话术：加「外部服务：」前缀，避免被当成对外描述
            String userDesc = (remark.isEmpty() ? "外部服务" : "外部服务：" + remark)
                + " · " + ("0".equals(s.getReadOnly()) ? "可写" : "只读");
            // -1 = 次数没读出来（DB 抖动/权限异常），前端显示「—」，不要冒充「没调用过」
            Long calls = callCounts == null ? -1L : callCounts.getOrDefault(s.getServerName(), 0L);
            rows.add(new ToolRow(
                key,
                s.getDisplayName() != null && !s.getDisplayName().isBlank()
                    ? s.getDisplayName() : s.getServerName(),
                userDesc,
                "外部服务",
                "high",
                st.code(), st.text(),
                whoCanUse(effective),
                "mcp",
                calls));
        }

        // 统一排序：能用的在前（等开启/已停用沉底），平台工具在同类里优先，再按分类+名字
        rows.sort(Comparator
            .comparingInt((ToolRow t) -> statusRank(t.status()))
            .thenComparingInt(t -> "platform".equals(t.kind()) ? 0 : 1)
            .thenComparingInt(t -> "查询".equals(t.category()) ? 0 : 1)
            .thenComparing(ToolRow::displayName));
        return R.ok(rows);
    }

    /**
     * 本租户各外部服务的真实调用次数（一次 group by 取完，避免按服务逐个 count）。
     * 口径与「AI 助手管理 → 能力 → 调用日志」的 realOnly 一致：排除 MOUNT_* 挂载行；
     * 账本本身只保留 90 天（McpPilotService 每天清扫），所以页面写「近 90 天」。
     *
     * @return 服务名→次数；读取失败返回 null（页面显示「—」，不能冒充「没调用过」）
     */
    private Map<String, Long> loadCallCounts(String tenantId) {
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
