package com.smartdock.assistant.spi;

import lombok.Data;

import java.util.Map;
import java.util.Set;

/**
 * 工具调用上下文(桥侧组装, 业务侧只读)。
 *
 * <p>权限门(minRoleLevel/requiredPermission)已由注册侧判定完才会进来;
 * menuPermissions 仅供业务做更细的行级判断。</p>
 *
 * @author smartdock
 */
@Data
public class BizToolCall {

    /** 目标工具名(= BizToolSpec.name) */
    private String toolName;

    /** 模型给出的参数(已按 schema 声明, 未声明的多余键由桥剔除) */
    private Map<String, Object> args;

    private String tenantId;

    private Long userId;

    private String username;

    /** 调用者角色档位(注册门槛已过, 这里供业务细分) */
    private int roleLevel;

    /** 调用者菜单权限串集合 */
    private Set<String> menuPermissions;

    /** 会话标识(可空) */
    private String threadId;
}
