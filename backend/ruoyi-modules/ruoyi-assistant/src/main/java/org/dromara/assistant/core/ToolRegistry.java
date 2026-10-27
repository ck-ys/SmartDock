package org.dromara.assistant.core;

import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import org.dromara.assistant.tools.ToolIdentity;
import org.dromara.common.core.service.ConfigService;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

/**
 * 平台工具注册表（批3 微核心化，2026-10-18）：助手「平台自留」工具的
 * 「构造器 + 工具名清单 + 权限行为 + 角色门槛」唯一登记处。
 *
 * <p><b>批3 口径</b>：业务域工具（查任务/催办/审核/建任务等 18 个）随旧 dcs
 * 一起退役，业务能力一律走 {@link ProviderToolRegistry} 的 BizToolProvider
 * SPI（微核心提供 DcsToolProvider）。本表只剩 6 个平台工具：联网搜索、
 * 读附件、计划卡、TODO 清单、长期记忆、技能自取——全部与具体业务域无关，
 * 任何业务模块缺席都不影响其注册。</p>
 *
 * <p><b>怎么加新平台工具</b>：在 {@link #SPECS} 加一条 ToolSpec——工具类带
 * @Tool 注解的方法名全列进 toolNames，权限行为按 query=ALLOW / act=ASK
 * 分（坑71：规则键=工具方法名精确匹配，禁通配）。业务工具别来这里，
 * 去 BizToolProvider。</p>
 */
public final class ToolRegistry {

    private ToolRegistry() {
    }

    /**
     * 工具构造依赖包：工厂持有的全部 service 依赖显式集中，
     * 测试可自建假实现注入（禁 Spring 上下文也能构造注册表）。
     */
    public record ToolDeps(
        ConfigService configService,
        org.dromara.assistant.security.AssistantPiiFilter piiFilter,
        org.dromara.assistant.service.AssistantAttachmentService attachmentService,
        org.dromara.assistant.core.AssistantPlanSignal planSignal,
        org.dromara.assistant.service.AssistantMemoryService memoryService,
        Boolean memoryEnabled,
        String aguiThreadId,
        /** 建 agent 的生效租户（登录快照）；异步 supplier 线程无 Sa-Token，租户必须显式带进来 */
        String tenantId,
        org.dromara.assistant.core.AssistantTodoSignal todoSignal,
        org.dromara.assistant.service.AssistantConversationService conversationService,
        org.dromara.assistant.limiter.AssistantWebSearchQuota webSearchQuota,
        org.dromara.assistant.service.AssistantUserQuotaService quotaService,
        /** 三审回修 P1-1：view_skill 自取过闸用的租户矩阵快照 */
        java.util.Map<String, String> toolGate
    ) {
    }

    /**
     * 一条注册项：一个工具类（可含多个 @Tool 方法）。
     *
     * @param toolNames   该类全部 @Tool 方法名（权限规则逐名生成）
     * @param behavior    权限行为：查询类 ALLOW 直过；动作类 ASK 进 HITL 确认卡
     * @param minRoleLevel 角色门槛（低于此等级不注册 schema——模型不可见）
     * @param factory     构造器引用（每会话 new，身份随构造器进，禁 static holder）
     * @param gate        配置门控（null=恒注册；二站④：save_memory 仅租户开记忆时注册）
     */
    public record ToolSpec(
        Set<String> toolNames,
        PermissionBehavior behavior,
        int minRoleLevel,
        BiFunction<ToolIdentity, ToolDeps, Object> factory,
        java.util.function.Predicate<ToolDeps> gate
    ) {
    }

    /** 全部平台工具的唯一清单（顺序即注册顺序，新增工具只改这里；守卫测试直读）。 */
    public static final List<ToolSpec> SPECS = List.of(
        // 联网搜索（业务批3-④ #17 + N3 2026-10-23）：用户会话开关开着、且本租户已配置
        // 可用搜索 key 才注册 schema（任一不满足=模型看不见）；工具内仍保留 DISABLED 兜底
        // 处理会话期间 key 被清空。执行链再过日配额闸（单人覆盖>租户默认 20 次/天，上游失败补偿）
        new ToolSpec(Set.of("web_search"),
            PermissionBehavior.ALLOW, ToolIdentity.LEVEL_MEMBER,
            (id, d) -> new org.dromara.assistant.tools.WebSearchTools(id, d.configService(), d.piiFilter(),
                d.webSearchQuota(), d.quotaService()),
            d -> org.dromara.assistant.tools.WebSearchTools.shouldRegisterWebSearch(
                AssistantWebSearchHolder.isOn(d.aguiThreadId()), d.tenantId(),
                () -> org.dromara.assistant.tools.WebSearchTools.readTenantKey(d.configService(), d.tenantId()))),
        // 会话附件：全角色可用（只读自己会话的文件；threadId 随 agent 会话绑定；
        // G5 批G：出口统一 secureJson（PII+注入打标），piiFilter 不再单独注入）
        new ToolSpec(Set.of("read_attachment"),
            PermissionBehavior.ALLOW, ToolIdentity.LEVEL_MEMBER,
            (id, d) -> new org.dromara.assistant.tools.AttachmentTools(id, d.aguiThreadId(),
                d.attachmentService()), null),
        // 计划卡（二站③ AU-51）：全角色可用——纯信号展示无副作用，模型按提示词
        // 触发规则调用，enricher 转 plan CUSTOM 帧，前端 PlanCard 三按钮
        new ToolSpec(Set.of("submit_plan"),
            PermissionBehavior.ALLOW, ToolIdentity.LEVEL_MEMBER,
            (id, d) -> new org.dromara.assistant.tools.PlanTools(id, d.planSignal(), d.aguiThreadId()), null),
        // TODO 清单（ZCode 对齐 Q2，批A）：全角色可用——整表替换式工作计划，
        // 落会话行 todo_items 列 + enricher 转 todo CUSTOM 帧，前端 StatPanel 分区
        new ToolSpec(Set.of("todo_write"),
            PermissionBehavior.ALLOW, ToolIdentity.LEVEL_MEMBER,
            (id, d) -> new org.dromara.assistant.tools.TodoTools(id, d.todoSignal(),
                d.conversationService(), d.aguiThreadId()), null),
        // 长期记忆（二站④ AU-53）：仅租户 memoryEnabled='1' 时注册——
        // 未开启租户模型不可见 save_memory，也拿不到记忆注入（工厂侧同开关）
        new ToolSpec(Set.of("save_memory"),
            PermissionBehavior.ALLOW, ToolIdentity.LEVEL_MEMBER,
            (id, d) -> new org.dromara.assistant.tools.MemoryTools(id, d.memoryService()),
            d -> Boolean.TRUE.equals(d.memoryEnabled())),
        // 技能自取（五需求批 R1，2026-10-13）：全角色注册；目录按角色裁剪+体内 minRole 兜底
        new ToolSpec(Set.of("view_skill"),
            PermissionBehavior.ALLOW, ToolIdentity.LEVEL_MEMBER,
            (id, d) -> new org.dromara.assistant.tools.SkillTools(id, d.toolGate()), null)
    );

    /** 权限通配三态口径单点(批2 glm53 P2-8 收敛): null 拒; 裸 * / *:*:* / 精确串 放行。
     *  ProviderToolRegistry 的 provider 权限门引用本方法, 口径演进只改这一处。 */
    public static boolean wildcardOrContains(java.util.Set<String> menuPermissions, String requiredPermission) {
        return menuPermissions != null
            && (menuPermissions.contains("*:*:*")
                || menuPermissions.contains("*")
                || menuPermissions.contains(requiredPermission));
    }

    /**
     * 按角色注册全部达标工具并同步生成权限规则（注册与规则同循环=零第二真相）。
     *
     * @param toolGate 批1 Q1=B：租户级工具档位覆盖（工具名→off/member/admin；null/空=内置门槛）
     * @return 实际注册成功的工具名集合（守卫断言用）
     */
    public static Set<String> registerAll(Toolkit toolkit, ToolIdentity identity, ToolDeps deps,
                                          PermissionContextState.Builder pb,
                                          java.util.Map<String, String> toolGate) {
        Set<String> registered = new LinkedHashSet<>();
        for (ToolSpec spec : SPECS) {
            int minRole = effectiveMinRole(spec, toolGate);
            if (minRole < 0 || identity.getRoleLevel() < minRole) {
                continue;
            }
            if (spec.gate() != null && !spec.gate().test(deps)) {
                continue;
            }
            Object tool = spec.factory().apply(identity, deps);
            toolkit.registerTool(tool);
            for (String name : spec.toolNames()) {
                PermissionRule rule = new PermissionRule(name, "", spec.behavior(), "dcs-assistant");
                if (spec.behavior() == PermissionBehavior.ASK) {
                    pb.addAskRule(name, rule);
                } else {
                    pb.addAllowRule(name, rule);
                }
                registered.add(name);
            }
        }
        return registered;
    }

    /**
     * 批1 Q1=B（2026-10-21）：租户级工具档位单点判定——注册面/目录/全景三面共用。
     * 覆盖值 off=-1（彻底关闭，模型不可见）；member/admin=门槛只升不降
     * （内置门槛高的工具如管理员专用项，租户配 member 也不能降到全员）；
     * 未配置/非法值/空表=内置门槛不变。多工具 spec 全量聚合取最严（G1 遗留#1）：
     * 任一 off→整组关；否则取命中档位最高者——不再依赖 Set 迭代序的"先命中"。
     */
    public static int effectiveMinRole(ToolSpec spec, java.util.Map<String, String> toolGate) {
        if (toolGate != null) {
            boolean anyOff = false;
            boolean anyHit = false;
            int strictest = spec.minRoleLevel();
            for (String name : spec.toolNames()) {
                String v = toolGate.get(name);
                if (v == null) {
                    continue;
                }
                int single = effectiveMinRoleSingle(name, spec.minRoleLevel(), v);
                if (single < 0) {
                    anyOff = true;
                } else {
                    anyHit = true;
                    strictest = Math.max(strictest, single);
                }
            }
            if (anyOff) {
                return -1;
            }
            if (anyHit) {
                return strictest;
            }
        }
        return spec.minRoleLevel();
    }

    /**
     * 单键档位核心（B 批 2026-10-27 抽出）：off=-1；member/admin 与内置门槛取 max（只升不降）；
     * 未配置/非法值=内置门槛。工具（矩阵行）与技能（skill:&lt;name&gt; 行）共用此函数=
     * 两类对象的开关语义单一真相，演进只改这一处。
     */
    public static int effectiveMinRoleSingle(String key, int builtinLevel, String override) {
        if (override == null) {
            return builtinLevel;
        }
        return switch (override) {
            case "off" -> -1;
            case "member" -> Math.max(builtinLevel, ToolIdentity.LEVEL_MEMBER);
            case "admin" -> Math.max(builtinLevel, ToolIdentity.LEVEL_ADMIN);
            // 非法值忽略——配置面已限下拉，此处兜底不炸链
            default -> builtinLevel;
        };
    }

    /** 全量工具名（不分角色，守卫测试比对用）。 */
    public static Set<String> allToolNames() {
        Set<String> names = new LinkedHashSet<>();
        for (ToolSpec spec : SPECS) {
            names.addAll(spec.toolNames());
        }
        return names;
    }

    /** 批1 Q1=B：spec 清单只读视图（toolgate 管理端合并视图用——不暴露可变 SPECS）。 */
    public static List<ToolSpec> SPECS_VIEW() {
        return List.copyOf(SPECS);
    }

    /** 工具展示元数据（能力全景/管理页，批A Q4）：与 SPECS 同文件=单点注册不破。 */
    public record ToolMeta(String displayName, String category, int station, String gateDesc) {
    }

    /**
     * 工具名 → 中文展示元数据。station：1=一期基础 / 2=二站（管理页暂缓展示） / 3=本期。
     * gateDesc 空=恒可用；save_memory 随租户记忆开关注册；web_search 随会话联网开关注册（业务批3-④）。
     */
    public static final java.util.Map<String, ToolMeta> TOOL_META = java.util.Map.ofEntries(
        java.util.Map.entry("web_search", new ToolMeta("联网搜索", "查询", 1, "会话开启联网后")),
        java.util.Map.entry("read_attachment", new ToolMeta("读会话附件", "查询", 1, "")),
        java.util.Map.entry("submit_plan", new ToolMeta("计划卡", "交互", 2, "")),
        java.util.Map.entry("save_memory", new ToolMeta("保存长期记忆", "交互", 2, "租户开启长期记忆后")),
        java.util.Map.entry("todo_write", new ToolMeta("任务清单", "交互", 3, "")),
        java.util.Map.entry("view_skill", new ToolMeta("查看技能指引", "交互", 3, ""))
    );
}
