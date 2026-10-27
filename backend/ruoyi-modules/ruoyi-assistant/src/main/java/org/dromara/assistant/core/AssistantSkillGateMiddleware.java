package org.dromara.assistant.core;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.skill.AgentSkill;
import io.agentscope.core.skill.DynamicSkillMiddleware;
import io.agentscope.core.tool.Toolkit;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * B 批翻译层（2026-10-27 拍板 题3=甲·官方考证吻合）：官方技能动态中间件的我们侧子类——
 * 「谁能用哪个技能」的唯一判定层。
 *
 * <p>每次模型调用重组可见技能视图：先按技能 frontmatter 的 minRole（内置门槛），
 * 再按租户工具开关矩阵的 {@code skill:<name>} 行（off=剔除；member/admin=只升不降），
 * 语义与工具行完全同构（{@link ToolRegistry#effectiveMinRoleSingle} 单一真相）。
 * 中间件实例随会话建（构造捕获该用户的 roleLevel 与建会话时刻的矩阵快照），
 * 矩阵改动经装配面指纹失效缓存会话→下一请求重建即吃到新开关（题4 甲口径：
 * 关掉的技能对<b>新对话</b>不可见，进行中对话不踢）。</p>
 *
 * <p>框架钩子即官方预留位：DynamicSkillMiddleware.filterVisible 注释原话
 * 「canary lists, environment gates」——灰度名单/环境门控正是本类做的事；
 * 可见视图内容寻址缓存由官方维护（同视图零重建），我们零缓存代码。</p>
 */
public class AssistantSkillGateMiddleware extends DynamicSkillMiddleware {

    private final int roleLevel;
    private final Map<String, String> toolGate;

    /** 仓分层栈取 {@link AssistantSkillSource}（进程级共享，FileSystem 记忆化才生效） */
    public AssistantSkillGateMiddleware(Toolkit toolkit, int roleLevel,
                                        Map<String, String> toolGate) {
        super(AssistantSkillSource.repositories(), toolkit);
        this.roleLevel = roleLevel;
        this.toolGate = toolGate;
    }

    @Override
    protected List<AgentSkill> filterVisible(List<AgentSkill> raw, RuntimeContext ctx) {
        return filterVisibleImpl(raw, roleLevel, toolGate);
    }

    /**
     * 纯函数判定（@Tag dev 单测锚点）：按 minRole + 矩阵行过滤可见技能。
     * 静态可测——不经框架运行时，正反例直接构造 AgentSkill 断言。
     */
    static List<AgentSkill> filterVisibleImpl(List<AgentSkill> raw, int roleLevel,
                                              Map<String, String> toolGate) {
        List<AgentSkill> out = new ArrayList<>(raw.size());
        for (AgentSkill s : raw) {
            int builtin = AssistantSkillSource.minRoleOf(s);
            int effective = ToolRegistry.effectiveMinRoleSingle(
                gateKey(s.getName()), builtin,
                toolGate == null ? null : toolGate.get(gateKey(s.getName())));
            if (effective >= 0 && roleLevel >= effective) {
                out.add(s);
            }
        }
        return out;
    }

    /** 矩阵行键：技能名加 skill: 前缀，与工具名/mcp: 行互不撞名 */
    static String gateKey(String skillName) {
        return "skill:" + skillName;
    }
}
