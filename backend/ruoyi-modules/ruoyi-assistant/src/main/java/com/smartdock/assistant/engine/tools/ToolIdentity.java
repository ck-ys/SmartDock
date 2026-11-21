package com.smartdock.assistant.engine.tools;

import lombok.Getter;
import org.dromara.common.core.domain.model.LoginUser;

/**
 * 工具身份快照（批B B1，2026-10-04）。
 *
 * <p>工厂建 agent 时从 resolver 传来的 LoginUser 固化：userId/tenantId 是所有查询
 * 工具的隔离锚点（服务层 LoginHelper.getUserId/getTenantId 的来源），roleLevel
 * 决定能力面（批C：成员不注册 act 工具）。</p>
 *
 * <p>禁 static holder——工厂每会话 new 一份随 agent 走，会话之间互不可见。</p>
 */
@Getter
public final class ToolIdentity {

    /** 成员（无写操作权限） */
    public static final int LEVEL_MEMBER = 0;
    /** 发布人（有 smartdock:mission:edit 等发布人特权） */
    public static final int LEVEL_PUBLISHER = 1;
    /** 审核人（写工具 SPEC 深审定案，2026-10-12）：持有 smartdock:mission:audit 的角色（leader 等
     * 审核岗常无 edit——按 edit 判会落 STUDENT，审核工具对真审核人不可见）。与 PUBLISHER 平级：
     * 两者是不同职能而非层级，各工具按需取门槛（act_forward=PUBLISHER / act_audit=REVIEWER），
     * 服务层「本任务审核人/发布人」硬校验照常兜底（挡住有权串但非本任务审核人的幻觉出卡） */
    public static final int LEVEL_REVIEWER = 1;
    /** 管理员（超管/租户管理员） */
    public static final int LEVEL_ADMIN = 2;

    private final Long userId;
    private final String tenantId;
    private final int roleLevel;
    /** 登录用户快照（工具内 runAs 重建登录态用，含权限集） */
    private final LoginUser loginUser;

    public ToolIdentity(Long userId, String tenantId, Integer roleLevel, LoginUser loginUser) {
        this.userId = userId;
        this.tenantId = tenantId;
        this.roleLevel = roleLevel == null ? LEVEL_MEMBER : roleLevel;
        this.loginUser = loginUser;
    }

    /**
     * 从登录用户推导身份：管理员→发布人/审核人→成员（就高不叠加）。
     *
     * <p>口径与 LoginHelper 对齐：超管=userId 常量判定；租户管理员=
     * rolePermission 含 admin（TenantConstants.TENANT_ADMIN_ROLE_KEY="admin"）；发布人特权=
     * menuPermission 含 smartdock:mission:edit（任务面 controller 同口径, 批9 前名为 DcsMissionController）；审核权=
     * menuPermission 含 smartdock:mission:audit（与 edit 平级职能判定，命中任一即出 STUDENT 档）。</p>
     */
    public static ToolIdentity from(LoginUser user) {
        int level;
        if (org.dromara.common.satoken.utils.LoginHelper.isSuperAdmin(user.getUserId())) {
            level = LEVEL_ADMIN;
        } else if (user.getRolePermission() != null
            && user.getRolePermission().contains("admin")) {
            level = LEVEL_ADMIN;
        } else if (user.getMenuPermission() != null
            && (user.getMenuPermission().contains("smartdock:mission:edit")
                || user.getMenuPermission().contains("smartdock:mission:audit")
                // 批3 回修(三审 5.3fh P1-2/dspro P2-10): 微核心新权限串同认——
                // 批6 删旧菜单后只持新串的发布人不会集体跌回成员档
                || user.getMenuPermission().contains("smartdock:task:add")
                || user.getMenuPermission().contains("smartdock:task:edit")
                || user.getMenuPermission().contains("smartdock:audit:audit"))) {
            level = LEVEL_PUBLISHER;
        } else {
            level = LEVEL_MEMBER;
        }
        return new ToolIdentity(user.getUserId(), user.getTenantId(), level, user);
    }
}
