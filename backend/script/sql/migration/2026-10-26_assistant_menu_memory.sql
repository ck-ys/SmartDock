-- 2026-10-26 助手三页批一 P0-2 菜单调整 DDL
-- 内容: AI助手记忆菜单 11928 从顶级隐藏挪入「AI 助手」目录(11930)下并翻显
-- 说明: parent_id 0→11930、path 'assistant-memory'→'memory'(完整路由 /ai/memory)、visible '1'→'0'(显示);
--       component 保持 dcs/assistant/memory/index 不变;
--       sys_role_menu 无需处理——baseline/04_role_menu.sql 已有 role 5(成员)/6(租户管理员) 对 11928 的绑定
-- 可重复执行: UPDATE 幂等, 重复跑结果不变
-- 回退: UPDATE public.sys_menu SET parent_id = 0, path = 'assistant-memory', visible = '1' WHERE menu_id = 11928;

UPDATE public.sys_menu
SET parent_id = 11930,
    path      = 'memory',
    visible   = '0'
WHERE menu_id = 11928;
