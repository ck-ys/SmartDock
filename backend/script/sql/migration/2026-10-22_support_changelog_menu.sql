-- 2026-10-22：修复「系统支持」白屏——补回迁移漏搬的「更新日志」子菜单
-- 背景：开源迁移基线只搬了顶级目录 9180，未搬子菜单 9160（git 全历史无 9160 记录），
--       前端 views/dcs/changelog 与首页「全部日志 →」入口仍在，访问 /support 无子路由 → 白屏。
-- 口径：更新日志为纯前端静态页（无后端接口）；与母仓差异——反馈中心未迁移（本仓无对应页面），
--       可见范围改为全体登录角色（首页对所有角色展示入口）。
-- 可重复执行（幂等）：菜单 ON CONFLICT DO UPDATE 校正，授权 DO NOTHING。

INSERT INTO public.sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (9160, '更新日志', 9180, 1, 'changelog', 'dcs/changelog/index', '',
     '1', '0', 'C', '0', '0', '', 'log',
     103, 1, now(), NULL, NULL, '系统版本迭代记录(纯前端静态页, 全体登录角色可见)')
ON CONFLICT (menu_id) DO UPDATE SET
    parent_id = EXCLUDED.parent_id,
    path = EXCLUDED.path,
    component = EXCLUDED.component,
    menu_type = EXCLUDED.menu_type,
    visible = EXCLUDED.visible,
    status = EXCLUDED.status,
    perms = EXCLUDED.perms,
    remark = EXCLUDED.remark,
    update_time = now();

UPDATE public.sys_menu
   SET remark = '系统支持目录(更新日志; 纯前端静态页)', update_time = now()
 WHERE menu_id = 9180;

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
  FROM public.sys_role r
 CROSS JOIN (VALUES (9180), (9160)) AS m(menu_id)
 WHERE r.del_flag = '0' AND r.status = '0'
ON CONFLICT (role_id, menu_id) DO NOTHING;
