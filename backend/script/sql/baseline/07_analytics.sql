-- =====================================================================
-- 批4 分析中心：会话、日汇总、漏斗、采集站点
-- 可重复执行（IF NOT EXISTS / ON CONFLICT DO NOTHING）
-- 依赖：01_schema.sql（profile_event / profile_subject）
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.analytics_session (
    session_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    session_key character varying(160) NOT NULL,
    visitor_id character varying(160) NOT NULL,
    subject_type character varying(32),
    subject_id character varying(64),
    anonymous_id character varying(128),
    is_new_visitor character(1) DEFAULT '0'::bpchar NOT NULL,
    started_at timestamp without time zone NOT NULL,
    ended_at timestamp without time zone NOT NULL,
    last_event_at timestamp without time zone NOT NULL,
    duration_seconds integer DEFAULT 0 NOT NULL,
    page_views integer DEFAULT 0 NOT NULL,
    event_count integer DEFAULT 0 NOT NULL,
    is_bounce character(1) DEFAULT '1'::bpchar NOT NULL,
    entry_page character varying(512),
    exit_page character varying(512),
    referrer character varying(1024),
    source_channel character varying(128),
    campaign character varying(128),
    device_type character varying(32),
    os character varying(64),
    browser character varying(64),
    country character varying(64),
    province character varying(64),
    city character varying(64),
    search_term character varying(256),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_analytics_session PRIMARY KEY (session_id),
    CONSTRAINT uk_analytics_session_key UNIQUE (tenant_id, source, session_key)
);
CREATE INDEX IF NOT EXISTS idx_analytics_session_time
    ON public.analytics_session USING btree (tenant_id, source, started_at DESC);
CREATE INDEX IF NOT EXISTS idx_analytics_session_visitor
    ON public.analytics_session USING btree (tenant_id, source, visitor_id, started_at);
CREATE INDEX IF NOT EXISTS idx_analytics_session_subject
    ON public.analytics_session USING btree (tenant_id, source, subject_id, started_at)
    WHERE subject_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS public.analytics_daily (
    daily_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    stat_date date NOT NULL,
    pv bigint DEFAULT 0 NOT NULL,
    uv bigint DEFAULT 0 NOT NULL,
    visits bigint DEFAULT 0 NOT NULL,
    events bigint DEFAULT 0 NOT NULL,
    new_visitors bigint DEFAULT 0 NOT NULL,
    returning_visitors bigint DEFAULT 0 NOT NULL,
    bounces bigint DEFAULT 0 NOT NULL,
    duration_seconds bigint DEFAULT 0 NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone,
    CONSTRAINT pk_analytics_daily PRIMARY KEY (daily_id),
    CONSTRAINT uk_analytics_daily UNIQUE (tenant_id, source, stat_date)
);

CREATE TABLE IF NOT EXISTS public.analytics_funnel (
    funnel_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    funnel_code character varying(64) NOT NULL,
    funnel_name character varying(128) NOT NULL,
    steps jsonb DEFAULT '[]'::jsonb NOT NULL,
    window_days integer DEFAULT 7 NOT NULL,
    enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    builtin character(1) DEFAULT '0'::bpchar NOT NULL,
    remark character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_analytics_funnel PRIMARY KEY (funnel_id),
    CONSTRAINT uk_analytics_funnel UNIQUE (tenant_id, source, funnel_code)
);

CREATE INDEX IF NOT EXISTS idx_profile_event_visitor
    ON public.profile_event USING btree (tenant_id, source, anonymous_id, occurred_time)
    WHERE anonymous_id IS NOT NULL;

-- 采集源默认只做分析，不触发画像重算/复核；需要画像时再单独打开
INSERT INTO public.profile_engine_config
    (config_id, tenant_id, source, auto_recalc, review_enabled, review_threshold, debounce_ms, remark, create_by, create_time)
VALUES
    (9820, '000000', 'web', '0', '0', 60, 2000, '分析采集源默认关闭画像重算', 1, '2026-10-19 00:00:00')
ON CONFLICT DO NOTHING;

-- 内置漏斗
INSERT INTO public.analytics_funnel
    (funnel_id, tenant_id, source, funnel_code, funnel_name, steps, window_days, enabled, builtin, remark, create_by, create_time)
VALUES
    (9810, '000000', 'web', 'web_conversion', '访问转化', '[
        {"eventName":"page_view","name":"访问页面"},
        {"eventName":"search","name":"站内搜索"},
        {"eventName":"signup","name":"完成注册"}
     ]'::jsonb, 1, '1', '1', '演示站点：访问 → 搜索 → 注册', 1, '2026-10-19 00:00:00'),
    (9811, '000000', 'dcs', 'task_delivery', '任务交付', '[
        {"eventName":"task.published","name":"任务发布"},
        {"eventName":"data.submitted","name":"数据提交"},
        {"eventName":"data.audit_passed","name":"审核通过"}
     ]'::jsonb, 30, '1', '1', '数据采集：任务发布 → 提交 → 审核通过', 1, '2026-10-19 00:00:00')
ON CONFLICT DO NOTHING;

-- 菜单与权限
INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(11986, '数据分析', 12000, 6, 'profile-analytics', 'dcs/profile/analytics', '', '1', '0', 'C', '0', '0', 'analytics:center:query', 'chart', 103, 1, '2026-10-19 00:00:00', NULL, NULL, '流量/页面/漏斗/留存/访问明细/数据导入'),
(11987, '数据分析编辑', 11986, 14, '', NULL, NULL, '1', '0', 'F', '1', '0', 'analytics:center:edit', '#', 103, 1, '2026-10-19 00:00:00', NULL, NULL, '重建汇总/导入/漏斗维护')
ON CONFLICT DO NOTHING;

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 1, 11986 WHERE NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 1 AND menu_id = 11986);
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 1, 11987 WHERE NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 1 AND menu_id = 11987);
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 6, 11986 WHERE EXISTS (SELECT 1 FROM public.sys_role WHERE role_id = 6)
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 6 AND menu_id = 11986);
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 6, 11987 WHERE EXISTS (SELECT 1 FROM public.sys_role WHERE role_id = 6)
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 6 AND menu_id = 11987);

-- Web 事件 Schema（宽松校验：只约束关键字段）
INSERT INTO public.profile_schema_registry
    (schema_id, tenant_id, source, event_name, schema_version, schema_json, status, create_by, create_time)
VALUES
    (9701, '000000', 'web', 'page_view', '1-0-0', $schema$
    {"type":"object","required":["pageUrl"],"properties":{"pageUrl":{"type":"string"},"title":{"type":"string"}}}
    $schema$::jsonb, 'ACTIVE', 1, '2026-10-19 00:00:00'),
    (9702, '000000', 'web', 'page_leave', '1-0-0', $schema$
    {"type":"object","required":["pageUrl"],"properties":{"pageUrl":{"type":"string"},"durationMs":{"type":"integer"}}}
    $schema$::jsonb, 'ACTIVE', 1, '2026-10-19 00:00:00'),
    (9703, '000000', 'web', 'search', '1-0-0', $schema$
    {"type":"object","required":["keyword"],"properties":{"keyword":{"type":"string"}}}
    $schema$::jsonb, 'ACTIVE', 1, '2026-10-19 00:00:00'),
    (9704, '000000', 'web', 'signup', '1-0-0', $schema$
    {"type":"object","properties":{"plan":{"type":"string"}}}
    $schema$::jsonb, 'ACTIVE', 1, '2026-10-19 00:00:00')
ON CONFLICT DO NOTHING;


-- ================================================================ 批10 (2026-10-20)

-- 事件回放任务(批10 Q3): 天粒度断点(current_day), 回放前后对账数字落 stats_before/after
CREATE TABLE IF NOT EXISTS public.analytics_replay_task (
    replay_id      bigint       NOT NULL,
    tenant_id      varchar(20)  NOT NULL DEFAULT '000000',
    source         varchar(32)  NOT NULL,
    from_day       date         NOT NULL,
    to_day         date         NOT NULL,
    chains         varchar(64)  NOT NULL,
    status         varchar(16)  NOT NULL,
    current_day    date,
    days_total     integer      NOT NULL DEFAULT 0,
    days_done      integer      NOT NULL DEFAULT 0,
    stats_before   varchar(2000),
    stats_after    varchar(2000),
    error_msg      varchar(1000),
    create_dept    bigint,
    create_by      bigint,
    create_time    timestamp    DEFAULT CURRENT_TIMESTAMP,
    update_by      bigint,
    update_time    timestamp,
    del_flag       char(1)      DEFAULT '0',
    CONSTRAINT pk_analytics_replay_task PRIMARY KEY (replay_id),
    CONSTRAINT ck_analytics_replay_status CHECK (status IN ('RUNNING', 'PAUSED', 'DONE', 'FAILED')),
    CONSTRAINT ck_analytics_replay_days CHECK (days_total >= 0 AND days_done >= 0)
);
CREATE INDEX IF NOT EXISTS idx_analytics_replay_tenant_source
    ON public.analytics_replay_task USING btree (tenant_id, source, create_time DESC);

-- 会话/日汇总重建耗时留痕(批10 Q5): 健康页'重建耗时分布/趋势'原料, 保留期任务清 30 天
CREATE TABLE IF NOT EXISTS public.analytics_rebuild_log (
    log_id      bigint      NOT NULL,
    tenant_id   varchar(20) NOT NULL,
    source      varchar(32) NOT NULL,
    kind        varchar(16) NOT NULL,
    duration_ms bigint      NOT NULL,
    sessions    integer,
    finished_at timestamp   NOT NULL,
    read_watermark timestamp,
    CONSTRAINT pk_analytics_rebuild_log PRIMARY KEY (log_id),
    CONSTRAINT ck_analytics_rebuild_kind CHECK (kind IN ('RECENT', 'MANUAL', 'REPLAY', 'UNMERGE', 'DELETION'))
);
CREATE INDEX IF NOT EXISTS idx_analytics_rebuild_log_source
    ON public.analytics_rebuild_log USING btree (tenant_id, source, finished_at DESC);

-- 访客首见物化表(批10 Q7): 键=COALESCE(NULLIF(anonymous_id,''), subject_id), 事件写入时 LEAST 增量维护;
-- 部署时由 migration 从 profile_event 全量回填
CREATE TABLE IF NOT EXISTS public.analytics_visitor_first_seen (
    tenant_id   varchar(20)  NOT NULL,
    source      varchar(32)  NOT NULL,
    visitor_key varchar(128) NOT NULL,
    first_seen  timestamp    NOT NULL,
    CONSTRAINT pk_analytics_visitor_first_seen PRIMARY KEY (tenant_id, source, visitor_key)
);
