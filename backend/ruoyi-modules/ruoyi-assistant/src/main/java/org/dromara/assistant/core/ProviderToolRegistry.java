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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
     * 按会话身份注册全部达标工具(在 ToolRegistry.registerAll 之后调用, 内置优先)。
     *
     * @return 实际注册的工具名集合(守卫断言用)
     */
    public Set<String> registerProviders(Toolkit toolkit, ToolIdentity identity, String threadId,
                                         PermissionContextState.Builder pb) {
        Set<String> registered = new LinkedHashSet<>();
        Set<String> builtinNames = org.dromara.assistant.core.ToolRegistry.allToolNames();
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
                if (!registered.add(name)) {
                    // provider 间撞名: 先到先得, 后来者让位(避免静默覆盖)
                    log.warn("[Assistant] provider tool {} duplicated across providers, first wins", name);
                    continue;
                }
                registered.remove(name); // add 仅用于判重, 真正登记在注册成功后
                if (identity.getRoleLevel() < spec.getMinRoleLevel()) {
                    continue;
                }
                if (!permissionSatisfied(spec.getRequiredPermission(), identity)) {
                    continue;
                }
                try {
                    toolkit.registerAgentTool(new BizToolAgentAdapter(provider, spec, identity, threadId));
                    PermissionBehavior behavior = spec.isReadOnly() ? PermissionBehavior.ALLOW : PermissionBehavior.ASK;
                    PermissionRule rule = new PermissionRule(name, "", behavior,
                        "biz-provider:" + provider.namespace());
                    if (spec.isReadOnly()) {
                        pb.addAllowRule(name, rule);
                    } else {
                        pb.addAskRule(name, rule);
                    }
                    registered.add(name);
                } catch (Exception e) {
                    log.warn("[Assistant] provider tool {} register failed: {}", name, e.getMessage());
                }
            }
        }
        return registered;
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
