-- =====================================================================
-- R2-4 报表资产与联动(2026-10-26): 自定义分析视图表 + 分析报表三按钮位
-- 「保存当前视图」落库(租户+创建人隔离, 重名=覆盖); 权限 analytics:report:*
-- 可重复执行: IF NOT EXISTS / NOT EXISTS / ON CONFLICT。
-- =====================================================================
BEGIN;

CREATE TABLE IF NOT EXISTS public.analytics_custom_report (
    report_id    bigint       NOT NULL,
    tenant_id    varchar(20)  NOT NULL DEFAULT '000000',
    report_name  varchar(100) NOT NULL,
    source       varchar(50)  NOT NULL,
    dimension    varchar(50)  NOT NULL DEFAULT 'day',
    metric       varchar(20)  NOT NULL DEFAULT 'pv',
    days         integer      NOT NULL DEFAULT 30,
    limit_rows   integer      NOT NULL DEFAULT 20,
    create_by    bigint,
    create_time  timestamp,
    update_by    bigint,
    update_time  timestamp,
    CONSTRAINT pk_analytics_custom_report PRIMARY KEY (report_id),
    CONSTRAINT uk_analytics_custom_report UNIQUE (tenant_id, create_by, report_name)
);
COMMENT ON TABLE public.analytics_custom_report IS '分析中心-自定义分析视图(R2-4 保存当前视图)';
COMMENT ON COLUMN public.analytics_custom_report.report_name IS '视图名(同租户同人唯一, 重名保存=覆盖)';
COMMENT ON COLUMN public.analytics_custom_report.dimension IS '维度: day/page/channel/campaign/device/browser/os/geo/entry/exit/search/event';
COMMENT ON COLUMN public.analytics_custom_report.metric IS '指标: pv/uv/visits/events';

-- 菜单: 数据分析(11986)下三个 F 按钮位
INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 11992, '分析报表查询', 11986, 15, '', NULL, NULL, '1', '0', 'F', '1', '0', 'analytics:report:query', '#', 103, 1, '2026-10-26 00:00:00', NULL, NULL, '自定义分析视图列表'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 11992);

INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 11993, '分析报表保存', 11986, 16, '', NULL, NULL, '1', '0', 'F', '1', '0', 'analytics:report:save', '#', 103, 1, '2026-10-26 00:00:00', NULL, NULL, '保存/覆盖自定义分析视图'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 11993);

INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 11994, '分析报表删除', 11986, 17, '', NULL, NULL, '1', '0', 'F', '1', '0', 'analytics:report:remove', '#', 103, 1, '2026-10-26 00:00:00', NULL, NULL, '删除自定义分析视图'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 11994);

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id FROM (VALUES (11992), (11993), (11994)) AS m(menu_id)
WHERE NOT EXISTS (SELECT 1 FROM public.sys_role_menu rm WHERE rm.role_id = 1 AND rm.menu_id = m.menu_id)
ON CONFLICT (role_id, menu_id) DO NOTHING;

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 6, m.menu_id FROM (VALUES (11992), (11993), (11994)) AS m(menu_id)
WHERE EXISTS (SELECT 1 FROM public.sys_role WHERE role_id = 6)
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu rm WHERE rm.role_id = 6 AND rm.menu_id = m.menu_id)
ON CONFLICT (role_id, menu_id) DO NOTHING;

COMMIT;
