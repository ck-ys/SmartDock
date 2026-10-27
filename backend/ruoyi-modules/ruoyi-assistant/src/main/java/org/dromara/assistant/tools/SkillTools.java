package org.dromara.assistant.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能自取工具（五需求批 R1 / Q1-A 双入口之一，2026-10-13）。
 *
 * <p>模型侧入口：系统提示词目录只给 id+描述，模型判断需要某技能完整指引时调本工具按
 * skill_id 取正文（progressive disclosure 的 model-invocable 路）。纯只读 ALLOW 全角色
 * 注册（目录已按角色裁剪，成员目录里根本没有高权技能 id；此处再按 minRole 兜底拦）。
 * 返回的技能正文与用户手选注入的正文同源同口径（{@link org.dromara.assistant.core.SkillAssets}）。</p>
 */
public class SkillTools {

    private final ToolIdentity identity;
    /** 三审回修 P1-1：租户矩阵快照——模型自取前过闸（与目录段/注入同口径） */
    private final java.util.Map<String, String> toolGate;

    public SkillTools(ToolIdentity identity, java.util.Map<String, String> toolGate) {
        this.identity = identity;
        this.toolGate = toolGate;
    }

    @Tool(name = "view_skill",
        description = "查看某个技能的完整指引（系统提示词技能目录里只有一句话描述）。当你正在执行的任务与某个技能"
            + "对口而用户没选它时，调本工具取全文按其执行；与本轮任务无关的技能不要取。",
        readOnly = true)
    public String viewSkill(
        @ToolParam(name = "skill_id", required = true, description = "技能标识（系统提示词技能目录里 <skill-id> 标签内的英文串）")
        String skillId) {

        Map<String, Object> out = ToolSupport.baseShape(identity);
        if (skillId == null || skillId.isBlank()) {
            out.put(ToolContracts.KEY_ERROR, "ERROR");
            out.put("message", "skill_id 不能为空（参数绑定失败保护）");
            return ToolSupport.secureJson(out, identity);
        }
        // 三审回修 P1-1：矩阵 off/提档的技能自取一并拦（与 load_skill_through_path 可见集一致）
        if (!org.dromara.assistant.core.AssistantSkillGateMiddleware.visibleTo(
            skillId.trim(), identity.getRoleLevel(), toolGate)) {
            out.put(ToolContracts.KEY_ERROR, "ERROR");
            out.put("message", "没有这个技能，或当前身份无权使用（技能目录里有的 id 才有效）");
            return ToolSupport.secureJson(out, identity);
        }
        String body = org.dromara.assistant.core.SkillAssets.body(skillId.trim(), identity.getRoleLevel());
        if (body == null) {
            out.put(ToolContracts.KEY_ERROR, "ERROR");
            out.put("message", "没有这个技能，或当前身份无权使用（技能目录里有的 id 才有效）");
            return ToolSupport.secureJson(out, identity);
        }
        out.put("skillId", skillId.trim());
        out.put("body", body);
        out.put(ToolContracts.KEY_ROWS, List.of());
        return ToolSupport.secureJson(out, identity);
    }
}
