-- 2026-11-01 三批③ 写操作审计：dcs_assistant_action 补 release_mode + 审计独立页菜单
-- 内容: ①动作账本补 release_mode 列（confirm=确认卡放行 / once=同类已确认 / auto=全自动 /
--       allow=平台放行；REJECTED 行为空——拒绝行没有放行动作）
--       ②「写操作审计」独立菜单（menu_id=11936，挂「AI 助手」目录 11930 下 order 3，
--       path=assistant-audit，component=dcs/assistant/audit/index，perms=dcs:assistant:audit:query）
-- 幂等: 列 ADD COLUMN IF NOT EXISTS；菜单 ON CONFLICT (menu_id) DO UPDATE 校正
--       （照 2026-10-22_support_changelog_menu.sql:11-28 范式——该文件头注记载的正是
--       「迁移漏菜单→页面无路由白屏」同型事故，存量环境跑迁移即得菜单，杜绝复发）
-- 角色绑定: sys_role_menu 不在 SQL 里绑角色（超管天然可见，租户管理员按现角色授权流程手工挂）
-- 回退: ALTER TABLE public.dcs_assistant_action DROP COLUMN IF EXISTS release_mode;
--       DELETE FROM public.sys_menu WHERE menu_id = 11936;

ALTER TABLE public.dcs_assistant_action
    ADD COLUMN IF NOT EXISTS release_mode character varying(16);

COMMENT ON COLUMN public.dcs_assistant_action.release_mode
    IS '放行方式(confirm=确认卡放行/once=同类已确认/auto=全自动/allow=平台放行; REJECTED行为空)';

INSERT INTO public.sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (11936, '写操作审计', 11930, 3, 'assistant-audit', 'dcs/assistant/audit/index', '',
     '1', '0', 'C', '0', '0', 'dcs:assistant:audit:query', 'search',
     103, 1, now(), NULL, NULL, '助手写操作审计(动作账本四维查询/CSV导出, 不含工具参数)')
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
