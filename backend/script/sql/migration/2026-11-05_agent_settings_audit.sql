-- 2026-11-05 工作台设置页·审计分节授权（拍板 A3 / 题5 乙）
-- 背景：智能体工作台的「设置」改成独立页后，审计从设置页进（只读，复用
--       /dcs/assistant/action/audit/* 现有接口与数据）。业务系统里的「写操作审计」
--       11936 保持原样、保持 0 角色绑定——不给 admin 绑 11936，避免业务侧栏多出菜单。
-- 做法：新增 1 条 F 型权限行 12061（menu_type=F，path 空，不生成动态路由），perms 复用
--       dcs:assistant:audit:query；再显式授给 role_key IN ('admin','tenant_admin')。
-- 边界：成员角色 5 不得获得（本脚本只按 role_key 选，天然不含 member）；
--       file_reviewer 等其它角色也不授。超管走 *:*:* 不需要绑定。
-- 幂等：菜单 ON CONFLICT (menu_id) DO UPDATE；角色绑定 NOT EXISTS。
-- 回退：DELETE FROM sys_role_menu WHERE menu_id = 12061;
--       DELETE FROM sys_menu WHERE menu_id = 12061;

INSERT INTO public.sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (12061, '工作台审计查看', 11930, 11, '', NULL, NULL,
     '1', '0', 'F', '0', '0', 'dcs:assistant:audit:query', '#',
     103, 1, now(), NULL, NULL, '工作台设置页审计分节(只读; 仅授 admin/tenant_admin, 不生成路由)')
ON CONFLICT (menu_id) DO UPDATE SET
    parent_id = EXCLUDED.parent_id,
    order_num = EXCLUDED.order_num,
    path = EXCLUDED.path,
    component = EXCLUDED.component,
    menu_type = EXCLUDED.menu_type,
    visible = EXCLUDED.visible,
    status = EXCLUDED.status,
    perms = EXCLUDED.perms,
    remark = EXCLUDED.remark,
    update_time = now();

-- 显式授权：只给管理员（租户管理员 + 演示库的自定义 admin 角色），不给成员
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT r.role_id, 12061
FROM public.sys_role r
WHERE r.role_key IN ('admin', 'tenant_admin')
  AND r.del_flag = '0'
  AND r.status = '0'
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu x WHERE x.role_id = r.role_id AND x.menu_id = 12061);
