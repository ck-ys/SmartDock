-- 单源重构第一批·题4: 测试发送权限错位修复(完整版)
-- 现状两个问题(板上题4 实证 + 落库核对新发现):
--   ① 引擎管理页签入口 profile:engine:query(11989) 只授角色1, 租户管理员进不去测试发送页;
--   ② 发布按钮权限 profile:event:publish 原先没有任何菜单按钮挂载, 非超管(通配)永远看不到按钮。
-- 修复: 新增按钮位 12020(profile:event:publish, 挂引擎运维 12001 下) 授角色1+6;
--       角色6(租户管理员)补 12001(引擎运维页)+11989(引擎管理页签)。
-- 边界: 角色6 本就持有 schema/协议/维度/规则/重算等按钮权限但此前无页面入口(11971 recalc:run 同样被埋),
--       本次只是把已授权限的页面入口补齐, 未新增任何写权限; 来源改(12019)不随此开放。
-- 可重复执行: NOT EXISTS 跳过
BEGIN;

-- 1) 补挂发布按钮权限位
INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 12020, '测试发送(引擎管理页内)', 12001, 23, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:event:publish', '#', 103, 1, '2026-10-28 00:00:00', NULL, NULL, '单源重构第一批(题4): 补挂发布按钮权限位, 与页签入口(11989)一并对齐'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 12020);

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 1, 12020 WHERE NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 1 AND menu_id = 12020);

-- 2) 租户管理员: 引擎运维页 + 引擎管理页签 + 发布按钮
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 6, 12001 WHERE EXISTS (SELECT 1 FROM public.sys_role WHERE role_id = 6)
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 6 AND menu_id = 12001);

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 6, 11989 WHERE EXISTS (SELECT 1 FROM public.sys_role WHERE role_id = 6)
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 6 AND menu_id = 11989);

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 6, 12020 WHERE EXISTS (SELECT 1 FROM public.sys_role WHERE role_id = 6)
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 6 AND menu_id = 12020);

COMMIT;
