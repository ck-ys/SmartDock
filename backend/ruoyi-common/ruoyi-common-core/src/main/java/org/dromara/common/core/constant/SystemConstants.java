package org.dromara.common.core.constant;

/**
 * 系统常量信息
 *
 * @author Lion Li
 */
public interface SystemConstants {

    /**
     * 正常状态
     */
    String NORMAL = "0";

    /**
     * 异常状态
     */
    String DISABLE = "1";

    /**
     * 是否为系统默认（是）
     */
    String YES = "Y";

    /**
     * 是否为系统默认（否）
     */
    String NO = "N";

    /**
     * 是否菜单外链（是）
     */
    String YES_FRAME = "0";

    /**
     * 是否菜单外链（否）
     */
    String NO_FRAME = "1";

    /**
     * 菜单类型（目录）
     */
    String TYPE_DIR = "M";

    /**
     * 菜单类型（菜单）
     */
    String TYPE_MENU = "C";

    /**
     * 菜单类型（按钮）
     */
    String TYPE_BUTTON = "F";

    /**
     * Layout组件标识
     */
    String LAYOUT = "Layout";

    /**
     * ParentView组件标识
     */
    String PARENT_VIEW = "ParentView";

    /**
     * InnerLink组件标识
     */
    String INNER_LINK = "InnerLink";

    /**
     * 超级管理员ID
     */
    Long SUPER_ADMIN_ID = 1L;

    /**
     * 根部门祖级列表
     */
    String ROOT_DEPT_ANCESTORS = "0";

    /**
     * 默认部门 ID
     */
    Long DEFAULT_DEPT_ID = 100L;

    /**
     * 排除敏感属性字段
     *
     * <p>O-5（2026-10-06）：补 configValue/secret/token 族——参数设置 PUT 的值可携带
     * 第三方密钥（如 AI 助手博查 key），明文进请求日志=密钥泄露面。
     * 脱敏只影响日志输出，不影响业务读写。</p>
     *
     * <p>PD-4（2026-10-07 试点走查）：补 contactPhone——租户新建/编辑 POST /system/tenant
     * 的联系人手机号明文进请求日志，属强 PII。</p>
     */
    String[] EXCLUDE_PROPERTIES = { "password", "oldPassword", "newPassword", "confirmPassword",
        "configValue", "secret", "accessToken", "refreshToken", "contactPhone" };


}
