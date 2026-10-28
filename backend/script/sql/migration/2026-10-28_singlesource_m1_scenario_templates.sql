-- 单源重构 M1 F 片(2026-10-28): 场景模板库。
-- ① 新表 profile_scenario_template(我的模板, 导入落库; 内置 7 套是代码常量不落库)
-- ② 权限按钮 12021 profile:scenario:edit 挂画像工作室(11979), 授角色 1+6
-- 可重复执行: IF NOT EXISTS / WHERE NOT EXISTS 全覆盖。执行后重启后端生效。

BEGIN;

CREATE TABLE IF NOT EXISTS public.profile_scenario_template (
    template_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    template_code character varying(64) NOT NULL,
    template_name character varying(100) NOT NULL,
    scenario character varying(50),
    description character varying(500),
    definition jsonb NOT NULL,
    builtin character(1) DEFAULT '0'::bpchar NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_scenario_template PRIMARY KEY (template_id),
    CONSTRAINT ck_profile_scenario_builtin CHECK (builtin IN ('0', '1'))
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_profile_scenario_template_code
  ON public.profile_scenario_template (tenant_id, template_code) WHERE del_flag = '0';

INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 12021, '场景模板套用(工作室页内)', 11979, 10, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:scenario:edit', '#', 103, 1, '2026-10-28 00:00:00', NULL, NULL, '单源重构第一批(F 片): 模板库套用/导入导出/删除写权限(蕴含维度/权重/分群/漏斗四类编辑能力), 挂画像工作室'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 12021);

-- 三审回修(glm53 P2): 角色 6 授权带角色存在性守卫(sys_role_menu 无外键, 无角色 6 的环境会插孤儿行)
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 1, 12021 WHERE NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 1 AND menu_id = 12021);
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 6, 12021
WHERE EXISTS (SELECT 1 FROM public.sys_role WHERE role_id = 6)
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 6 AND menu_id = 12021);

COMMIT;
