-- R2-2 引擎管理页: 画像与分析目录(12000)下新增 C 菜单「引擎管理」
-- 页面内写操作(重算/协议/Schema/灌数)复用既有 11971-11976 按钮权限位, 本迁移只加页面入口。
-- 可重复执行: IF NOT EXISTS 语义 + ON CONFLICT 跳过。
BEGIN;

INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 11989, '引擎管理', 12000, 9, 'engine-admin', 'dcs/profile/admin', '', '1', '0', 'C', '0', '0', 'profile:engine:query', 'setting', 103, 1, '2026-10-26 00:00:00', NULL, NULL, '画像引擎运维: 重算/协议/Schema/灌数(写操作复用 11971-11976 按钮位)'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 11989);

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 1, 11989
WHERE NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 1 AND menu_id = 11989);

COMMIT;
