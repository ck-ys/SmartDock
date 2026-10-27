-- ============================================================================
-- 画像方向重构 · 第一批：菜单收缩 11 → 3（2026-10-27）
-- 板: 20261027-profile-direction-board.html 题0★D 六件套②
--   一级只留 数据分析/用户画像；其余 5 项收进「引擎与运维」折叠组(12018)；
--   数据运维/引擎健康/引擎管理 三页合一(引擎运维)；低分复核并入用户画像页。
-- 权限不倒退: 被合并页面菜单转隐藏 F 按钮(权限串/角色绑定全保留)，
--   合一页 tab 与画像页复核 drawer 用 v-hasPermi 按同一权限串控制。
-- 幂等可重复执行。
-- ============================================================================

-- 1) 新目录「引擎与运维」12018(12011-12017 已被合规按钮占用)
INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES (12018, '引擎与运维', 12000, 3, 'engine-ops', NULL, NULL, '1', '0', 'M', '0', '0', '', 'setting', 103, 1, now(), '画像方向重构: 工作室/RFM/分群/合规/引擎运维收纳为折叠组')
ON CONFLICT (menu_id) DO UPDATE SET menu_name = EXCLUDED.menu_name, parent_id = EXCLUDED.parent_id, order_num = EXCLUDED.order_num, path = EXCLUDED.path, update_time = now();

-- 2) 四页挪进折叠组
UPDATE public.sys_menu SET parent_id = 12018, order_num = 1, update_time = now() WHERE menu_id = 11979; -- 画像工作室
UPDATE public.sys_menu SET parent_id = 12018, order_num = 2, update_time = now() WHERE menu_id = 11980; -- 画像RFM
UPDATE public.sys_menu SET parent_id = 12018, order_num = 3, update_time = now() WHERE menu_id = 11981; -- 画像分群
UPDATE public.sys_menu SET parent_id = 12018, order_num = 4, update_time = now() WHERE menu_id = 12010; -- 合规中心

-- 3) 数据运维 → 引擎运维(三页合一宿主)，挪进折叠组
UPDATE public.sys_menu SET parent_id = 12018, order_num = 5, menu_name = '引擎运维',
  remark = '画像方向重构: 数据运维+引擎健康+引擎管理三页合一', update_time = now()
WHERE menu_id = 12001;

-- 4) 引擎健康/引擎管理 菜单转隐藏按钮挂到 12001 下(权限串与角色绑定原样保留)
UPDATE public.sys_menu SET menu_type = 'F', parent_id = 12001, order_num = 20, path = '', component = NULL,
  menu_name = '引擎健康(页内)', visible = '1', update_time = now() WHERE menu_id = 12002;
UPDATE public.sys_menu SET menu_type = 'F', parent_id = 12001, order_num = 21, path = '', component = NULL,
  menu_name = '引擎管理(页内)', visible = '1', update_time = now() WHERE menu_id = 11989;

-- 5) 低分复核 菜单转隐藏按钮挂到 9070 用户画像下(drawer 入口用同一权限串)
UPDATE public.sys_menu SET menu_type = 'F', parent_id = 9070, order_num = 22, path = '', component = NULL,
  menu_name = '低分复核(画像页内)', visible = '1', update_time = now() WHERE menu_id = 11982;

-- 6) 一级排序: 数据分析 1 / 用户画像 2 / 折叠组 3
UPDATE public.sys_menu SET order_num = 1, update_time = now() WHERE menu_id = 11986;
UPDATE public.sys_menu SET order_num = 2, update_time = now() WHERE menu_id = 9070;

-- 7) 折叠组目录授权 = 原子菜单绑定角色并集(适配任意环境的既有绑定)
--    并集含被合并的 12002/11989(引擎健康/管理); 11982(低分复核)转按钮挂 9070,
--    9070 本身是显式菜单不需要并集, 但要给「只授过 11982 没授过 9070」的角色补宿主
--    (三审 glm53 P1: 少这半句, 这类角色权限串还在但画像页路由不可达, 复核抽屉静默失联)
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 12018 FROM public.sys_role_menu rm
WHERE rm.menu_id IN (11979, 11980, 11981, 12001, 12010, 12002, 11989)
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = 12018);
-- 转隐藏按钮的三页: 确保其权限串挂靠的父菜单对授过它们的角色可达
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 12001 FROM public.sys_role_menu rm
WHERE rm.menu_id IN (12002, 11989)
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = 12001);
-- 低分复核(11982)→用户画像(9070) 宿主保证: 只授过复核没授过画像页的角色补画像页入口
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 9070 FROM public.sys_role_menu rm
WHERE rm.menu_id = 11982
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = 9070);

-- 8) 清理已删角色残留的 12018 绑定(三审 dspro 补充: 并集 INSERT 不会回收死角色的行)
DELETE FROM public.sys_role_menu rm
WHERE rm.menu_id = 12018
  AND NOT EXISTS (SELECT 1 FROM public.sys_role r WHERE r.role_id = rm.role_id);
