-- 小修包批(2026-11-02, 剩余工作板回执「全按推荐」题6): 摘除邀请注册死入口。
-- 背景: 菜单 9181(system:user:invite) 自 2026-09-26 挂出, 后端接口从未实现
-- (/dcs/inviteLink/** 全仓无 Controller), 用户点了没有任何响应——最伤信任的一类问题。
-- 拍板: 摘按钮; 补实现属大活, 需要时另立批次。
-- 伴随代码改动: 前端用户管理页按钮/弹窗与 api/system/inviteLink 目录已删,
-- 所以本迁移只需清菜单与角色关联。可重复执行。

BEGIN;

DELETE FROM public.sys_role_menu WHERE menu_id = 9181;
DELETE FROM public.sys_menu WHERE menu_id = 9181;

COMMIT;
