-- ============================================================================
-- 画像方向重构 · 第二批：来源改名/停用（2026-10-27）
-- 板: 20261027-profile-direction-board.html 六件套②(三页合一页顺手补)
--   定性表承诺「来源管理入口缺失要修」——本迁移补:
--   ① profile_source_config 表(租户级: 显示名/启停, 未配置的源默认启用)
--   ② 按钮权限 profile:source:edit(12019 挂 12001 引擎运维, 跟随 11989 授权)
-- 幂等可重复执行。
-- ============================================================================

-- 1) 来源配置表
CREATE TABLE IF NOT EXISTS public.profile_source_config (
    config_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(64) NOT NULL,
    display_name character varying(64),
    enabled char(1) DEFAULT '1'::bpchar NOT NULL,
    remark character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_source_config PRIMARY KEY (config_id),
    CONSTRAINT ck_profile_source_config_enabled CHECK (enabled IN ('0', '1'))
);
-- 三审 dspro P2: 唯一性用部分索引(仅未删行)——未来加删除入口后, 删掉的配置不挡重建
CREATE UNIQUE INDEX IF NOT EXISTS uk_profile_source_config_tenant_source
  ON public.profile_source_config (tenant_id, source) WHERE del_flag = '0';

-- 2) 「来源改名/停用」按钮权限(挂引擎运维页 12001, 与引擎管理 tab 同授权面)
INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES (12019, '来源改名停用(引擎管理页内)', 12001, 22, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:source:edit', '#', 103, 1, now(), '画像方向重构第二批: 引擎管理页签「来源管理」写权限')
ON CONFLICT (menu_id) DO UPDATE SET menu_name = EXCLUDED.menu_name, parent_id = EXCLUDED.parent_id, order_num = EXCLUDED.order_num, perms = EXCLUDED.perms, update_time = now();

-- 3) 授权 = 引擎管理(11989)既有绑定并集(能在引擎管理页签看的人才能改来源)
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 12019 FROM public.sys_role_menu rm
WHERE rm.menu_id = 11989
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = 12019);
