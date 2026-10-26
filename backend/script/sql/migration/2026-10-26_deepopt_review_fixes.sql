-- 2026-10-26 画像与分析深度优化批·三审回修 DDL
-- 内容: ①backfill 手工入口独立权限位 analytics:backfill:run(菜单 11995, 只授超管角色 1)
-- 说明: 跨租户补数是运维级操作, 不能复用租户级的 analytics:center:edit(普通分析编辑者会被误授全租户遍历)
-- 可重复执行: IF NOT EXISTS + ON CONFLICT

INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 11995, '日汇总补数', 11986, 18, '', NULL, NULL, '1', '0', 'F', '1', '0', 'analytics:backfill:run', '#', 103, 1, '2026-10-26 00:00:00', NULL, NULL, '手工触发跨租户日汇总补数(遍历所有租户)'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 11995);

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 1, 11995
WHERE EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 11995)
ON CONFLICT (role_id, menu_id) DO NOTHING;
