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
    private final String tenantId;

    /**
     * 仓分层栈取 {@link AssistantSkillSource}（前两层进程级共享，FileSystem 记忆化才生效；
     * 5b 批起第三层=数据库在线层，按本租户绑定）。
     * 三审回修 P1-3：共享稳定 workDir——基类每实例 mkdtemp+挂 shutdown hook，会话级
     * 实例化下 /tmp 目录与 hook 无界增长；传共享目录后两者只发生一次（caller-supplied
     * root 不挂 hook），进程级清理由 AssistantSkillSource 静态块兜底。
     */
    public AssistantSkillGateMiddleware(Toolkit toolkit, int roleLevel,
                                        Map<String, String> toolGate, String tenantId) {
        super(AssistantSkillSource.repositories(tenantId), toolkit, null, false,
            AssistantSkillSource.sharedSkillWorkDir());
        this.roleLevel = roleLevel;
        this.toolGate = toolGate;
        this.tenantId = tenantId == null || tenantId.isBlank()
            ? DatabaseSkillRepository.GLOBAL_TENANT : tenantId;
    }

    @Override
    protected List<AgentSkill> filterVisible(List<AgentSkill> raw, RuntimeContext ctx) {
        return filterVisibleImpl(raw, roleLevel, toolGate);
    }

    /**
     * 纯函数判定（@Tag dev 单测锚点）：按 minRole + 矩阵行过滤可见技能。
     * 静态可测——不经框架运行时，正反例直接构造 AgentSkill 断言。
     *
     * <p><b>护栏约束（三审 glm53 P2-3）</b>：本方法<b>禁抛异常、禁引入可失败 IO/查库</b>——
     * 官方基类对 filterVisible 的异常按 pass-through 全量放行处理，本方法是「谁能用哪个
     * 技能」的唯一判定层，未来扩展只允许纯内存计算；矩阵快照在构造时取得，一切可失败
     * 读取必须发生在构造路径（失败即建会话失败=关闭面）而非判定路径。</p>
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

    /** 矩阵行键：技能名加 skill: 前缀，与工具名/mcp: 行互不撞名（id 型消费方共用） */
    public static String gateKey(String skillName) {
        return "skill:" + skillName;
    }

    /**
     * id 型可见性判定单一真相（三审回修 P1-1，dspro/dsflash 两席共识）：注入装饰器/
     * view_skill 取正文前过同一道闸——矩阵 off/提档后，陈旧 localStorage 胶囊、
     * 模型自取两条旁路一并封死（与模型侧目录段、/skills 端点同口径）。
     */
    public static boolean visibleTo(String skillId, int roleLevel, Map<String, String> toolGate) {
        return visibleTo(skillId, roleLevel, toolGate, SkillAssets.currentTenant());
    }

    /**
     * id 型可见性判定单一真相（三审回修 P1-1，dspro/dsflash 两席共识）：注入装饰器/
     * view_skill 取正文前过同一道闸——矩阵 off/提档后，陈旧 localStorage 胶囊、
     * 模型自取等旁路一并封死（与模型侧目录段、/skills 端点同口径）。
     * 5b 批：按租户视图判定（agent 线程显式传租户——LoginHelper 在那里不可用）。
     */
    public static boolean visibleTo(String skillId, int roleLevel, Map<String, String> toolGate,
                                    String tenantId) {
        if (skillId == null || skillId.isBlank()) {
            return false;
        }
        for (SkillAssets.LoadedSkill s : SkillAssets.skills(tenantId)) {
            if (s.id().equals(skillId.trim())) {
                int effective = ToolRegistry.effectiveMinRoleSingle(
                    gateKey(s.id()), s.minRole(),
                    toolGate == null ? null : toolGate.get(gateKey(s.id())));
                return effective >= 0 && roleLevel >= effective;
            }
        }
        return false;
    }
}
