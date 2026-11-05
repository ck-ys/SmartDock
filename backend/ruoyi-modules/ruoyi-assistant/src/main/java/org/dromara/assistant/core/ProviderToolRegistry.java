package org.dromara.assistant.core;

import com.smartdock.assistant.spi.BizToolProvider;
import com.smartdock.assistant.spi.BizToolSpec;
import io.agentscope.core.permission.PermissionBehavior;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionRule;
import io.agentscope.core.tool.Toolkit;
import lombok.extern.slf4j.Slf4j;
import org.dromara.assistant.tools.ToolIdentity;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * BizToolProvider 注册器(批2 SPI 化): 收集全部 provider Bean, 按会话身份注册工具。
 *
 * <p>注册与权限规则同循环生成(沿袭 ToolRegistry S-3 单点化纪律——规则键=工具名
 * 精确匹配, 禁通配)。门控三道: 角色档 / 权限串 / 撞名让位(内置优先, provider
 * 名字撞车只 warn 不炸)。</p>
 *
 * <p>无任何 provider Bean 时构造成立(空列表)——助手无业务模块也能起。</p>
 *
 * @author smartdock
 */
@Slf4j
@Component
public class ProviderToolRegistry {

    private final List<BizToolProvider> providers;

    public ProviderToolRegistry(ObjectProvider<BizToolProvider> providerProvider) {
        // ObjectProvider.stream(): 零 provider Bean 时得空流——"无业务模块也能起"的启动语义
        this.providers = List.copyOf(providerProvider.stream().toList());
    }

    public List<BizToolProvider> providers() {
        return providers;
    }

    /**
     * 三批③（2026-11-01）：写操作审计口径快照（建 agent 时算一次，hook 据此判 release_mode）。
     *
     * <ul>
     *   <li>writeTools：本会话按过滤链真实可注册的<b>写类</b>业务工具名全集（与
     *       {@link #registerProviders} 同一条过滤链共用，见 {@link #eligibleSpecs}）；</li>
     *   <li>autonomyAllowed：writeTools 中建时按档位直接放行（autoAllow.test==true）的子集
     *       ——autoAllow 由 AgentFactory 传与 registerProviders 完全同款的谓词实例，
     *       快照口径=与实际装进引擎的权限规则一致。</li>
     * </ul>
     */
    public record AuditScope(Set<String> writeTools, Set<String> autonomyAllowed) {
    }

    /**
     * 审计口径快照计算：只读工具不进集合（它们无放行语义）；autoAllow=null=全集空
     * （等价 always 档——写类全走确认卡，release_mode 恒 confirm）。
     */
    public AuditScope auditScope(ToolIdentity identity, String threadId, Predicate<String> autoAllow) {
        Set<String> write = new LinkedHashSet<>();
        for (Eligible e : eligibleSpecs(identity)) {
            if (!e.spec().isReadOnly()) {
                write.add(e.spec().getName());
            }
        }
        Set<String> auto = new LinkedHashSet<>();
        if (autoAllow != null) {
            for (String name : write) {
                if (autoAllow.test(name)) {
                    auto.add(name);
                }
            }
        }
        return new AuditScope(Set.copyOf(write), Set.copyOf(auto));
    }

    /**
     * 只读业务工具名集合（2026-11-05 自动化批·题2 甲）：无人值守运行只允许只读面，
     * 工厂建完 agent 后按此白名单裁掉写类/交互类工具。与注册链共用同一 eligibleSpecs。
     */
    public Set<String> readOnlyToolNames(ToolIdentity identity) {
        Set<String> out = new LinkedHashSet<>();
        for (Eligible e : eligibleSpecs(identity)) {
            if (e.spec().isReadOnly()) {
                out.add(e.spec().getName());
            }
        }
        return out;
    }

    /**
     * 按会话身份注册全部达标工具(在 ToolRegistry.registerAll 之后调用, 内置优先)。
     *
     * @return 实际注册的工具名集合(守卫断言用)
     */
    public Set<String> registerProviders(Toolkit toolkit, ToolIdentity identity, String threadId,
                                         PermissionContextState.Builder pb) {
        return registerProviders(toolkit, identity, threadId, pb, null);
    }

    /**
     * 题9 案1（2026-10-31）：带自主权判定的注册入口。
     *
     * @param autoAllow 写类工具是否可按用户档位（全自动 / 同类已确认）直接放行；
     *                  null=老口径（写类一律弹确认卡）
     */
    public Set<String> registerProviders(Toolkit toolkit, ToolIdentity identity, String threadId,
                                         PermissionContextState.Builder pb,
                                         Predicate<String> autoAllow) {
        return registerProviders(toolkit, identity, threadId, pb, autoAllow, null);
    }

    /**
     * 三批③：带自主权判定+写操作审计挂钩的注册入口（AgentFactory 主链用）。
     *
     * @param actionLedger 写操作账本挂钩（null=不挂账本——测试/旧路径，行为与题9 完全一致）
     */
    public Set<String> registerProviders(Toolkit toolkit, ToolIdentity identity, String threadId,
                                         PermissionContextState.Builder pb,
                                         Predicate<String> autoAllow,
                                         AssistantActionLedgerHook actionLedger) {
        Set<String> registered = new LinkedHashSet<>();
        for (Eligible e : eligibleSpecs(identity)) {
            BizToolProvider provider = e.provider();
            BizToolSpec spec = e.spec();
            String name = spec.getName();
            try {
                toolkit.registerAgentTool(new BizToolAgentAdapter(provider, spec, identity, threadId, actionLedger));
                // 题9 案1：只读恒放行；写类按自主权档位决定放行/弹卡（审计在 run 记录与日志留痕）
                boolean auto = !spec.isReadOnly() && autoAllow != null && autoAllow.test(name);
                PermissionBehavior behavior = spec.isReadOnly() || auto
                    ? PermissionBehavior.ALLOW : PermissionBehavior.ASK;
                PermissionRule rule = new PermissionRule(name, "", behavior,
                    "biz-provider:" + provider.namespace());
                if (behavior == PermissionBehavior.ALLOW) {
                    pb.addAllowRule(name, rule);
                } else {
                    pb.addAskRule(name, rule);
                }
                if (auto) {
                    log.info("[Autonomy] 写类工具按用户档位自动放行 tenant={} user={} thread={} tool={}",
                        identity.getTenantId(), identity.getUserId(), threadId, name);
                }
                registered.add(name);
            } catch (Exception ex) {
                log.warn("[Assistant] provider tool {} register failed: {}", name, ex.getMessage());
            }
        }
        return registered;
    }

    /** provider 与其 spec 的配对（eligibleSpecs 产出——注册与审计快照共用） */
    private record Eligible(BizToolProvider provider, BizToolSpec spec) {
    }

    /**
     * 达标工具全集（注册与 auditScope 共用的同一条过滤链）：
     * 非 blank / 不撞内置名 / provider 间撞名先到先得 / minRole / 权限串。
     */
    private List<Eligible> eligibleSpecs(ToolIdentity identity) {
        List<Eligible> out = new ArrayList<>();
        Set<String> builtinNames = org.dromara.assistant.core.ToolRegistry.allToolNames();
        Set<String> seen = new LinkedHashSet<>();
        for (BizToolProvider provider : providers) {
            List<BizToolSpec> specs;
            try {
                specs = provider.tools();
            } catch (Exception e) {
                log.warn("[Assistant] provider {} tools() failed, skipped: {}",
                    provider.namespace(), e.getMessage());
                continue;
            }
            if (specs == null) {
                continue;
            }
            for (BizToolSpec spec : specs) {
                String name = spec == null ? null : spec.getName();
                if (name == null || name.isBlank()) {
                    continue;
                }
                if (builtinNames.contains(name)) {
                    log.warn("[Assistant] provider tool {} conflicts with builtin, provider yields", name);
                    continue;
                }
                if (!seen.add(name)) {
                    // provider 间撞名: 先到先得, 后来者让位(避免静默覆盖)
                    log.warn("[Assistant] provider tool {} duplicated across providers, first wins", name);
                    continue;
                }
                if (identity.getRoleLevel() < spec.getMinRoleLevel()) {
                    continue;
                }
                if (!permissionSatisfied(spec.getRequiredPermission(), identity)) {
                    continue;
                }
                out.add(new Eligible(provider, spec));
            }
        }
        return out;
    }

    /**
     * 权限串门: null/空=直通; 否则 menuPermissions 含 * / *:*:* / 精确串之一才过。
     * 口径与 ToolRegistry.wildcardOrContains 同源(超管通配三态并认)。
     */
    static boolean permissionSatisfied(String requiredPermission, ToolIdentity identity) {
        if (requiredPermission == null || requiredPermission.isBlank()) {
            return true;
        }
        Set<String> menuPermissions = identity.getLoginUser() == null ? null
            : identity.getLoginUser().getMenuPermission();
        // glm53 P2-8: 通配三态口径收敛到 ToolRegistry 单点(canCreateMission 已随批3 退役)
        return ToolRegistry.wildcardOrContains(menuPermissions, requiredPermission);
    }
}
