-- 批9 画像运营化: 触达/导出权限按钮(挂 9070 用户画像下, 与画像域 F 按钮同风格)
-- 可重复执行; baseline 02_reference.sql 已同步
INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 11990, '画像触达查询', 9070, 20, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:reach:query', '#', 103, 1, '2026-10-20 00:00:00', NULL, NULL, '触达记录/逐人明细/导出记录查看'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 11990);

INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 11991, '画像触达与导出', 9070, 21, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:reach:edit', '#', 103, 1, '2026-10-20 00:00:00', NULL, NULL, '发通知/失败重试/成员导出(明文)'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 11991);

-- 角色授权: 管理员角色(role_id=1)默认全量(F 按钮对超管天然可见, 此处给 member 之外的运营角色留位);
-- 演示库仅超管使用触达能力, 不给 user001 授权(双视角验收用)
