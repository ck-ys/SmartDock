-- 2026-10-25 深度优化批 R2: 分析中心 CSV 导出按钮位(菜单 11988, 权限串 analytics:center:export)
-- 可重复执行: ON CONFLICT / NOT EXISTS 幂等
-- 对应 baseline 同步: script/sql/baseline/07_analytics.sql 菜单区

INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(11988, '数据分析导出', 11986, 15, '', NULL, NULL, '1', '0', 'F', '1', '0', 'analytics:center:export', '#', 103, 1, '2026-10-25 00:00:00', NULL, NULL, '访问明细/自定义分析 CSV 导出')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 1, 11988 WHERE NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 1 AND menu_id = 11988);
