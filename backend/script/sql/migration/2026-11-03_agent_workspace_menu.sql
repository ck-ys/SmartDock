-- 批3（2026-11-03）：智能体工作台入口权限 agentws:workspace:use
-- 背景：工作台 /agent 是「独立分区」——前端 constantRoutes 直挂（不套业务壳层，自带 264 左栏），
--       因此这里只用按钮行(menu_type=F)下发权限串，不生成动态路由（避免同组件在业务壳内再注册一份）。
-- 权限语义：能进智能体工作台。会话页/助理接口仍各自校验 dcs:assistant:use（本行不放宽任何数据面）。
-- 授权：按「已绑 AI 助手会话 11920 的角色」派生（默认 5/6 + 存量自定义/多租户角色），
--       与工作台的 11920 入口同口径；超管走 *:*:* 不需要绑定。
-- 可重复执行：存在性守卫 INSERT。

INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache,
    menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 12060, '智能体工作台入口', 11930, 0, '', NULL, 1, 0,
    'F', '0', '0', 'agentws:workspace:use', '#', 103, 1, CURRENT_TIMESTAMP, 1, NULL,
    '批3：独立分区 /agent 的工作台入口权限（不生成动态路由）'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 12060 OR perms = 'agentws:workspace:use');

-- 授权按「既有助手使用者」派生：凡绑过 AI 助手会话 11920 的角色都补 12060
-- （默认角色 5/6 + 存量自定义角色/多租户角色），避免菜单被改道后点进 /403。
INSERT INTO sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 12060
FROM sys_role_menu rm
JOIN sys_role r ON r.role_id = rm.role_id
WHERE rm.menu_id = 11920
  AND EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 12060)
  AND NOT EXISTS (SELECT 1 FROM sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = 12060);
