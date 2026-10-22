package org.dromara.assistant.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.dromara.assistant.core.AssistantAgentFactory;
import org.dromara.assistant.core.PromptAssets;
import org.dromara.assistant.core.ToolRegistry;
import org.dromara.assistant.service.AssistantConfigService;
import org.dromara.common.core.domain.R;
import org.dromara.common.satoken.utils.LoginHelper;
import org.dromara.common.web.core.BaseController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 助手能力全景端点（ZCode 对齐 Q4，批A 2026-10-10）：管理页重构的数据源——
 * 工具清单（ToolRegistry 单点注册+中文元数据）、系统提示词只读展示、租户级开关。
 * 只读不写；工具真源在 {@link ToolRegistry}（SPECS+TOOL_META 同文件单点）。
 */
@Tag(name = "助手能力全景", description = "工具清单/系统提示词/租户开关只读展示（管理页数据源）")
@RestController
@RequestMapping("/dcs/assistant/capability")
@RequiredArgsConstructor
public class AssistantCapabilityPanoramaController extends BaseController {

    private final AssistantConfigService assistantConfigService;
    /** 批A 回修：租户默认模型读取（压缩派生值与 /config 同源） */
    private final org.dromara.assistant.factory.AssistantModelFactory modelFactory;

    /** 一条工具的全景行（SPECS 注册信息 + TOOL_META 中文元数据合成）；
     *  批1 Q1=B：+toolGate 覆盖档位与 effective 生效档（-1=本租户已关闭） */
    public record PanoramaTool(String name, String displayName, String category, int station,
                               String behavior, int minRoleLevel, String gateDesc,
                               String toolGate, int effective) {
    }

    /** 业务批7 Q9：技能库全景行（正文折叠展示；注入方式口径与 SkillAssets 三级兜底一致） */
    public record PanoramaSkill(String id, String name, String description, int minRoleLevel,
                                String injectNote, java.util.List<String> triggers, String body) {
    }

    /** 全景聚合：工具清单 + 系统提示词（memoryOn 拼接口径与 agent 一致）+ 租户开关；
     *  批2 Q8 增加分段视图 promptSegments（管理页总纲按 md 文件分段展示，只读）；
     *  业务批7 Q9 增加 skills（➕ 面板可选的技能目录——管理员第一次能在管理端看到技能本体） */
    public record PanoramaVo(List<PanoramaTool> tools, String sysPrompt,
                             List<PromptAssets.PromptSegment> promptSegments,
                             List<PanoramaSkill> skills,
                             Map<String, Object> tenantConfig) {
    }

    /**
     * 能力全景（管理页；perm=dcs:assistant:config 与 /admin/stats 同源）。
     * 四席审查回修（dspro P1-1/glm53 P2-2，2026-10-11）：原 OR(dcs:ai:query, dcs:assistant:use)
     * 把系统提示词全文+租户日预算开放给了全体助手用户（含成员）——唯一消费方是管理页
     * （路由本身要求 config），收紧到 config；工具行按分类+名称排序（SPECS 组内 Set.of
     * 迭代序随 JVM 重启漂移，dspro P2-4）。
     */
    @SaCheckPermission("dcs:assistant:config")
    @GetMapping("/panorama")
    public R<PanoramaVo> panorama() {
        String tenantId = LoginHelper.getTenantId();
        // 批1 Q1=B：先取生效配置——工具行带出 tool_gate 覆盖与 effective 生效档；
        // 三审回修（dspro P2-6）：与下方 memoryOn 同源复用，一次请求只查一次
        AssistantConfigService.EffectiveConfig cfg = assistantConfigService.effective(tenantId);
        java.util.Map<String, String> gate = cfg.toolGate();
        List<PanoramaTool> tools = new ArrayList<>();
        for (ToolRegistry.ToolSpec spec : ToolRegistry.SPECS) {
            int effective = ToolRegistry.effectiveMinRole(spec, gate);
            for (String name : spec.toolNames()) {
                ToolRegistry.ToolMeta meta = ToolRegistry.TOOL_META.get(name);
                tools.add(new PanoramaTool(
                    name,
                    meta == null ? name : meta.displayName(),
                    meta == null ? "未分类" : meta.category(),
                    meta == null ? 1 : meta.station(),
                    spec.behavior() == io.agentscope.core.permission.PermissionBehavior.ASK ? "需确认" : "自动执行",
                    spec.minRoleLevel(),
                    meta == null ? "" : meta.gateDesc(),
                    gate.get(name),
                    effective));
            }
        }
        tools.sort(java.util.Comparator.comparing(PanoramaTool::category).thenComparing(PanoramaTool::displayName));
        // 业务批7 Q9：技能目录（SkillAssets 进程内缓存；注入方式说明与 R1 设计口径一致）
        List<PanoramaSkill> skills = new ArrayList<>();
        for (org.dromara.assistant.core.SkillAssets.LoadedSkill sk : org.dromara.assistant.core.SkillAssets.skills()) {
            skills.add(new PanoramaSkill(sk.id(), sk.name(), sk.description(), sk.minRole(),
                "会话中 ➕ 面板选中才注入：仅选中会话按轮生效，未选中不产生额外消耗", sk.triggers(), sk.body()));
        }
        boolean memoryOn = "1".equals(cfg.memoryEnabled());
        // 批2 Q8：snapshot 单次读盘，拼装串与分段视图同源同刻（审查 P2-3）
        PromptAssets.Snapshot snap = PromptAssets.snapshot(memoryOn);
        // 批A 回修（三审 P2）：租户旧固定阈值已废弃——同 /config 口径按默认模型窗口派生，
        // 防管理端两处展示互相矛盾（旧值 60000 vs 派生值 9 万+）
        Integer window = null;
        Integer maxOutput = null;
        try {
            com.smartdock.domain.DcsAiConfig def = modelFactory.resolveConfig(tenantId);
            window = def.getContextWindow();
            maxOutput = def.getMaxTokens();
        } catch (Exception e) {
            // 未配置模型：按兜底窗口派生（与 /config 同口径）
        }
        return R.ok(new PanoramaVo(
            tools,
            snap.sysPrompt(),
            snap.segments(),
            skills,
            Map.of(
                "enabled", cfg.enabled(),
                "memoryEnabled", memoryOn,
                "dailyRoundLimitPerUser", cfg.dailyRoundLimitPerUser(),
                "dailyBudgetAmount", cfg.dailyBudgetAmount(),
                "compactionTriggerTokens", org.dromara.assistant.core.AssistantCompactionPolicy
                    .deriveTriggerTokens(window, maxOutput))));
    }
}
