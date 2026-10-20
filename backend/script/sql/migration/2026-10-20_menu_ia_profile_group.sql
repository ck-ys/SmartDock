-- 2026-10-20 菜单 IA：画像与分析独立成顶级组（双引擎定位）
--
-- 背景：二期画像能力的 6 个页面此前平铺在「任务中心」下，与任务流混装且
-- order_num 撞车（两个 1、两个 3、两个 4、三个 6）。本迁移把画像域迁入
-- 新的顶级目录「画像与分析」(12000, path=insight)，任务中心收窄为纯任务流；
-- 同时删除隐藏的 RuoYi「PLUS 官网」框架外链。
--
-- 幂等：全部使用 ON CONFLICT / 条件更新，可重复执行。
-- ID 段：12000 起（11970-11987 已被画像/分析菜单占用，11990+ 留二期窗口）。
BEGIN;

INSERT INTO public.sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (12000, '画像与分析', 0, 79, 'insight', NULL, NULL, '1', '0',
   'M', '0', '0', '', 'peoples', 103, 1, '2026-10-20 00:00:00', NULL, NULL,
   '双引擎之一：画像/分群/复核/分析中心')
ON CONFLICT (menu_id) DO UPDATE SET
  menu_name = EXCLUDED.menu_name,
  parent_id = EXCLUDED.parent_id,
  order_num = EXCLUDED.order_num,
  path = EXCLUDED.path,
  icon = EXCLUDED.icon,
  visible = '0',
  status = '0',
  remark = EXCLUDED.remark;

-- 画像 6 页迁入新组（path 不变，前端路由前缀由父级 path 决定 → /insight/*）
UPDATE public.sys_menu SET parent_id = 12000, order_num = 1 WHERE menu_id = 9070;  -- 用户画像
UPDATE public.sys_menu SET parent_id = 12000, order_num = 2 WHERE menu_id = 11979; -- 画像工作室
UPDATE public.sys_menu SET parent_id = 12000, order_num = 3 WHERE menu_id = 11980; -- 画像 RFM
UPDATE public.sys_menu SET parent_id = 12000, order_num = 4 WHERE menu_id = 11981; -- 画像分群
UPDATE public.sys_menu SET parent_id = 12000, order_num = 5 WHERE menu_id = 11982; -- 低分复核
UPDATE public.sys_menu SET parent_id = 12000, order_num = 6 WHERE menu_id = 11986; -- 数据分析

-- 任务中心收窄为纯任务流，order 连续无撞车
UPDATE public.sys_menu SET order_num = 1 WHERE menu_id = 9001;  -- 任务管理
UPDATE public.sys_menu SET order_num = 2 WHERE menu_id = 9005;  -- 我的待办
UPDATE public.sys_menu SET order_num = 3 WHERE menu_id = 9020;  -- 我的消息
UPDATE public.sys_menu SET order_num = 4 WHERE menu_id = 11970; -- 审核队列
UPDATE public.sys_menu SET order_num = 5 WHERE menu_id = 9040;  -- 数据看板

-- 角色映射：超管始终可见；原先能看画像页的角色同步获得新组入口
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 12000
FROM public.sys_role_menu rm
WHERE rm.menu_id IN (9070, 11979, 11980, 11981, 11982, 11986)
ON CONFLICT DO NOTHING;

INSERT INTO public.sys_role_menu (role_id, menu_id) VALUES (1, 12000)
ON CONFLICT DO NOTHING;

-- 删除隐藏的框架外链（菜单 4：PLUS官网）
DELETE FROM public.sys_role_menu WHERE menu_id = 4;
DELETE FROM public.sys_menu WHERE menu_id = 4;

COMMIT;
