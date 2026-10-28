-- 5b 批（2026-10-28 拍板）：技能内容在线层 dcs_assistant_skill
-- 可重复执行（IF NOT EXISTS / IF NOT EXISTS 约束 DO 块 / 存在性守卫 INSERT）；与 baseline 01_schema.sql 同步维护。
-- 语义：tenant_id='000000' 行=全局基线；租户行同 skill_id 覆盖全局行。
-- 无触发词列（题7 甲拍板：description=模型触发唯一依据）。

CREATE TABLE IF NOT EXISTS public.dcs_assistant_skill (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    skill_id character varying(64) NOT NULL,
    display_name character varying(64),
    description character varying(1024) NOT NULL,
    min_role character varying(16) DEFAULT 'member'::character varying NOT NULL,
    body text NOT NULL,
    disabled smallint DEFAULT 0 NOT NULL,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'dcs_assistant_skill_pk') THEN
        ALTER TABLE public.dcs_assistant_skill ADD CONSTRAINT dcs_assistant_skill_pk PRIMARY KEY (id);
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uk_dcs_assistant_skill_tenant
    ON public.dcs_assistant_skill (tenant_id, skill_id);

-- 权限串按钮挂「AI 助手管理」（menu_id=11927，02_reference.sql 实测）；角色 6=租户管理员
INSERT INTO sys_menu (menu_id, menu_name, parent_id, order_num, path, component, is_frame, is_cache,
    menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 12040, '助手技能编辑', 11927, 10, '', NULL, 1, 0, 'F', '0', '0',
    'dcs:assistant:skill:edit', '#', 103, 1, CURRENT_TIMESTAMP, 1, NULL,
    '5b 批：管理台在线编辑技能内容（题5 甲·独立权限串）'
WHERE NOT EXISTS (SELECT 1 FROM sys_menu WHERE menu_id = 12040 OR perms = 'dcs:assistant:skill:edit');

-- 权限串授权不做自动绑定（三审 dsflash P1-5 回修）：角色 6（租户管理员）在基线里连宿主菜单
-- 11927 都没有，自动授 12040 只会造出「能盲写、不能读、看不到页面」的半授权；
-- 拍板口径=「超管默认有，其他管理员按需勾」（板 v7 题5），由部署方在角色管理里自行勾选。
