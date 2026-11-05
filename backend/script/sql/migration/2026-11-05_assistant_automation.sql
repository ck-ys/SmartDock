-- 2026-11-05 自动化批（拍板 题0 甲 / 题1 乙 / 题2 甲 / 题3 甲 / 题4 乙 / 题5 甲 / 题6 甲 / 题7 乙 / 题8 甲）
-- 内容: ①dcs_automation 用户自建「到点替你跑一件事」（一句话 + 每天/每周几 + 开关）
--       ②dcs_automation_run 运行记录（计划时刻 / 状态 / 耗时 / 会话）
--       ③F 型权限行 12062 dcs:assistant:automation（不生成动态路由，只作权限载体）
-- 边界: 每人 5 条自建（服务端硬校验）；内置每日摘要为 builtin='1'（租户级 user_id=0），
--       只能开关、不能改提示词/删除，由服务首次列列表时懒建。
-- 幂等: 建表/索引 IF NOT EXISTS；菜单 ON CONFLICT (menu_id) DO UPDATE；角色绑定 NOT EXISTS。
-- 回退: DROP TABLE IF EXISTS public.dcs_automation_run; DROP TABLE IF EXISTS public.dcs_automation;
--       DELETE FROM public.sys_role_menu WHERE menu_id = 12062;
--       DELETE FROM public.sys_menu WHERE menu_id = 12062;

CREATE TABLE IF NOT EXISTS public.dcs_automation (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    user_id bigint NOT NULL,
    title character varying(120) NOT NULL,
    prompt text NOT NULL,
    schedule_type character varying(16) NOT NULL,
    schedule_time character varying(5),
    weekdays character varying(20),
    time_zone character varying(40) DEFAULT 'Asia/Shanghai'::character varying NOT NULL,
    enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    builtin character(1) DEFAULT '0'::bpchar NOT NULL,
    last_run_at timestamp without time zone,
    last_status character varying(16),
    last_run_id bigint,
    consecutive_failures integer DEFAULT 0 NOT NULL,
    next_run_at timestamp without time zone,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    del_flag character(1) DEFAULT '0'::bpchar NOT NULL,
    CONSTRAINT pk_dcs_automation PRIMARY KEY (id),
    CONSTRAINT ck_dcs_automation_enabled CHECK (((enabled)::text = ANY ((ARRAY['0'::character varying, '1'::character varying])::text[]))),
    CONSTRAINT ck_dcs_automation_schedule CHECK (((schedule_type)::text = ANY ((ARRAY['DAILY'::character varying, 'WEEKLY'::character varying, 'BUILTIN'::character varying])::text[])))
);

CREATE TABLE IF NOT EXISTS public.dcs_automation_run (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    automation_id bigint NOT NULL,
    user_id bigint NOT NULL,
    trigger_type character varying(16) NOT NULL,
    scheduled_for timestamp without time zone NOT NULL,
    status character varying(16) NOT NULL,
    thread_id character varying(128),
    conversation_id bigint,
    result_summary text,
    error_message character varying(1000),
    duration_ms integer,
    started_at timestamp without time zone,
    finished_at timestamp without time zone,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_dcs_automation_run PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_dcs_automation_user
    ON public.dcs_automation USING btree (tenant_id, user_id, del_flag, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_dcs_automation_due
    ON public.dcs_automation USING btree (next_run_at)
    WHERE ((enabled = '1'::bpchar) AND (del_flag = '0'::bpchar) AND (next_run_at IS NOT NULL));
CREATE INDEX IF NOT EXISTS idx_dcs_automation_run_auto
    ON public.dcs_automation_run USING btree (tenant_id, automation_id, scheduled_for DESC);
CREATE INDEX IF NOT EXISTS idx_dcs_automation_run_user
    ON public.dcs_automation_run USING btree (tenant_id, user_id, create_time DESC);
-- 同一条自动化的同一计划时刻只落一行（多实例/重扫去重）
CREATE UNIQUE INDEX IF NOT EXISTS uk_dcs_automation_run_slot
    ON public.dcs_automation_run USING btree (automation_id, scheduled_for)
    WHERE ((trigger_type)::text = 'SCHEDULE'::text);
-- 同一条自动化同时只允许一个 RUNNING（进程崩溃由扫描器按超时收口，见 AutomationScanJob）
CREATE UNIQUE INDEX IF NOT EXISTS uk_dcs_automation_run_running
    ON public.dcs_automation_run USING btree (automation_id)
    WHERE ((status)::text = 'RUNNING'::text);
-- 每个租户一条内置项（首次列列表时懒建，幂等靠这条唯一索引）
CREATE UNIQUE INDEX IF NOT EXISTS uk_dcs_automation_builtin
    ON public.dcs_automation USING btree (tenant_id)
    WHERE ((builtin)::text = '1'::text);

COMMENT ON TABLE public.dcs_automation IS '用户自建自动化（到点跑一轮只读 AI 任务；builtin=1 为系统内置项）';
COMMENT ON COLUMN public.dcs_automation.schedule_time IS '每天/每周的执行时刻 HH:mm（时区见 time_zone，当前固定 Asia/Shanghai）';
COMMENT ON COLUMN public.dcs_automation.weekdays IS '每周几，ISO 1-7 逗号分隔（1=周一）';
COMMENT ON COLUMN public.dcs_automation.next_run_at IS '下次计划时刻；扫描器认领时先推进此列再执行';
COMMENT ON TABLE public.dcs_automation_run IS '自动化运行记录（一次执行一行；scheduled_for=计划时刻）';

-- 权限载体：F 型行不生成动态路由，只把 dcs:assistant:automation 发到角色权限集
INSERT INTO public.sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (12062, '自动化任务', 11930, 12, '', NULL, NULL,
     '1', '0', 'F', '0', '0', 'dcs:assistant:automation', '#',
     103, 1, now(), NULL, NULL, '自动化一级页/新建/开关/立即运行权限载体(不生成动态路由)')
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

-- 授权口径与「已绑 AI 助手会话 11920」派生一致（全新装=成员/租户管理员；升级库按现有绑定）
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 12062
FROM public.sys_role_menu rm
JOIN public.sys_role r ON r.role_id = rm.role_id
WHERE rm.menu_id = 11920
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = 12062);
