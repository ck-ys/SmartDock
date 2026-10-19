package com.smartdock.assistant.spi;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具声明(schema + 权限门槛 + 确认卡文案)。
 *
 * @author smartdock
 */
@Data
public class BizToolSpec {

    /** 工具名(全局唯一, 建议 namespace 前缀) */
    private String name;

    /** 中文展示名 */
    private String displayName;

    /** 给模型看的功能描述——写清"何时该用", 模型选工具就靠它 */
    private String description;

    /** 参数声明 */
    private List<BizParamSpec> params = new ArrayList<>();

    /**
     * true=只读直接执行; false=写操作, 调用时出 HITL 确认卡(复用平台既有确认卡流程)。
     */
    private boolean readOnly = true;

    /** 角色门槛(0=所有人; 档位语义与助手 ToolIdentity 对齐, 由桥侧比较) */
    private int minRoleLevel = 0;

    /** 权限串门(可空): menuPermissions 含 * / *:*:* / 精确串之一才注册 */
    private String requiredPermission;

    /** readOnly=false 时确认卡标题(可空=用 displayName) */
    private String confirmationTitle;

    public static BizToolSpec readOnly(String name, String displayName, String description) {
        BizToolSpec s = new BizToolSpec();
        s.setName(name);
        s.setDisplayName(displayName);
        s.setDescription(description);
        s.setReadOnly(true);
        return s;
    }

    public static BizToolSpec write(String name, String displayName, String description, String confirmationTitle) {
        BizToolSpec s = readOnly(name, displayName, description);
        s.setReadOnly(false);
        s.setConfirmationTitle(confirmationTitle);
        return s;
    }
}
