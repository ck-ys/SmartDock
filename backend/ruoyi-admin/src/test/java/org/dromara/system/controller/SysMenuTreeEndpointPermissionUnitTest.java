package org.dromara.system.controller;

import cn.dev33.satoken.annotation.SaCheckPermission;
import cn.dev33.satoken.annotation.SaMode;
import org.dromara.system.domain.bo.SysMenuBo;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 菜单树接口的租户可访问性守门测试。
 *
 * <p>角色管理弹窗(新增/修改)依赖 /system/menu/treeselect 与 /roleMenuTreeselect 画权限树,
 * 而租户套餐有意不发放 system:menu:*(sys_menu 是全局表, 租户不得管理菜单)。
 * 这两个端点若收紧为仅 system:menu:query, 租户管理员就会"有角色增改权限却打不开弹窗"
 * (2026-09-29 生产实证: 某租户管理员建/改角色 403)。返回值本就按当前
 * 用户过滤(SysMenuServiceImpl#selectMenuList 非 userId=1 走 buildMenuByUserSql),
 * 放行 OR 不会向租户泄露全局菜单。</p>
 */
@Tag("dev")
class SysMenuTreeEndpointPermissionUnitTest {

    @Test
    void menuTreeEndpointsAcceptMenuQueryOrRoleQuery() throws Exception {
        Class<?> controller = Class.forName("org.dromara.system.controller.system.SysMenuController");

        assertMenuOrRoleQuery(controller.getMethod("treeselect", SysMenuBo.class));
        assertMenuOrRoleQuery(controller.getMethod("roleMenuTreeselect", Long.class));
    }

    private static void assertMenuOrRoleQuery(Method method) {
        SaCheckPermission permission = method.getAnnotation(SaCheckPermission.class);
        assertThat(permission).as(method.getName() + " permission").isNotNull();
        assertThat(permission.value())
            .as(method.getName() + " 须同时接受 menu:query 与 role:list(租户角色弹窗依赖)")
            .containsExactlyInAnyOrder("system:menu:query", "system:role:list");
        assertThat(permission.mode()).as(method.getName() + " 须为 OR 任一即可").isEqualTo(SaMode.OR);
    }
}
