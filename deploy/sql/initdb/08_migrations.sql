-- 后置增量：baseline 之后的所有迁移，按文件名日期序合并（原文，未改一字）
-- 基线 01..07 是某次从线上库导出的快照；batch10 之后的表（analytics_rebuild_log /
-- analytics_replay_task / analytics_visitor_first_seen / profile_event_archive 等）只在迁移里，
-- 所以全新安装必须把这一份也跑完，否则代码里的 Mapper 会报表不存在。

-- ===================== 2026-10-19_analytics_center.sql =====================
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

CREATE TABLE IF NOT EXISTS public.analytics_site (
    site_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    site_key character varying(64) NOT NULL,
    site_name character varying(128) NOT NULL,
    allowed_domains character varying(512),
    enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    remark character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_analytics_site PRIMARY KEY (site_id),
    CONSTRAINT uk_analytics_site_key UNIQUE (site_key)
);

CREATE INDEX IF NOT EXISTS idx_profile_event_visitor
    ON public.profile_event USING btree (tenant_id, source, anonymous_id, occurred_time)
    WHERE anonymous_id IS NOT NULL;

-- 采集站点：siteKey 是公开标识，不是密钥；域名白名单用于后台提示与人工排查
INSERT INTO public.analytics_site
    (site_id, tenant_id, source, site_key, site_name, allowed_domains, enabled, remark, create_by, create_time)
VALUES
    (9800, '000000', 'web', 'sd_demo_site', '演示网站', 'localhost,127.0.0.1', '1',
     '内置演示站点，可在采集代码页复制 SDK 片段', 1, '2026-10-19 00:00:00')
ON CONFLICT DO NOTHING;

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
(11986, '数据分析', 9000, 6, 'profile-analytics', 'dcs/profile/analytics', '', '1', '0', 'C', '0', '0', 'analytics:center:query', 'chart', 103, 1, '2026-10-19 00:00:00', NULL, NULL, '流量/页面/漏斗/留存/访问明细/采集'),
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

-- ===================== 2026-10-19_profile_models_segments_review.sql =====================
-- 2026-10-19 画像二期: 通用 scoreModel / RFM / 标签分群 / 低分复核 / 自动重算配置
-- 幂等，可重复执行；不改历史快照。

BEGIN;

CREATE TABLE IF NOT EXISTS public.profile_score_model (
    model_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    model_code character varying(64) NOT NULL,
    model_name character varying(100) NOT NULL,
    model_type character varying(32) DEFAULT 'GENERIC'::character varying NOT NULL,
    model_version character varying(32) DEFAULT 'v1'::character varying NOT NULL,
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    score_max numeric(8,2) DEFAULT 100 NOT NULL,
    enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    builtin character(1) DEFAULT '0'::bpchar NOT NULL,
    remark character varying(500),
    revision integer DEFAULT 0 NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_score_model PRIMARY KEY (model_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_profile_model_code
    ON public.profile_score_model USING btree (tenant_id, source, model_code)
    WHERE (del_flag = '0'::bpchar);

CREATE TABLE IF NOT EXISTS public.profile_model_run (
    run_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    source character varying(32) NOT NULL,
    model_id bigint,
    model_code character varying(64),
    model_version character varying(32),
    status character varying(16) DEFAULT 'RUNNING'::character varying NOT NULL,
    trigger_type character varying(16) DEFAULT 'MANUAL'::character varying NOT NULL,
    total_subjects integer DEFAULT 0 NOT NULL,
    done_subjects integer DEFAULT 0 NOT NULL,
    error_msg text,
    started_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    finished_at timestamp without time zone,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_model_run PRIMARY KEY (run_id)
);
CREATE INDEX IF NOT EXISTS idx_profile_model_run_model
    ON public.profile_model_run USING btree (tenant_id, source, model_code, create_time DESC);

CREATE TABLE IF NOT EXISTS public.profile_model_snapshot (
    model_snapshot_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    source character varying(32) NOT NULL,
    model_id bigint,
    model_code character varying(64) NOT NULL,
    model_version character varying(32) NOT NULL,
    subject_type character varying(32) DEFAULT 'user'::character varying NOT NULL,
    subject_id character varying(64) NOT NULL,
    model_score numeric(8,2) DEFAULT 0 NOT NULL,
    normalized_score numeric(8,2) DEFAULT 0 NOT NULL,
    level character varying(64),
    detail jsonb DEFAULT '{}'::jsonb NOT NULL,
    run_id bigint,
    prev_snapshot_id bigint,
    calc_time timestamp without time zone NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_model_snapshot PRIMARY KEY (model_snapshot_id)
);
CREATE INDEX IF NOT EXISTS idx_profile_model_snap_latest
    ON public.profile_model_snapshot USING btree
    (tenant_id, source, model_code, subject_type, subject_id, calc_time DESC, model_snapshot_id DESC);
CREATE INDEX IF NOT EXISTS idx_profile_model_snap_run
    ON public.profile_model_snapshot USING btree (tenant_id, run_id);
CREATE INDEX IF NOT EXISTS idx_profile_model_snap_level
    ON public.profile_model_snapshot USING btree (tenant_id, source, model_code, level);

CREATE TABLE IF NOT EXISTS public.profile_segment (
    segment_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    segment_code character varying(64) NOT NULL,
    segment_name character varying(100) NOT NULL,
    segment_type character varying(16) DEFAULT 'DYNAMIC'::character varying NOT NULL,
    status character varying(16) DEFAULT 'ENABLED'::character varying NOT NULL,
    rule_config jsonb DEFAULT '{}'::jsonb NOT NULL,
    description character varying(500),
    member_count integer DEFAULT 0 NOT NULL,
    last_run_id bigint,
    last_eval_time timestamp without time zone,
    builtin character(1) DEFAULT '0'::bpchar NOT NULL,
    revision integer DEFAULT 0 NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_segment PRIMARY KEY (segment_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_profile_segment_code
    ON public.profile_segment USING btree (tenant_id, source, segment_code)
    WHERE (del_flag = '0'::bpchar);

CREATE TABLE IF NOT EXISTS public.profile_segment_run (
    run_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    source character varying(32) NOT NULL,
    segment_id bigint NOT NULL,
    trigger_type character varying(16) DEFAULT 'MANUAL'::character varying NOT NULL,
    status character varying(16) DEFAULT 'RUNNING'::character varying NOT NULL,
    total_candidates integer DEFAULT 0 NOT NULL,
    member_count integer DEFAULT 0 NOT NULL,
    error_msg text,
    started_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    finished_at timestamp without time zone,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_segment_run PRIMARY KEY (run_id)
);
CREATE INDEX IF NOT EXISTS idx_profile_segment_run_segment
    ON public.profile_segment_run USING btree (tenant_id, segment_id, create_time DESC);

CREATE TABLE IF NOT EXISTS public.profile_segment_member (
    member_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    source character varying(32) NOT NULL,
    segment_id bigint NOT NULL,
    run_id bigint NOT NULL,
    subject_type character varying(32) DEFAULT 'user'::character varying NOT NULL,
    subject_id character varying(64) NOT NULL,
    display_name character varying(128),
    reason character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_segment_member PRIMARY KEY (member_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_profile_segment_member
    ON public.profile_segment_member USING btree (run_id, subject_type, subject_id)
    WHERE (del_flag = '0'::bpchar);
CREATE INDEX IF NOT EXISTS idx_profile_segment_member_segment
    ON public.profile_segment_member USING btree (tenant_id, segment_id, subject_id);

CREATE TABLE IF NOT EXISTS public.profile_review_queue (
    review_id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    source character varying(32) NOT NULL,
    subject_type character varying(32) DEFAULT 'user'::character varying NOT NULL,
    subject_id character varying(64) NOT NULL,
    display_name character varying(128),
    snapshot_id bigint,
    total_score numeric(6,2),
    risk_level character varying(20) DEFAULT 'HIGH'::character varying NOT NULL,
    trigger_reason character varying(500) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    conclusion character varying(32),
    review_note character varying(500),
    reviewed_by bigint,
    reviewed_at timestamp without time zone,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_review_queue PRIMARY KEY (review_id),
    CONSTRAINT ck_profile_review_status CHECK (
        (status)::text = ANY (ARRAY['PENDING', 'CONFIRMED', 'DISMISSED', 'SUPERSEDED']::text[])
    )
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_profile_review_pending
    ON public.profile_review_queue USING btree (tenant_id, source, subject_type, subject_id)
    WHERE (status = 'PENDING'::character varying AND del_flag = '0'::bpchar);
CREATE INDEX IF NOT EXISTS idx_profile_review_queue
    ON public.profile_review_queue USING btree (tenant_id, source, status, create_time DESC);

CREATE TABLE IF NOT EXISTS public.profile_engine_config (
    config_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    auto_recalc character(1) DEFAULT '1'::bpchar NOT NULL,
    review_enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    review_threshold numeric(6,2) DEFAULT 60 NOT NULL,
    debounce_ms integer DEFAULT 2000 NOT NULL,
    remark character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_engine_config PRIMARY KEY (config_id)
);
CREATE UNIQUE INDEX IF NOT EXISTS uk_profile_engine_config
    ON public.profile_engine_config USING btree (tenant_id, source)
    WHERE (del_flag = '0'::bpchar);

-- 默认 RFM 模型(dcs 演示口径: M 用交付通过量, 通用预设仍用 order.paid/amount)
INSERT INTO public.profile_score_model
    (model_id, tenant_id, source, model_code, model_name, model_type, model_version, config,
     score_max, enabled, builtin, remark, revision, create_by, create_time)
VALUES
    (9700, '000000', 'dcs', 'rfm-default', 'RFM 分层', 'RFM', 'v1',
     '{"preset":"RFM","dimensions":[
        {"code":"R","name":"最近活跃","weight":1,"calc":{"type":"RECENCY_DAYS","eventTypes":[]},
         "bins":{"method":"percentile","direction":"lower","quantiles":[0.2,0.4,0.6,0.8],"scores":[1,2,3,4,5],"missingScore":1}},
        {"code":"F","name":"互动频次","weight":1,"calc":{"type":"COUNT","eventTypes":[]},
         "bins":{"method":"percentile","direction":"higher","quantiles":[0.2,0.4,0.6,0.8],"scores":[1,2,3,4,5],"missingScore":1}},
        {"code":"M","name":"交付贡献","weight":1,"calc":{"type":"COUNT","eventTypes":["data.audit_passed"]},
         "bins":{"method":"percentile","direction":"higher","quantiles":[0.2,0.4,0.6,0.8],"scores":[1,2,3,4,5],"missingScore":1}}
     ]}'::jsonb,
     100, '1', '1', '内置 RFM 演示模型, 可在工作室复制修改', 0, 1, CURRENT_TIMESTAMP)
ON CONFLICT (model_id) DO NOTHING;

-- 引擎默认配置: 事件后自动重算 + 低于 60 分入复核队列
INSERT INTO public.profile_engine_config
    (config_id, tenant_id, source, auto_recalc, review_enabled, review_threshold, debounce_ms,
     remark, create_by, create_time)
VALUES
    (9701, '000000', 'dcs', '1', '1', 60, 2000, '默认: 自动重算 + 低分复核', 1, CURRENT_TIMESTAMP)
ON CONFLICT (config_id) DO NOTHING;

-- 示例动态分群
INSERT INTO public.profile_segment
    (segment_id, tenant_id, source, segment_code, segment_name, segment_type, status,
     rule_config, description, member_count, builtin, revision, create_by, create_time)
VALUES
    (9710, '000000', 'dcs', 'high_value_active', '高价值活跃', 'DYNAMIC', 'ENABLED',
     '{"match":"ALL","conditions":[{"field":"totalScore","op":"GTE","value":75}]}'::jsonb,
     '总分不低于 75 的主体', 0, '1', 0, 1, CURRENT_TIMESTAMP),
    (9711, '000000', 'dcs', 'low_score_watch', '低分待关注', 'DYNAMIC', 'ENABLED',
     '{"match":"ALL","conditions":[{"field":"totalScore","op":"LT","value":60}]}'::jsonb,
     '总分低于 60、需要复核的主体', 0, '1', 0, 1, CURRENT_TIMESTAMP)
ON CONFLICT (segment_id) DO NOTHING;

-- 菜单与按钮权限
INSERT INTO public.sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,
     menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (11980, '画像 RFM', 9000, 3, 'profile-rfm', 'dcs/profile/rfm', '', '1', '0', 'C', '0', '0', 'profile:model:query', 'chart', 103, 1, '2026-10-19 00:00:00', NULL, NULL, 'RFM/通用评分模型'),
    (11981, '画像分群', 9000, 4, 'profile-segments', 'dcs/profile/segments', '', '1', '0', 'C', '0', '0', 'profile:segment:query', 'peoples', 103, 1, '2026-10-19 00:00:00', NULL, NULL, '动态/静态标签分群'),
    (11982, '低分复核', 9000, 5, 'profile-review', 'dcs/profile/review', '', '1', '0', 'C', '0', '0', 'profile:review:query', 'validCode', 103, 1, '2026-10-19 00:00:00', NULL, NULL, '低分主体复核队列'),
    (11983, '画像模型编辑', 9070, 11, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:model:edit', '#', 103, 1, '2026-10-19 00:00:00', NULL, NULL, '新建/编辑/运行评分模型'),
    (11984, '画像分群编辑', 9070, 12, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:segment:edit', '#', 103, 1, '2026-10-19 00:00:00', NULL, NULL, '新建/编辑/评估/导入分群'),
    (11985, '低分复核处理', 9070, 13, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:review:edit', '#', 103, 1, '2026-10-19 00:00:00', NULL, NULL, '确认/驳回低分复核')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO public.sys_role_menu (role_id, menu_id) VALUES
    (1, 11980), (1, 11981), (1, 11982), (1, 11983), (1, 11984), (1, 11985),
    (6, 11980), (6, 11981), (6, 11982), (6, 11983), (6, 11984), (6, 11985)
ON CONFLICT DO NOTHING;

COMMIT;

-- ===================== 2026-10-19_profile_protocol_v1.sql =====================
-- 2026-10-19 profile.protocol/v1 升级脚本（已有库执行；空库直接跑 baseline 即可）
-- 所有语句幂等，可重复执行。

BEGIN;

-- 1. 事件信封扩展
ALTER TABLE public.profile_event ADD COLUMN IF NOT EXISTS event_name character varying(64);
ALTER TABLE public.profile_event ADD COLUMN IF NOT EXISTS anonymous_id character varying(128);
ALTER TABLE public.profile_event ADD COLUMN IF NOT EXISTS session_id character varying(128);
ALTER TABLE public.profile_event ADD COLUMN IF NOT EXISTS schema_version character varying(32) DEFAULT '1-0-0'::character varying NOT NULL;
ALTER TABLE public.profile_event ADD COLUMN IF NOT EXISTS context jsonb DEFAULT '{}'::jsonb NOT NULL;
ALTER TABLE public.profile_event ADD COLUMN IF NOT EXISTS traits jsonb DEFAULT '{}'::jsonb NOT NULL;
ALTER TABLE public.profile_event ADD COLUMN IF NOT EXISTS identifiers jsonb DEFAULT '{}'::jsonb NOT NULL;
UPDATE public.profile_event SET event_name = event_type WHERE event_name IS NULL;

CREATE INDEX IF NOT EXISTS idx_profile_event_name
    ON public.profile_event USING btree (tenant_id, source, event_name, occurred_time DESC);
CREATE INDEX IF NOT EXISTS idx_profile_event_session
    ON public.profile_event USING btree (tenant_id, source, session_id, occurred_time DESC)
    WHERE (session_id IS NOT NULL);

-- 2. 主体属性
ALTER TABLE public.profile_subject ADD COLUMN IF NOT EXISTS properties jsonb DEFAULT '{}'::jsonb NOT NULL;

-- 3. 接入协议
CREATE TABLE IF NOT EXISTS public.profile_protocol (
    protocol_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    version character varying(32) NOT NULL,
    status character varying(16) DEFAULT 'PUBLISHED'::character varying NOT NULL,
    manifest jsonb NOT NULL,
    checksum character varying(64) NOT NULL,
    published_at timestamp without time zone,
    published_by bigint,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_protocol PRIMARY KEY (protocol_id),
    CONSTRAINT uk_profile_protocol_version UNIQUE (tenant_id, source, version)
);
CREATE INDEX IF NOT EXISTS idx_profile_protocol_current
    ON public.profile_protocol USING btree (tenant_id, source, status);
CREATE UNIQUE INDEX IF NOT EXISTS uk_profile_protocol_published
    ON public.profile_protocol USING btree (tenant_id, source)
    WHERE (status = 'PUBLISHED' AND del_flag = '0');

-- 4. 事件 schema 注册表
CREATE TABLE IF NOT EXISTS public.profile_schema_registry (
    schema_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    event_name character varying(64) NOT NULL,
    schema_version character varying(32) DEFAULT '1-0-0'::character varying NOT NULL,
    schema_json jsonb NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_schema_registry PRIMARY KEY (schema_id),
    CONSTRAINT uk_profile_schema UNIQUE (tenant_id, source, event_name, schema_version)
);
CREATE INDEX IF NOT EXISTS idx_profile_schema_source
    ON public.profile_schema_registry USING btree (tenant_id, source, event_name);

-- 5. 身份链接与合并审计
CREATE TABLE IF NOT EXISTS public.profile_identity_link (
    link_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    subject_type character varying(32) DEFAULT 'user'::character varying NOT NULL,
    subject_id character varying(64) NOT NULL,
    identifier_type character varying(32) NOT NULL,
    identifier_value character varying(128) NOT NULL,
    identifier_hash character varying(128),
    confidence numeric(5,4) DEFAULT 1.0 NOT NULL,
    is_secure character(1) DEFAULT '0'::bpchar NOT NULL,
    status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    first_seen timestamp without time zone,
    last_seen timestamp without time zone,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    CONSTRAINT pk_profile_identity_link PRIMARY KEY (link_id),
    CONSTRAINT uk_profile_identity_link UNIQUE (tenant_id, source, identifier_type, identifier_value),
    CONSTRAINT ck_profile_identity_confidence CHECK (confidence >= 0 AND confidence <= 1)
);
CREATE INDEX IF NOT EXISTS idx_profile_identity_link_subject
    ON public.profile_identity_link USING btree (tenant_id, source, subject_type, subject_id);

CREATE TABLE IF NOT EXISTS public.profile_identity_merge (
    merge_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    subject_type character varying(32) DEFAULT 'user'::character varying NOT NULL,
    from_subject_id character varying(64) NOT NULL,
    to_subject_id character varying(64) NOT NULL,
    reason character varying(32) NOT NULL,
    rule_version character varying(32) NOT NULL,
    actor character varying(64),
    occurred_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    reversible_until timestamp without time zone,
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    CONSTRAINT pk_profile_identity_merge PRIMARY KEY (merge_id),
    CONSTRAINT ck_profile_identity_merge_diff CHECK (from_subject_id <> to_subject_id)
);
CREATE INDEX IF NOT EXISTS idx_profile_identity_merge_from
    ON public.profile_identity_merge USING btree (tenant_id, source, subject_type, from_subject_id);

-- 6. 权限菜单（隐藏功能项，挂在“用户画像”目录下）
INSERT INTO public.sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,
     menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (11973, '画像协议查询', 9070, 5, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:protocol:query', '#', 103, 1, '2026-10-19 00:00:00', NULL, NULL, '查看/导出画像接入协议'),
    (11974, '画像协议编辑', 9070, 6, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:protocol:edit', '#', 103, 1, '2026-10-19 00:00:00', NULL, NULL, '校验/发布画像接入协议'),
    (11975, '事件Schema查询', 9070, 7, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:schema:query', '#', 103, 1, '2026-10-19 00:00:00', NULL, NULL, '查看事件schema'),
    (11976, '事件Schema编辑', 9070, 8, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:schema:edit', '#', 103, 1, '2026-10-19 00:00:00', NULL, NULL, '注册/更新事件schema')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO public.sys_role_menu (role_id, menu_id) VALUES
    (1, 11973), (1, 11974), (1, 11975), (1, 11976),
    (6, 11973), (6, 11974), (6, 11975), (6, 11976)
ON CONFLICT DO NOTHING;

COMMIT;

-- ===================== 2026-10-19_profile_studio.sql =====================
-- 2026-10-19 画像工作室（维度/权重规则治理）升级脚本
-- 幂等，可重复执行；只新增列/索引/菜单，不改历史快照。

BEGIN;

-- 1. 评分规则版本化字段
ALTER TABLE public.profile_score_rule ADD COLUMN IF NOT EXISTS rule_name character varying(100);
ALTER TABLE public.profile_score_rule ADD COLUMN IF NOT EXISTS status character varying(16) DEFAULT 'PUBLISHED'::character varying NOT NULL;
ALTER TABLE public.profile_score_rule ADD COLUMN IF NOT EXISTS remark character varying(500);
ALTER TABLE public.profile_score_rule ADD COLUMN IF NOT EXISTS published_at timestamp without time zone;
ALTER TABLE public.profile_score_rule ADD COLUMN IF NOT EXISTS published_by bigint;
ALTER TABLE public.profile_score_rule ADD COLUMN IF NOT EXISTS base_rule_version character varying(32);
ALTER TABLE public.profile_score_rule ADD COLUMN IF NOT EXISTS revision integer DEFAULT 0 NOT NULL;

UPDATE public.profile_score_rule SET rule_name = rule_version WHERE rule_name IS NULL;
UPDATE public.profile_score_rule SET status = CASE WHEN enabled = '1' THEN 'PUBLISHED' ELSE 'ARCHIVED' END
WHERE status IS NULL OR status = '';

-- 同源多生效行时只保留最新一条为 PUBLISHED，避免部分唯一索引创建失败
UPDATE public.profile_score_rule r
SET status = 'ARCHIVED', enabled = '0', update_time = CURRENT_TIMESTAMP
WHERE r.status = 'PUBLISHED' AND r.del_flag = '0'
  AND r.rule_id NOT IN (
      SELECT DISTINCT ON (tenant_id, source) rule_id
      FROM public.profile_score_rule
      WHERE status = 'PUBLISHED' AND del_flag = '0'
      ORDER BY tenant_id, source, create_time DESC NULLS LAST, rule_id DESC
  );

-- 旧数据没有发布时间: 用创建时间回填, 避免管理面显示空
UPDATE public.profile_score_rule SET published_at = create_time
WHERE status = 'PUBLISHED' AND published_at IS NULL;

-- 2. 查询索引
CREATE INDEX IF NOT EXISTS idx_profile_snap_latest
    ON public.profile_score_snapshot USING btree (tenant_id, source, subject_type, subject_id, calc_time DESC, snapshot_id DESC);
CREATE INDEX IF NOT EXISTS idx_profile_snap_rank
    ON public.profile_score_snapshot USING btree (tenant_id, source, total_score DESC);
CREATE INDEX IF NOT EXISTS idx_profile_rule_source
    ON public.profile_score_rule USING btree (tenant_id, source, status, create_time DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uk_profile_rule_published
    ON public.profile_score_rule USING btree (tenant_id, source)
    WHERE (status = 'PUBLISHED' AND del_flag = '0');
CREATE UNIQUE INDEX IF NOT EXISTS uk_profile_rule_draft
    ON public.profile_score_rule USING btree (tenant_id, source)
    WHERE (status = 'DRAFT' AND del_flag = '0');

-- 3. 权限菜单
INSERT INTO public.sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,
     menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (11977, '画像维度编辑', 9070, 9, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:dimension:edit', '#', 103, 1, '2026-10-19 00:00:00', NULL, NULL, '新增/修改/停用评分维度'),
    (11978, '画像规则编辑', 9070, 10, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:rule:edit', '#', 103, 1, '2026-10-19 00:00:00', NULL, NULL, '创建/编辑/发布/归档评分规则'),
    (11979, '画像工作室', 9000, 2, 'profile-studio', 'dcs/profile/studio', '', '1', '0', 'C', '0', '0', 'profile:score:query', 'edit', 103, 1, '2026-10-19 00:00:00', NULL, NULL, '维度/权重/覆盖度治理')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO public.sys_role_menu (role_id, menu_id) VALUES
    (1, 11977), (1, 11978), (1, 11979),
    (6, 11977), (6, 11978), (6, 11979)
ON CONFLICT DO NOTHING;

COMMIT;

-- ===================== 2026-10-20_assistant_9req_quickfix.sql =====================
-- 2026-10-20 AI 助手九题快刀批（拍板页 20261020 全按推荐）
-- Q6 助手称呼配置列 + Q9 MiniMax 国内站预置种子
-- 幂等，可重复执行；与 baseline 同源双写（01_schema.sql / 02_reference.sql）。

BEGIN;

-- Q6：助手对外称呼（空=平台默认「SmartDock 小助手」；管理台可改，读侧两级默认合并）
ALTER TABLE public.dcs_assistant_config
    ADD COLUMN IF NOT EXISTS assistant_name character varying(50);

-- Q9：MiniMax 预置切国内站——文档域名白名单补 api.minimax.cn 与 platform.minimax.cn
-- （枚举 baseUrl 已改 https://api.minimax.cn/v1，官方文档现行端点；与 baseline/02_reference 同值）
INSERT INTO public.dcs_ai_doc_domain (domain_id, tenant_id, domain, provider, remark, enabled, create_dept, create_by, create_time, update_by, update_time, del_flag) VALUES
    (2110200000000000001, '000000', 'api.minimax.cn', 'MINIMAX', 'MiniMax 国内站 API（快刀批 Q9）', '1', 103, 1, '2026-10-20 12:00:00', NULL, NULL, '0'),
    (2110200000000000002, '000000', 'platform.minimax.cn', 'MINIMAX', 'MiniMax 国内站开放平台（快刀批 Q9）', '1', 103, 1, '2026-10-20 12:00:00', NULL, NULL, '0')
ON CONFLICT (domain_id) DO NOTHING;

-- Q9：MiniMax-M3.1-Flash 人民币价格种子（默认模型升级的计费配套；CNY 计价是硬约束，
-- 计费链 CNY 闸会过滤非 CNY 行——官方价 ¥1 / ¥0.1（缓存）/ ¥4 每百万 token；
-- 与 baseline/02_reference 同值）
INSERT INTO public.dcs_ai_model_price (price_id, tenant_id, provider, model_name, currency, input_price_per_million, cached_input_price_per_million, output_price_per_million, source_url, enabled, remark, create_dept, create_by, create_time, update_by, update_time, del_flag, pricing_region, pricing_tier_code, input_token_min, input_token_max, billing_formula, pricing_schema_json, source_updated_at) VALUES
    (900000000000003105, '000000', 'MINIMAX', 'MiniMax-M3.1-Flash', 'CNY', 1.000000, 0.100000, 4.000000, 'https://platform.minimax.cn/docs/guides/pricing', '1', 'MiniMax 国内站定价种子（快刀批 Q9）；人民币计价是硬约束——计费链 CNY 闸会过滤非 CNY 行', NULL, NULL, '2026-10-20 12:00:00', NULL, NULL, '0', NULL, 'DEFAULT', NULL, NULL, 'INPUT_OUTPUT_PER_MILLION', NULL, NULL)
ON CONFLICT (price_id) DO NOTHING;

COMMIT;

-- ===================== 2026-10-20_batch10_lifecycle.sql =====================
-- 2026-10-20 批10 多实例与数据生命周期
--
-- 覆盖: 回放任务表 / 重建留痕表 / 访客首见物化表(含回填) / 事件归档表 /
--       引擎配置加按源保留期两列 / 身份合并加拆分审计两列 / 模型配置存档加 model_type /
--       菜单(数据运维 C + 引擎健康 C + F 按钮)。
-- 幂等: 全部 IF NOT EXISTS / ON CONFLICT / WHERE NOT EXISTS, 可重复执行。
-- 分区明示不上(拍板 Q8-A/R3): 触发条件=事件总量过亿或单表过千万再立专项批。
BEGIN;

-- ---------------------------------------------------------------- 回放任务(批10 Q3)
CREATE TABLE IF NOT EXISTS analytics_replay_task (
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
    ON analytics_replay_task (tenant_id, source, create_time DESC);

-- ---------------------------------------------------------------- 重建留痕(批10 Q5)
CREATE TABLE IF NOT EXISTS analytics_rebuild_log (
    log_id      bigint      NOT NULL,
    tenant_id   varchar(20) NOT NULL,
    source      varchar(32) NOT NULL,
    kind        varchar(16) NOT NULL,
    duration_ms bigint      NOT NULL,
    sessions    integer,
    finished_at timestamp   NOT NULL,
    CONSTRAINT pk_analytics_rebuild_log PRIMARY KEY (log_id),
    CONSTRAINT ck_analytics_rebuild_kind CHECK (kind IN ('RECENT', 'MANUAL', 'REPLAY', 'UNMERGE'))
);
CREATE INDEX IF NOT EXISTS idx_analytics_rebuild_log_source
    ON analytics_rebuild_log (tenant_id, source, finished_at DESC);

-- ---------------------------------------------------------------- 访客首见物化(批10 Q7)
CREATE TABLE IF NOT EXISTS analytics_visitor_first_seen (
    tenant_id  varchar(20) NOT NULL,
    source     varchar(32) NOT NULL,
    visitor_key varchar(128) NOT NULL,
    first_seen timestamp   NOT NULL,
    CONSTRAINT pk_analytics_visitor_first_seen PRIMARY KEY (tenant_id, source, visitor_key)
);
-- 回填: 键口径与原全表聚合完全一致(COALESCE(NULLIF(anonymous_id,''), subject_id))
INSERT INTO analytics_visitor_first_seen (tenant_id, source, visitor_key, first_seen)
SELECT tenant_id, source, COALESCE(NULLIF(anonymous_id, ''), subject_id), min(occurred_time)
FROM profile_event
WHERE del_flag = '0'
GROUP BY 1, 2, 3
ON CONFLICT (tenant_id, source, visitor_key)
DO UPDATE SET first_seen = LEAST(analytics_visitor_first_seen.first_seen, EXCLUDED.first_seen);

-- ---------------------------------------------------------------- 事件归档表(批10 Q4)
-- 结构同 profile_event(不复制唯一约束: 归档只进不出, 由迁移 CTE 保证一行只归档一次)
CREATE TABLE IF NOT EXISTS profile_event_archive (LIKE profile_event INCLUDING DEFAULTS);
DO $$ BEGIN
    ALTER TABLE profile_event_archive ADD CONSTRAINT pk_profile_event_archive PRIMARY KEY (event_id);
EXCEPTION
    WHEN duplicate_object THEN NULL;
    -- 基线已建同表时 PG17 先抛 42P16 multiple primary keys 而非 42710, 同样视为已就绪
    WHEN invalid_table_definition THEN NULL;
END $$;
CREATE INDEX IF NOT EXISTS idx_profile_event_archive_source
    ON profile_event_archive (tenant_id, source, occurred_time DESC);

-- ---------------------------------------------------------------- 引擎配置: 按源保留期(批10 Q4)
ALTER TABLE profile_engine_config ADD COLUMN IF NOT EXISTS event_retention_days integer;
ALTER TABLE profile_engine_config ADD COLUMN IF NOT EXISTS event_archive char(1) DEFAULT '1' NOT NULL;
DO $$ BEGIN
    ALTER TABLE profile_engine_config ADD CONSTRAINT ck_profile_engine_event_retention
        CHECK (event_retention_days IS NULL OR event_retention_days BETWEEN 1 AND 36500);
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
DO $$ BEGIN
    ALTER TABLE profile_engine_config ADD CONSTRAINT ck_profile_engine_event_archive
        CHECK (event_archive IN ('0', '1'));
EXCEPTION WHEN duplicate_object THEN NULL; END $$;

-- ---------------------------------------------------------------- 身份合并: 拆分审计(批10 Q6)
ALTER TABLE profile_identity_merge ADD COLUMN IF NOT EXISTS reversed_at timestamp;
ALTER TABLE profile_identity_merge ADD COLUMN IF NOT EXISTS reversed_by varchar(64);

-- ---------------------------------------------------------------- 模型配置存档: model_type(批10 R5)
ALTER TABLE profile_model_config_snapshot ADD COLUMN IF NOT EXISTS model_type varchar(20);
-- 存量回填: 以模型当前类型为准(历史存档无更准的来源)
UPDATE profile_model_config_snapshot s
SET model_type = m.model_type
FROM profile_score_model m
WHERE s.model_id = m.model_id AND s.model_type IS NULL;

-- ---------------------------------------------------------------- 菜单(批10 C.1)
-- 数据运维(C): 回放任务 + 保留期配置 + 身份合并记录
INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 12001, '数据运维', 12000, 7, 'profile-ops', 'dcs/profile/ops', '', '1', '0', 'C', '0', '0', 'profile:replay:query', 'time-range', 103, 1, '2026-10-20 00:00:00', NULL, NULL, '事件回放/数据保留期/身份合并与拆分'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 12001);

INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 12002, '引擎健康', 12000, 8, 'profile-health', 'dcs/profile/health', '', '1', '0', 'C', '0', '0', 'profile:health:query', 'monitor', 103, 1, '2026-10-20 00:00:00', NULL, NULL, '四指标/越线告警/重建耗时趋势'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 12002);

INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 12003, '回放操作', 12001, 1, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:replay:edit', '#', 103, 1, '2026-10-20 00:00:00', NULL, NULL, '发起回放/断点续跑/暂停'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 12003);

INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 12004, '身份合并查询', 12001, 2, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:identity:query', '#', 103, 1, '2026-10-20 00:00:00', NULL, NULL, '合并记录列表'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 12004);

INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 12005, '身份拆分', 12001, 3, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:identity:edit', '#', 103, 1, '2026-10-20 00:00:00', NULL, NULL, '误合并拆分(不可逆,二次确认)'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 12005);

-- 数据运维页保留期保存按钮: 接口是复核配置编辑(profile:review:edit), 按钮权限需挂到本页菜单树下,
-- 否则只授"数据运维"的角色看不到保存按钮
INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 12006, '保留期配置', 12001, 4, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:review:edit', '#', 103, 1, '2026-10-20 00:00:00', NULL, NULL, '事件保留期保存'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 12006);

-- ---------------------------------------------------------------- 重建读取水位(批10 P1-B 回修)
-- 双实例去重比较"读取水位"而非完成时刻: 重建执行期间写入的事件不会被误判为已覆盖
ALTER TABLE analytics_rebuild_log ADD COLUMN IF NOT EXISTS read_watermark timestamp;

COMMIT;

-- ===================== 2026-10-20_batch11_compliance.sql =====================
-- 2026-10-20 批11 安全合规：PII 脱敏清单 / 主体删除向导 / 导出审计 / 采集签名
--
-- 拍板 batch11-decision-board 全 A：
--   Q1 PII 表驱动脱敏（profile_pii_field + 三权限串 profile:pii:score/contact/behavior）
--   Q2 主体级删除向导（profile_deletion_log 审计，重算 kind=DELETION）
--   Q3 采集签名（analytics_site.sign_secret/sign_enabled，HMAC ±10 分钟 + nonce 防重放）
--   Q5 导出审计（profile_export_log + 管理员站内通知 + CSV 公式注入转义）
--
-- 幂等：IF NOT EXISTS / ON CONFLICT / 约束先 DROP IF EXISTS，可重复执行。
BEGIN;

-- ------------------------------------------------------------ 1. PII 字段清单（全局表，键名为主键）

CREATE TABLE IF NOT EXISTS public.profile_pii_field (
    field_key character varying(64) NOT NULL,
    mask_rule character varying(16) NOT NULL,
    remark character varying(200),
    enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    CONSTRAINT pk_profile_pii_field PRIMARY KEY (field_key),
    CONSTRAINT ck_profile_pii_field_rule CHECK (mask_rule IN ('MOBILE', 'EMAIL', 'IP', 'TERM', 'EDGE', 'FULL')),
    CONSTRAINT ck_profile_pii_field_enabled CHECK (enabled IN ('0', '1'))
);

COMMENT ON TABLE public.profile_pii_field IS 'PII 字段清单：键名→打码规则，出参 map 命中即打码（批11 Q1）';

INSERT INTO public.profile_pii_field (field_key, mask_rule, remark, enabled) VALUES
  ('phone',    'MOBILE', '手机号：保留前3后4', '1'),
  ('mobile',   'MOBILE', '手机号别名键', '1'),
  ('email',    'EMAIL',  '邮箱：保留首字符与域名', '1'),
  ('ip',       'IP',     'IP 地址：末两段打码', '1'),
  ('search_term', 'TERM', '搜索词：整段打码', '1'),
  ('keyword',  'TERM',   '搜索词别名键', '1'),
  ('address',  'EDGE',   '地址：保留首尾各一字符', '1'),
  ('name',     'EDGE',   '姓名：保留首尾各一字符', '1'),
  ('id_card',  'FULL',   '证件号：整串打码', '1'),
  ('idcard',   'FULL',   '证件号别名键', '1')
ON CONFLICT (field_key) DO NOTHING;

-- ------------------------------------------------------------ 2. 删除审计

CREATE TABLE IF NOT EXISTS public.profile_deletion_log (
    log_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    subject_type character varying(32) NOT NULL,
    subject_id character varying(64) NOT NULL,
    preview text,
    status character varying(16) NOT NULL,
    events_deleted bigint DEFAULT 0,
    archives_deleted bigint DEFAULT 0,
    sessions_deleted bigint DEFAULT 0,
    links_deleted bigint DEFAULT 0,
    snapshots_deleted bigint DEFAULT 0,
    members_deleted bigint DEFAULT 0,
    days_rebuilt integer DEFAULT 0,
    error_msg character varying(1000),
    executed_at timestamp without time zone,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_profile_deletion_log PRIMARY KEY (log_id),
    CONSTRAINT ck_profile_deletion_status CHECK (status IN ('EXECUTED', 'FAILED'))
);

COMMENT ON TABLE public.profile_deletion_log IS '主体级删除向导审计（批11 Q2）：预览快照+实际清理行数+重算天数';

CREATE INDEX IF NOT EXISTS idx_profile_deletion_time
    ON public.profile_deletion_log (tenant_id, create_time DESC);

-- ------------------------------------------------------------ 3. 导出审计

CREATE TABLE IF NOT EXISTS public.profile_export_log (
    log_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    segment_id bigint NOT NULL,
    segment_name character varying(128),
    row_count bigint NOT NULL DEFAULT 0,
    masked character(1) DEFAULT '1'::bpchar NOT NULL,
    file_hash character varying(16),
    reach_id bigint,
    export_by bigint,
    export_at timestamp without time zone,
    CONSTRAINT pk_profile_export_log PRIMARY KEY (log_id),
    CONSTRAINT ck_profile_export_masked CHECK (masked IN ('0', '1'))
);

COMMENT ON TABLE public.profile_export_log IS '分群成员 CSV 导出审计（批11 Q5）：行数/是否明文/文件哈希';

CREATE INDEX IF NOT EXISTS idx_profile_export_time
    ON public.profile_export_log (tenant_id, export_at DESC);

-- ------------------------------------------------------------ 4. 采集签名（站点级开关，默认关）

ALTER TABLE public.analytics_site ADD COLUMN IF NOT EXISTS sign_secret character varying(128);
ALTER TABLE public.analytics_site ADD COLUMN IF NOT EXISTS sign_enabled character(1) DEFAULT '0'::bpchar NOT NULL;

ALTER TABLE public.analytics_site DROP CONSTRAINT IF EXISTS ck_analytics_site_sign_enabled;
ALTER TABLE public.analytics_site
    ADD CONSTRAINT ck_analytics_site_sign_enabled CHECK (sign_enabled IN ('0', '1'));

-- ------------------------------------------------------------ 5. 重建类型扩 DELETION（删除向导派生重算）

ALTER TABLE public.analytics_rebuild_log DROP CONSTRAINT IF EXISTS ck_analytics_rebuild_kind;
ALTER TABLE public.analytics_rebuild_log
    ADD CONSTRAINT ck_analytics_rebuild_kind CHECK (kind IN ('RECENT', 'MANUAL', 'REPLAY', 'UNMERGE', 'DELETION'));

-- ------------------------------------------------------------ 6. 菜单（合规中心一页三块 + 列级权限按钮）
-- ID 段 12010-12017（11970-11991 已占用，12000=画像与分析目录）

INSERT INTO public.sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES
  (12010, '合规中心', 12000, 7, 'compliance', 'dcs/profile/compliance', NULL, '1', '0',
   'C', '0', '0', 'profile:deletion:query', 'lock', 103, 1, '2026-10-20 00:00:00',
   '批11：PII 字段清单 / 主体删除向导 / 导出审计'),
  (12011, '删除-执行', 12010, 1, '', NULL, NULL, '1', '0',
   'F', '0', '0', 'profile:deletion:edit', '#', 103, 1, '2026-10-20 00:00:00', ''),
  (12012, '导出-审计查看', 12010, 2, '', NULL, NULL, '1', '0',
   'F', '0', '0', 'profile:export:query', '#', 103, 1, '2026-10-20 00:00:00', ''),
  (12013, 'PII-清单查看', 12010, 3, '', NULL, NULL, '1', '0',
   'F', '0', '0', 'profile:pii:query', '#', 103, 1, '2026-10-20 00:00:00', ''),
  (12014, 'PII-清单编辑', 12010, 4, '', NULL, NULL, '1', '0',
   'F', '0', '0', 'profile:pii:edit', '#', 103, 1, '2026-10-20 00:00:00', ''),
  (12015, '画像-看分数', 9070, 1, '', NULL, NULL, '1', '0',
   'F', '0', '0', 'profile:pii:score', '#', 103, 1, '2026-10-20 00:00:00', '列级：主体分数/维度'),
  (12016, '画像-看联系方式', 9070, 2, '', NULL, NULL, '1', '0',
   'F', '0', '0', 'profile:pii:contact', '#', 103, 1, '2026-10-20 00:00:00', '列级：电话/邮箱等原文'),
  (12017, '画像-看行为明细', 9070, 3, '', NULL, NULL, '1', '0',
   'F', '0', '0', 'profile:pii:behavior', '#', 103, 1, '2026-10-20 00:00:00', '列级：会话时间线/上下文')
ON CONFLICT (menu_id) DO UPDATE SET
  menu_name = EXCLUDED.menu_name,
  parent_id = EXCLUDED.parent_id,
  order_num = EXCLUDED.order_num,
  path = EXCLUDED.path,
  component = EXCLUDED.component,
  menu_type = EXCLUDED.menu_type,
  visible = '0',
  status = '0',
  perms = EXCLUDED.perms,
  icon = EXCLUDED.icon,
  remark = EXCLUDED.remark;

-- 合规中心页面默认只授超管角色（role_id=1）；列级三权限不授普通角色 → 无权限者看打码视图
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 1, m.menu_id FROM public.sys_menu m
WHERE m.menu_id IN (12010, 12011, 12012, 12013, 12014, 12015, 12016, 12017)
ON CONFLICT DO NOTHING;

COMMIT;

-- ===================== 2026-10-20_collect_quality.sql =====================
-- =====================================================================
-- 批7 采集质量：丢弃留痕表（robot/domain_mismatch/oversize/duplicate/schema_invalid）
-- 可重复执行（IF NOT EXISTS）。同步合入 baseline/07_analytics.sql。
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.analytics_collect_log (
    log_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    site_key character varying(64) NOT NULL,
    drop_reason character varying(32) NOT NULL,
    event_count integer DEFAULT 1 NOT NULL,
    ip_hash character varying(64),
    user_agent character varying(512),
    origin_host character varying(255),
    detail character varying(500),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_analytics_collect_log PRIMARY KEY (log_id)
);

CREATE INDEX IF NOT EXISTS idx_analytics_collect_log_time
    ON public.analytics_collect_log USING btree (tenant_id, source, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_analytics_collect_log_reason
    ON public.analytics_collect_log USING btree (tenant_id, source, drop_reason);

-- ===================== 2026-10-20_menu_fixes.sql =====================
-- 二期批7前置快赢: 菜单结构修正
-- 1) 11987(数据分析编辑) 原挂在 9070(用户画像) 下, 只勾数据分析菜单的角色拿不到编辑权限 → 挂到 11986(数据分析)
-- 2) 9070 remark 提到不存在的 "AI诊断" 能力 → 移除
UPDATE public.sys_menu SET parent_id = 11986 WHERE menu_id = 11987 AND parent_id = 9070;
UPDATE public.sys_menu SET remark = '用户画像查询(综合分/4维/排行)' WHERE menu_id = 9070 AND remark LIKE '%AI诊断%';

-- ===================== 2026-10-20_menu_ia_profile_group.sql =====================
-- 2026-10-20 菜单 IA：画像与分析独立成顶级组（双引擎定位）
--
-- 背景：二期画像能力的 6 个页面此前平铺在「任务中心」下，与任务流混装且
-- order_num 撞车（两个 1、两个 3、两个 4、三个 6）。本迁移把画像域迁入
-- 新的顶级目录「画像与分析」(12000, path=insight)，任务中心收窄为纯任务流；
-- 同时删除隐藏的 RuoYi「PLUS 官网」框架外链。
--
-- 幂等：全部使用 ON CONFLICT / 条件更新，可重复执行。
-- ID 段：12000 起（11970-11987 已被画像/分析菜单占用，11990+ 留二期窗口）。
BEGIN;

INSERT INTO public.sys_menu
  (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache,
   menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
  (12000, '画像与分析', 0, 79, 'insight', NULL, NULL, '1', '0',
   'M', '0', '0', '', 'peoples', 103, 1, '2026-10-20 00:00:00', NULL, NULL,
   '双引擎之一：画像/分群/复核/分析中心')
ON CONFLICT (menu_id) DO UPDATE SET
  menu_name = EXCLUDED.menu_name,
  parent_id = EXCLUDED.parent_id,
  order_num = EXCLUDED.order_num,
  path = EXCLUDED.path,
  icon = EXCLUDED.icon,
  visible = '0',
  status = '0',
  remark = EXCLUDED.remark;

-- 画像 6 页迁入新组（path 不变，前端路由前缀由父级 path 决定 → /insight/*）
UPDATE public.sys_menu SET parent_id = 12000, order_num = 1 WHERE menu_id = 9070;  -- 用户画像
UPDATE public.sys_menu SET parent_id = 12000, order_num = 2 WHERE menu_id = 11979; -- 画像工作室
UPDATE public.sys_menu SET parent_id = 12000, order_num = 3 WHERE menu_id = 11980; -- 画像 RFM
UPDATE public.sys_menu SET parent_id = 12000, order_num = 4 WHERE menu_id = 11981; -- 画像分群
UPDATE public.sys_menu SET parent_id = 12000, order_num = 5 WHERE menu_id = 11982; -- 低分复核
UPDATE public.sys_menu SET parent_id = 12000, order_num = 6 WHERE menu_id = 11986; -- 数据分析

-- 任务中心收窄为纯任务流，order 连续无撞车
UPDATE public.sys_menu SET order_num = 1 WHERE menu_id = 9001;  -- 任务管理
UPDATE public.sys_menu SET order_num = 2 WHERE menu_id = 9005;  -- 我的待办
UPDATE public.sys_menu SET order_num = 3 WHERE menu_id = 9020;  -- 我的消息
UPDATE public.sys_menu SET order_num = 4 WHERE menu_id = 11970; -- 审核队列
UPDATE public.sys_menu SET order_num = 5 WHERE menu_id = 9040;  -- 数据看板

-- 角色映射：超管始终可见；原先能看画像页的角色同步获得新组入口
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 12000
FROM public.sys_role_menu rm
WHERE rm.menu_id IN (9070, 11979, 11980, 11981, 11982, 11986)
ON CONFLICT DO NOTHING;

INSERT INTO public.sys_role_menu (role_id, menu_id) VALUES (1, 12000)
ON CONFLICT DO NOTHING;

-- 删除隐藏的框架外链（菜单 4：PLUS官网）
DELETE FROM public.sys_role_menu WHERE menu_id = 4;
DELETE FROM public.sys_menu WHERE menu_id = 4;

COMMIT;

-- ===================== 2026-10-20_profile_ops.sql =====================
-- 批9 画像运营化: 分群定时刷新 / 复核SLA / 触达审计 / 模型版本纪律
-- 可重复执行(IF NOT EXISTS / ON CONFLICT); baseline 01_schema.sql 已同步

-- 1) 分群刷新模式: OFF=关闭 / DAILY=每天 / HOURLY=每小时(默认 OFF, 存量行为不变)
ALTER TABLE public.profile_segment
    ADD COLUMN IF NOT EXISTS refresh_mode character varying(16) DEFAULT 'OFF'::character varying NOT NULL;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_profile_segment_refresh_mode') THEN
        ALTER TABLE public.profile_segment
            ADD CONSTRAINT ck_profile_segment_refresh_mode CHECK (refresh_mode IN ('OFF', 'DAILY', 'HOURLY'));
    END IF;
END $$;

-- 2) 复核 SLA 天数(按业务源, 挂引擎配置; 默认 7 天)
ALTER TABLE public.profile_engine_config
    ADD COLUMN IF NOT EXISTS review_sla_days integer DEFAULT 7 NOT NULL;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_profile_engine_sla_days') THEN
        ALTER TABLE public.profile_engine_config
            ADD CONSTRAINT ck_profile_engine_sla_days CHECK (review_sla_days BETWEEN 1 AND 365);
    END IF;
END $$;

-- 3) 评分模型发布状态: DRAFT=草稿(不影响线上) / PUBLISHED=生效 / ARCHIVED=历史版本(默认 PUBLISHED, 存量即已发布)
ALTER TABLE public.profile_score_model
    ADD COLUMN IF NOT EXISTS status character varying(16) DEFAULT 'PUBLISHED'::character varying NOT NULL;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_profile_score_model_status') THEN
        ALTER TABLE public.profile_score_model
            ADD CONSTRAINT ck_profile_score_model_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'));
    END IF;
END $$;

-- 4) 触达批次(审计留痕; channel=inapp 站内通知 / export 成员导出留痕共用此表)
CREATE TABLE IF NOT EXISTS public.profile_reach_batch (
    reach_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    segment_id bigint NOT NULL,
    segment_name character varying(100),
    channel character varying(16) DEFAULT 'inapp'::character varying NOT NULL,
    title character varying(200),
    content character varying(1000),
    masked character(1) DEFAULT '0'::bpchar,
    total_count integer DEFAULT 0 NOT NULL,
    success_count integer DEFAULT 0 NOT NULL,
    fail_count integer DEFAULT 0 NOT NULL,
    retry_count integer DEFAULT 0 NOT NULL,
    status character varying(16) DEFAULT 'RUNNING'::character varying NOT NULL,
    error_msg character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_reach_batch PRIMARY KEY (reach_id),
    CONSTRAINT ck_profile_reach_channel CHECK (channel IN ('inapp', 'export')),
    CONSTRAINT ck_profile_reach_status CHECK (status IN ('RUNNING', 'DONE', 'PARTIAL', 'FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_profile_reach_batch_segment
    ON public.profile_reach_batch USING btree (tenant_id, segment_id, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_profile_reach_batch_source
    ON public.profile_reach_batch USING btree (tenant_id, source, create_time DESC);

-- 5) 触达逐人明细(收件人快照+幂等键: 同批次同用户只发一次; 重试只重发失败者)
CREATE TABLE IF NOT EXISTS public.profile_reach_item (
    item_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    reach_id bigint NOT NULL,
    subject_type character varying(32),
    subject_id character varying(64),
    user_id bigint,
    user_name character varying(64),
    dedup_key character varying(128) NOT NULL,
    status character varying(16) DEFAULT 'PENDING'::character varying NOT NULL,
    error_msg character varying(500),
    sent_time timestamp without time zone,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_profile_reach_item PRIMARY KEY (item_id),
    CONSTRAINT uk_profile_reach_item UNIQUE (reach_id, dedup_key),
    CONSTRAINT ck_profile_reach_item_status CHECK (status IN ('PENDING', 'SUCCESS', 'FAILED'))
);
CREATE INDEX IF NOT EXISTS idx_profile_reach_item_user
    ON public.profile_reach_item USING btree (tenant_id, user_id);
CREATE INDEX IF NOT EXISTS idx_profile_reach_item_reach_status
    ON public.profile_reach_item USING btree (reach_id, status);

-- 6) 模型配置版本存档(发布时把生效配置快照留档, 支持历史 diff 与一键回滚)
CREATE TABLE IF NOT EXISTS public.profile_model_config_snapshot (
    config_snapshot_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    model_id bigint NOT NULL,
    model_code character varying(64) NOT NULL,
    model_version character varying(32) NOT NULL,
    config jsonb DEFAULT '{}'::jsonb NOT NULL,
    score_max numeric(8,2) DEFAULT 100 NOT NULL,
    published_by bigint,
    published_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    del_flag character(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_profile_model_config_snapshot PRIMARY KEY (config_snapshot_id),
    CONSTRAINT uk_profile_model_config_snapshot UNIQUE (tenant_id, model_id, model_version)
);
CREATE INDEX IF NOT EXISTS idx_profile_model_cfg_snap_model
    ON public.profile_model_config_snapshot USING btree (tenant_id, model_id, published_at DESC);

-- ===================== 2026-10-20_profile_reach_menu.sql =====================
-- 批9 画像运营化: 触达/导出权限按钮(挂 9070 用户画像下, 与画像域 F 按钮同风格)
-- 可重复执行; baseline 02_reference.sql 已同步
INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 11990, '画像触达查询', 9070, 20, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:reach:query', '#', 103, 1, '2026-10-20 00:00:00', NULL, NULL, '触达记录/逐人明细/导出记录查看'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 11990);

INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 11991, '画像触达与导出', 9070, 21, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:reach:edit', '#', 103, 1, '2026-10-20 00:00:00', NULL, NULL, '发通知/失败重试/成员导出(明文)'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 11991);

-- 角色授权: 管理员角色(role_id=1)默认全量(F 按钮对超管天然可见, 此处给 member 之外的运营角色留位);
-- 演示库仅超管使用触达能力, 不给 user001 授权(双视角验收用)

-- ===================== 2026-10-21_assistant_tool_gate.sql =====================
-- 2026-10-21 批1 Q1=B（开关矩阵）：dcs_assistant_config 新增 tool_gate 列
-- 存租户级工具档位覆盖 JSON（工具名→off/member/admin），空=全部按内置门槛。
-- text 存 JSON 字符串（避开 jsonb ::cast 写入链），读侧 AssistantConfigService.parseToolGate 容错解析。
-- 幂等可重跑。

ALTER TABLE public.dcs_assistant_config ADD COLUMN IF NOT EXISTS tool_gate text;

-- ===================== 2026-10-21_key_mgmt_provider_default_base_url.sql =====================
-- 2026-10-21 批1 钥匙管理：标准厂商空 base_url 补官方默认地址
-- 背景：批0 实证——空 base_url 的配置行会让运行时静默回退框架默认 api.openai.com
-- （本网络被 DNS 污染=30s 建连超时）。后端已改为保存时自动补齐（DcsAiProvider.defaultBaseUrl，
-- updateAccess/addKey/upsert 三处同口径），本迁移修复存量空地址行。
-- 口径：与后端枚举 DcsAiProvider 完全一致；CUSTOM 厂商不补（必须手填）。
-- 幂等可重跑：仅命中空地址行，补过即不再命中。

UPDATE dcs_ai_config SET base_url = 'https://api.openai.com/v1'          WHERE provider = 'OPENAI'          AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://api.deepseek.com/v1'       WHERE provider = 'DEEPSEEK'        AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://dashscope.aliyuncs.com/compatible-mode/v1' WHERE provider = 'QWEN'          AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://open.bigmodel.cn/api/paas/v4'              WHERE provider = 'ZHIPU'         AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://api.moonshot.ai/v1'        WHERE provider = 'KIMI'            AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://qianfan.baidubce.com/v2'   WHERE provider = 'BAIDU_QIANFAN'   AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://api.hunyuan.cloud.tencent.com/v1'           WHERE provider = 'TENCENT_HUNYUAN' AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://spark-api-open.xf-yun.com/v1'              WHERE provider = 'XFYUN_SPARK'   AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://api.minimax.cn/v1'         WHERE provider = 'MINIMAX'        AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://api.siliconflow.cn/v1'     WHERE provider = 'SILICONFLOW'    AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://opencode.ai/zen/go/v1'     WHERE provider = 'OPENCODE'       AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://api.stepfun.com/v1'        WHERE provider = 'STEPFUN'        AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://openrouter.ai/api/v1'      WHERE provider = 'OPENROUTER'     AND COALESCE(trim(base_url), '') = '';

UPDATE dcs_ai_provider_access SET base_url = 'https://api.openai.com/v1'          WHERE provider = 'OPENAI'          AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://api.deepseek.com/v1'       WHERE provider = 'DEEPSEEK'        AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://dashscope.aliyuncs.com/compatible-mode/v1' WHERE provider = 'QWEN'          AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://open.bigmodel.cn/api/paas/v4'              WHERE provider = 'ZHIPU'         AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://api.moonshot.ai/v1'        WHERE provider = 'KIMI'            AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://qianfan.baidubce.com/v2'   WHERE provider = 'BAIDU_QIANFAN'   AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://api.hunyuan.cloud.tencent.com/v1'           WHERE provider = 'TENCENT_HUNYUAN' AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://spark-api-open.xf-yun.com/v1'              WHERE provider = 'XFYUN_SPARK'   AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://api.minimax.cn/v1'         WHERE provider = 'MINIMAX'        AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://api.siliconflow.cn/v1'     WHERE provider = 'SILICONFLOW'    AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://opencode.ai/zen/go/v1'     WHERE provider = 'OPENCODE'       AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://api.stepfun.com/v1'        WHERE provider = 'STEPFUN'        AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://openrouter.ai/api/v1'      WHERE provider = 'OPENROUTER'     AND COALESCE(trim(base_url), '') = '';

-- ===================== 2026-10-21_mcp_pilot_tables.sql =====================
-- 2026-10-21 批2 Q2=A（MCP 试点包）：dcs_mcp_server / dcs_mcp_call_log 两表（可重复执行）

-- =====================================================================
-- 批2 Q2=A（MCP 试点包，2026-10-21）：外部 MCP 服务注册 + 调用审计两表
-- 设计口径：试点=平台级共享（tenant_id 预留，试点行恒 '000000'）；
-- read_only 恒 '1'（试点只收只读服务）；密钥/敏感头 headers 加密存储。
-- =====================================================================

-- MCP 服务注册表（管理台「外部工具」页签维护；STDIO/STREAMABLE_HTTP/SSE 三传输）
CREATE TABLE IF NOT EXISTS public.dcs_mcp_server (
    id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    server_name character varying(50) NOT NULL,
    display_name character varying(100),
    transport_type character varying(20) NOT NULL,
    command character varying(255),
    args text,
    url character varying(500),
    headers text,
    read_only character(1) DEFAULT '1'::bpchar NOT NULL,
    enabled character(1) DEFAULT '0'::bpchar NOT NULL,
    remark character varying(255),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone,
    update_by bigint,
    update_time timestamp without time zone,
    CONSTRAINT pk_dcs_mcp_server PRIMARY KEY (id),
    -- 试点物理删（审计表留痕），不引入逻辑删避免同名重建撞键
    CONSTRAINT uk_dcs_mcp_server_name UNIQUE (server_name, tenant_id),
    CONSTRAINT ck_dcs_mcp_transport CHECK (transport_type IN ('STDIO', 'STREAMABLE_HTTP', 'SSE')),
    CONSTRAINT ck_dcs_mcp_readonly CHECK (read_only = '1'),
    CONSTRAINT ck_dcs_mcp_enabled CHECK (enabled IN ('0', '1'))
);
CREATE INDEX IF NOT EXISTS idx_dcs_mcp_server_tenant ON public.dcs_mcp_server (tenant_id, enabled);

-- MCP 工具调用审计（挂载/每次真实调用两态都落账：MOUNT_OK/MOUNT_ERROR/CALL_OK/CALL_ERROR/RATE_LIMITED）
CREATE TABLE IF NOT EXISTS public.dcs_mcp_call_log (
    id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    user_id bigint,
    server_name character varying(50) NOT NULL,
    tool_name character varying(100),
    action character varying(20) NOT NULL,
    status character(1) DEFAULT '0'::bpchar NOT NULL,
    duration_ms integer,
    error_msg character varying(500),
    create_time timestamp without time zone,
    CONSTRAINT pk_dcs_mcp_call_log PRIMARY KEY (id),
    CONSTRAINT ck_dcs_mcp_call_action CHECK (action IN ('MOUNT_OK', 'MOUNT_ERROR', 'CALL_OK', 'CALL_ERROR', 'RATE_LIMITED')),
    CONSTRAINT ck_dcs_mcp_call_status CHECK (status IN ('0', '1'))
);
CREATE INDEX IF NOT EXISTS idx_dcs_mcp_call_tenant_time ON public.dcs_mcp_call_log (tenant_id, create_time DESC);

-- ===================== 2026-10-22_assistant_compaction.sql =====================
-- 批A-2（2026-10-22 拍板 Q9-A）：自动压缩记录表（链式审计）
-- 背景：压缩目前只落 dcs_assistant_message 的 system 分隔条，无法审计「压了几次、
--       每次前后多少、上一条是谁」。本表一行一次压缩，prev_id 指向上一条同会话记录。
-- 可重复执行（幂等）。

CREATE TABLE IF NOT EXISTS public.dcs_assistant_compaction (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    conversation_id bigint NOT NULL,
    user_id bigint NOT NULL,
    thread_id character varying(128),
    trigger_type character varying(16) DEFAULT 'AUTO'::character varying NOT NULL,
    status character varying(16) DEFAULT 'SUCCESS'::character varying NOT NULL,
    before_tokens integer NOT NULL,
    after_tokens integer NOT NULL,
    before_messages integer NOT NULL,
    after_messages integer NOT NULL,
    summary text,
    reason character varying(64),
    prev_id bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT dcs_assistant_compaction_pk PRIMARY KEY (id)
);

-- 已建表环境补列（Q9-A：摘要正文 + 原因；幂等）
ALTER TABLE public.dcs_assistant_compaction ADD COLUMN IF NOT EXISTS summary text;
ALTER TABLE public.dcs_assistant_compaction ADD COLUMN IF NOT EXISTS reason character varying(64);

CREATE INDEX IF NOT EXISTS idx_dcs_asst_comp_conv
    ON public.dcs_assistant_compaction (tenant_id, conversation_id, id);

-- 二审 P2：保留期清理按 create_time 过滤分批删——补时间索引（幂等）
CREATE INDEX IF NOT EXISTS idx_dcs_asst_comp_time
    ON public.dcs_assistant_compaction (create_time);

-- ===================== 2026-10-22_mcp_calllog_index_retention.sql =====================
-- G4-#5（2026-10-22）：MCP 调用审计表补服务维度索引
-- 背景：管理页审计查询按 server_name 等值 + create_time DESC 分页；现有唯一索引
-- idx_dcs_mcp_call_tenant_time 首列是 tenant_id——超管全量路径用不上、服务过滤路径
-- 首列缺失退化顺序扫描。本索引与该查询模式精确匹配。
-- 可重复执行（幂等）；保留期清理（90 天）由后端定时任务实现，无需 DDL。

CREATE INDEX IF NOT EXISTS idx_dcs_mcp_call_server_time
    ON public.dcs_mcp_call_log (server_name, create_time DESC);

-- ===================== 2026-10-22_mcp_calllog_retention_index.sql =====================
-- G4 三审回修（2026-10-22）：审计保留期清理的支撑索引
-- 背景：每日清理 DELETE ... WHERE create_time < cutoff（分批 5000 子查询）；既有两个
-- 索引前导列分别是 tenant_id / server_name，create_time 无前导索引 → 每批顺序扫
-- （dspro/dsflash 双席共识 P2）。本索引专供清理路径；可重复执行。

CREATE INDEX IF NOT EXISTS idx_dcs_mcp_call_create_time
    ON public.dcs_mcp_call_log (create_time);

-- ===================== 2026-10-22_mcp_elicit_audit_action.sql =====================
-- G3 三审回修（双席共识 P0）：dcs_mcp_call_log.action CHECK 约束补 ELICIT_* 五值
-- 背景：G3-A1 elicitation 反问桥写入 ELICIT_ASK/ACCEPT/DECLINE/CANCEL/TIMEOUT 审计，
-- 原五值约束 100% 拒收（异常被 audit() 静默吞掉，审计账本零落库）。
-- 可重复执行（幂等）：DROP IF EXISTS + ADD。

ALTER TABLE public.dcs_mcp_call_log DROP CONSTRAINT IF EXISTS ck_dcs_mcp_call_action;

ALTER TABLE public.dcs_mcp_call_log ADD CONSTRAINT ck_dcs_mcp_call_action CHECK (
    action IN ('MOUNT_OK', 'MOUNT_ERROR', 'CALL_OK', 'CALL_ERROR', 'RATE_LIMITED',
               'ELICIT_ASK', 'ELICIT_ACCEPT', 'ELICIT_DECLINE', 'ELICIT_CANCEL', 'ELICIT_TIMEOUT')
);

-- ===================== 2026-10-22_support_changelog_menu.sql =====================
-- 2026-10-22：修复「系统支持」白屏——补回迁移漏搬的「更新日志」子菜单
-- 背景：开源迁移基线只搬了顶级目录 9180，未搬子菜单 9160（git 全历史无 9160 记录），
--       前端 views/dcs/changelog 与首页「全部日志 →」入口仍在，访问 /support 无子路由 → 白屏。
-- 口径：更新日志为纯前端静态页（无后端接口）；与母仓差异——反馈中心未迁移（本仓无对应页面），
--       可见范围改为全体登录角色（首页对所有角色展示入口）。
-- 可重复执行（幂等）：菜单 ON CONFLICT DO UPDATE 校正，授权 DO NOTHING。

INSERT INTO public.sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (9160, '更新日志', 9180, 1, 'changelog', 'dcs/changelog/index', '',
     '1', '0', 'C', '0', '0', '', 'log',
     103, 1, now(), NULL, NULL, '系统版本迭代记录(纯前端静态页, 全体登录角色可见)')
ON CONFLICT (menu_id) DO UPDATE SET
    parent_id = EXCLUDED.parent_id,
    path = EXCLUDED.path,
    component = EXCLUDED.component,
    menu_type = EXCLUDED.menu_type,
    visible = EXCLUDED.visible,
    status = EXCLUDED.status,
    perms = EXCLUDED.perms,
    remark = EXCLUDED.remark,
    update_time = now();

UPDATE public.sys_menu
   SET remark = '系统支持目录(更新日志; 纯前端静态页)', update_time = now()
 WHERE menu_id = 9180;

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
  FROM public.sys_role r
 CROSS JOIN (VALUES (9180), (9160)) AS m(menu_id)
 WHERE r.del_flag = '0' AND r.status = '0'
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ===================== 2026-10-23_drop_external_collect.sql =====================
-- 2026-10-23 拍板：移除外部网站接入能力（公开采集口/SDK/siteKey 全链下线）
-- 系统回归内部数据源：业务系统经 ProfileEventPublisher 喂事件，离线数据走 CSV 补录。
-- 本迁移可重复执行：仅删除外部采集专属的两张表（幂等）。
DROP TABLE IF EXISTS public.analytics_site;
DROP TABLE IF EXISTS public.analytics_collect_log;

-- ===================== 2026-10-23_n1_api_format_price_source.sql =====================
-- N1 批（2026-10-23）：接口格式三选一 + 价格来源标记（可重复执行）
-- Q5-A：老配置/老钥匙默认 CHAT_COMPLETIONS，行为不变
ALTER TABLE public.dcs_ai_config
    ADD COLUMN IF NOT EXISTS api_format character varying(24) DEFAULT 'CHAT_COMPLETIONS'::character varying NOT NULL;

ALTER TABLE public.dcs_ai_provider_access
    ADD COLUMN IF NOT EXISTS api_format character varying(24) DEFAULT 'CHAT_COMPLETIONS'::character varying NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_dcs_ai_config_api_format') THEN
        ALTER TABLE public.dcs_ai_config
            ADD CONSTRAINT ck_dcs_ai_config_api_format
            CHECK (api_format IN ('CHAT_COMPLETIONS', 'ANTHROPIC_MESSAGES', 'RESPONSES'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_dcs_ai_provider_access_api_format') THEN
        ALTER TABLE public.dcs_ai_provider_access
            ADD CONSTRAINT ck_dcs_ai_provider_access_api_format
            CHECK (api_format IN ('CHAT_COMPLETIONS', 'ANTHROPIC_MESSAGES', 'RESPONSES'));
    END IF;
END $$;

-- Q6-A：价格行来源与手工覆盖标记（models.dev 同步不覆盖人工价）
ALTER TABLE public.dcs_ai_model_price
    ADD COLUMN IF NOT EXISTS price_source character varying(16) DEFAULT 'MANUAL'::character varying NOT NULL;

-- manual_override 首建时把存量行一并锁住：迁移前的价格全是人工维护的，
-- 默认 '0' 会让第一次 models.dev 同步把它们覆盖掉。包在「列不存在才执行」里保证可重跑
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'dcs_ai_model_price' AND column_name = 'manual_override'
    ) THEN
        ALTER TABLE public.dcs_ai_model_price
            ADD COLUMN manual_override character(1) DEFAULT '0'::bpchar NOT NULL;
        UPDATE public.dcs_ai_model_price SET manual_override = '1';
    END IF;
END $$;

ALTER TABLE public.dcs_ai_model_price
    ADD COLUMN IF NOT EXISTS last_synced_at timestamp without time zone;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_dcs_ai_model_price_source') THEN
        ALTER TABLE public.dcs_ai_model_price
            ADD CONSTRAINT ck_dcs_ai_model_price_source
            CHECK (price_source IN ('MANUAL', 'MODELS_DEV', 'IMPORT'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_dcs_ai_model_price_manual_override') THEN
        ALTER TABLE public.dcs_ai_model_price
            ADD CONSTRAINT ck_dcs_ai_model_price_manual_override
            CHECK (manual_override IN ('0', '1'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_dcs_ai_model_price_source
    ON public.dcs_ai_model_price (tenant_id, price_source)
    WHERE del_flag = '0';

-- ===================== 2026-10-23_n3_announcement_feedback_changelog.sql =====================
-- 2026-10-23 N3：公告专业化 + 反馈中心 + 更新日志数据库驱动
-- 可重复执行：列 ADD IF NOT EXISTS / 表 IF NOT EXISTS / 菜单 ON CONFLICT DO UPDATE / 授权 DO NOTHING

-- ===== 1. 公告：置顶 / 发布时间 / 过期时间 =====
ALTER TABLE public.sys_notice ADD COLUMN IF NOT EXISTS is_top char(1) DEFAULT '0'::bpchar;
ALTER TABLE public.sys_notice ADD COLUMN IF NOT EXISTS publish_time timestamp(6) without time zone;
ALTER TABLE public.sys_notice ADD COLUMN IF NOT EXISTS expire_time timestamp(6) without time zone;

COMMENT ON COLUMN public.sys_notice.is_top IS '置顶 0=否 1=是';
COMMENT ON COLUMN public.sys_notice.publish_time IS '发布时间；为空视为立即发布';
COMMENT ON COLUMN public.sys_notice.expire_time IS '过期时间；为空=长期有效';

CREATE INDEX IF NOT EXISTS idx_sys_notice_portal
    ON public.sys_notice (tenant_id, status, is_top DESC, publish_time DESC);

-- ===== 2. 公告已读（按人一行） =====
CREATE TABLE IF NOT EXISTS public.sys_notice_read (
    notice_id bigint NOT NULL,
    user_id   bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying,
    read_time timestamp(6) without time zone DEFAULT now(),
    CONSTRAINT pk_sys_notice_read PRIMARY KEY (notice_id, user_id)
);

COMMENT ON TABLE public.sys_notice_read IS '公告已读记录（公告+用户唯一）';

CREATE INDEX IF NOT EXISTS idx_sys_notice_read_user
    ON public.sys_notice_read (tenant_id, user_id);

-- ===== 3. 站内通知支持跳转链接 =====
ALTER TABLE public.dcs_notice ADD COLUMN IF NOT EXISTS ref_url character varying(500);
COMMENT ON COLUMN public.dcs_notice.ref_url IS '点击通知跳转的前端路由（可空）';

-- ===== 4. 反馈中心 =====
CREATE TABLE IF NOT EXISTS public.dcs_feedback (
    feedback_id   bigint NOT NULL,
    tenant_id     character varying(20) NOT NULL DEFAULT '000000'::character varying,
    title         character varying(200) NOT NULL,
    content       character varying(2000) NOT NULL,
    feedback_type character varying(20) NOT NULL,
    oss_ids       character varying(500),
    contact       character varying(100),
    context_json  text,
    status        character varying(16) NOT NULL DEFAULT 'PENDING'::character varying,
    handler_id    bigint,
    handler_name  character varying(64),
    handle_note   character varying(1000),
    handle_time   timestamp(6) without time zone,
    create_dept   bigint,
    create_by     bigint,
    create_time   timestamp(6) without time zone,
    update_by     bigint,
    update_time   timestamp(6) without time zone,
    del_flag      char(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_dcs_feedback PRIMARY KEY (feedback_id),
    CONSTRAINT ck_dcs_feedback_type CHECK (feedback_type::text = ANY (ARRAY['BUG','EXPERIENCE','SUGGEST','OTHER']::text[])),
    CONSTRAINT ck_dcs_feedback_status CHECK (status::text = ANY (ARRAY['PENDING','PROCESSING','RESOLVED','CLOSED']::text[]))
);

COMMENT ON TABLE public.dcs_feedback IS '产品反馈（单表；最近一次处理意见覆盖式存储）';
COMMENT ON COLUMN public.dcs_feedback.feedback_type IS 'BUG=缺陷 EXPERIENCE=体验 SUGGEST=建议 OTHER=其它';
COMMENT ON COLUMN public.dcs_feedback.context_json IS '提交时自动采集的页面/浏览器/版本上下文（JSON）';

CREATE INDEX IF NOT EXISTS idx_dcs_feedback_status ON public.dcs_feedback (status);
CREATE INDEX IF NOT EXISTS idx_dcs_feedback_create_by ON public.dcs_feedback (create_by);
CREATE INDEX IF NOT EXISTS idx_dcs_feedback_tenant_status
    ON public.dcs_feedback (tenant_id, status, create_time DESC);

-- ===== 5. 更新日志（全局表，已在 tenant.excludes） =====
CREATE TABLE IF NOT EXISTS public.dcs_changelog (
    log_id         bigint NOT NULL,
    version        character varying(32) NOT NULL,
    title          character varying(200) NOT NULL,
    items          text NOT NULL DEFAULT '[]'::text,
    target_tenants text NOT NULL DEFAULT '[]'::text,
    status         char(1) NOT NULL DEFAULT '0'::bpchar,
    publish_time   timestamp(6) without time zone,
    create_dept    bigint,
    create_by      bigint,
    create_time    timestamp(6) without time zone,
    update_by      bigint,
    update_time    timestamp(6) without time zone,
    del_flag       char(1) DEFAULT '0'::bpchar,
    CONSTRAINT pk_dcs_changelog PRIMARY KEY (log_id),
    CONSTRAINT ck_dcs_changelog_status CHECK (status::text = ANY (ARRAY['0','1']::text[]))
);

COMMENT ON TABLE public.dcs_changelog IS '更新日志（全局；visible 由 target_tenants 名单控制）';
COMMENT ON COLUMN public.dcs_changelog.items IS '条目 JSON：[{"type":"new|optimize|fix","text":"..."}]';
COMMENT ON COLUMN public.dcs_changelog.target_tenants IS '推送租户名单 JSON：["000000",...]';
COMMENT ON COLUMN public.dcs_changelog.status IS '0=草稿 1=已发布';

CREATE UNIQUE INDEX IF NOT EXISTS uk_dcs_changelog_version
    ON public.dcs_changelog (version) WHERE (del_flag = '0'::bpchar);
CREATE INDEX IF NOT EXISTS idx_dcs_changelog_status_publish
    ON public.dcs_changelog (status, publish_time DESC);

-- ===== 6. 菜单与权限 =====
-- 6.1 公告（系统支持目录下，全体登录角色可见）
INSERT INTO public.sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (9150, '公告', 9180, 0, 'announcement', 'dcs/announcement/index', '',
     '1', '0', 'C', '0', '0', 'dcs:announcement:list', 'message',
     103, 1, now(), NULL, NULL, '公告卡片流与文章页（全体登录角色）')
ON CONFLICT (menu_id) DO UPDATE SET
    parent_id = EXCLUDED.parent_id, path = EXCLUDED.path, component = EXCLUDED.component,
    menu_type = EXCLUDED.menu_type, visible = EXCLUDED.visible, status = EXCLUDED.status,
    perms = EXCLUDED.perms, remark = EXCLUDED.remark, update_time = now();

-- 6.2 更新日志：补列表权限 + 四个按钮
UPDATE public.sys_menu SET perms = 'dcs:changelog:list', remark = '系统版本迭代记录（数据库驱动）', update_time = now()
 WHERE menu_id = 9160;

INSERT INTO public.sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (9161, '日志查询', 9160, 1, '', NULL, '', '1', '0', 'F', '0', '0', 'dcs:changelog:query', '#', 103, 1, now(), NULL, NULL, ''),
    (9162, '版本新增', 9160, 2, '', NULL, '', '1', '0', 'F', '0', '0', 'dcs:changelog:add', '#', 103, 1, now(), NULL, NULL, ''),
    (9163, '版本编辑', 9160, 3, '', NULL, '', '1', '0', 'F', '0', '0', 'dcs:changelog:edit', '#', 103, 1, now(), NULL, NULL, ''),
    (9164, '版本删除', 9160, 4, '', NULL, '', '1', '0', 'F', '0', '0', 'dcs:changelog:remove', '#', 103, 1, now(), NULL, NULL, '')
ON CONFLICT (menu_id) DO UPDATE SET
    parent_id = EXCLUDED.parent_id, perms = EXCLUDED.perms, visible = EXCLUDED.visible,
    status = EXCLUDED.status, update_time = now();

-- 6.3 反馈中心
INSERT INTO public.sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (9170, '反馈中心', 9180, 2, 'feedback', 'dcs/feedback/index', '',
     '1', '0', 'C', '0', '0', 'dcs:feedback:list', 'email',
     103, 1, now(), NULL, NULL, '产品反馈管理（租户管理员与超管）'),
    (9171, '反馈查询', 9170, 1, '', NULL, '', '1', '0', 'F', '0', '0', 'dcs:feedback:query', '#', 103, 1, now(), NULL, NULL, ''),
    (9172, '反馈处理', 9170, 2, '', NULL, '', '1', '0', 'F', '0', '0', 'dcs:feedback:handle', '#', 103, 1, now(), NULL, NULL, '')
ON CONFLICT (menu_id) DO UPDATE SET
    parent_id = EXCLUDED.parent_id, path = EXCLUDED.path, component = EXCLUDED.component,
    menu_type = EXCLUDED.menu_type, visible = EXCLUDED.visible, status = EXCLUDED.status,
    perms = EXCLUDED.perms, remark = EXCLUDED.remark, update_time = now();

-- 6.4 授权
-- 公告 + 更新日志只读：所有有效角色
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
  FROM public.sys_role r
 CROSS JOIN (VALUES (9150), (9160), (9161)) AS m(menu_id)
 WHERE r.del_flag = '0' AND r.status = '0'
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 更新日志写面：仅超管（role_key='superadmin'）
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
  FROM public.sys_role r
 CROSS JOIN (VALUES (9162), (9163), (9164)) AS m(menu_id)
 WHERE r.del_flag = '0' AND r.status = '0' AND r.role_key = 'superadmin'
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- 反馈中心：超管 + 租户管理员
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
  FROM public.sys_role r
 CROSS JOIN (VALUES (9170), (9171), (9172)) AS m(menu_id)
 WHERE r.del_flag = '0' AND r.status = '0' AND r.role_key IN ('superadmin', 'tenant_admin')
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ===== 7. 历史更新日志迁移（从原前端静态 changelog.ts 一次性导入） =====
-- 已发布、推送给当前全部有效单位；不触发通知（历史记录不打扰）。重复执行自动跳过。

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000000, 'v1.10.0', '钥匙与模型：三种接口格式、20 家厂商模板、价格来源看得见',
  '[{"type":"new","text":"新增模型时可以从 20 家厂商模板一键带出接口地址、格式和推荐模型名；接口格式支持 Chat Completions / Anthropic Messages / Responses 三种，表单下方直接显示最终会请求的地址，非对话格式会标明「仅供连通测试」。"},{"type":"new","text":"价格目录新增「从 models.dev 同步」和「JSON 导入」，每行标出价格来源（手工 / 同步 / 导入 / 已锁定），同步不会覆盖手工价；「价格来源」列移到模型旁边，窄屏也不用横向滚动才能看到。"},{"type":"new","text":"余额与额度查询扩到 7 家厂商（Kimi / MiniMax 的订阅额度按「剩余百分比」显示）；手动「测试」与每日巡检写进同一张表，测完徽标立刻刷新；默认模型不可用时会自动沿用上次成功使用的模型。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-23 12:00:00', '0', '2026-10-23 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000001, 'v1.9.7', '细节修缮：卡片样式与说明文案对齐内部口径',
  '[{"type":"fix","text":"修复「数据分析」页卡片样式：移除外部接入时误留的一条旧样式规则曾让所有卡片顶部多出一条虚线和多余间距，已清除。"},{"type":"fix","text":"首页「数据脉搏」的来源说明不再提已移除的「采集站点」；文档中的页签清单补齐到实际的十个页签，数据库升级说明改为按文件名顺序执行全部增量脚本。"},{"type":"fix","text":"演示数据脚本加固：指定天数很小（1~6 天）时不再卡死；登录失败会提示检查验证码设置；文档补上「同一天内可重复执行」的准确说明。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-23 12:00:00', '0', '2026-10-23 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000002, 'v1.9.6', '升级脚本可安全重复执行',
  '[{"type":"fix","text":"修复一个升级脚本的问题：在全新安装的库上按顺序重放全部升级脚本时，会在事件归档表这一步因主键已存在而中断；现在重复执行会自动跳过，不再报错。"},{"type":"new","text":"经真实数据库全链路实测：全新安装（基线 7 个脚本）和「全新安装 + 重放全部 21 个升级脚本」两条路径均可一次跑通。"},{"type":"fix","text":"同步清理外部接入残留：架构与画像引擎文档、数据库安装说明（升级步骤与实际脚本对齐）、开发代理配置、开发规范文档等十余处不再提及已移除的接入方式。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-23 12:00:00', '0', '2026-10-23 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000003, 'v1.9.5', '移除外部网站接入：系统专注内部数据源',
  '[{"type":"optimize","text":"「数据接入」页签改为「数据导入」：只留 CSV 离线补录和全域总览；建站、接入代码、检测、模拟访客这些外部网站相关功能全部下线，「数据分析」默认页签回到「流量概览」。"},{"type":"optimize","text":"公开采集接口、网页采集脚本（SDK）、站点钥匙整套外部通道移除——数据进系统只有两条路：内部业务系统走事件协议，离线数据走 CSV 导入；部署模板里的采集转发规则一并删除。"},{"type":"new","text":"演示数据播种脚本改为走管理员的 CSV 导入通道（事件与口径不变，仍可重复执行）。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-23 12:00:00', '0', '2026-10-23 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000004, 'v1.9.4', '网站数据接入变简单：一页三块，5 分钟看到自己的数据',
  '[{"type":"optimize","text":"「数据分析」第一个页签改为「数据接入」：先看看（模拟几个访客即时验证）/ 接我的网站（起个名字就建站，复制整段代码贴进网页）/ 导入历史数据，三块搞定；站点钥匙等术语折进「高级」；已有站点刷新后直接亮出接入代码。"},{"type":"fix","text":"修复接入代码贴到自己网站后数据发不出去的问题（地址此前按相对路径生成，会发到用户自己域名）；「清掉模拟数据」现在连画像主体、访客首见一起清，不留幽灵记录。"},{"type":"new","text":"新增「检测接入」：贴完代码点一下，钥匙有效+测试事件收到两个绿灯亮起即链路打通；Docker 部署模板补上了采集转发规则（仅放行 collect 一个公开端点），README 增加 5 分钟接入指引。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-22 12:00:00', '0', '2026-10-22 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000005, 'v1.9.3', '长对话自动打包前文，手动「压缩」按钮下线',
  '[{"type":"fix","text":"对话太长时系统会自动把前文打包成一段摘要腾出空间，不用再找按钮：输入框的「打包前文」按钮和 /压缩 命令已删除。"},{"type":"optimize","text":"会话状态环改成「水位表」：能看到已用多少、窗口多大、到哪条线会自动打包；离打包线越近环越橙越红。"},{"type":"optimize","text":"翻看超长历史会话现在显示最近的消息（此前只显示最早 100 条，看着像丢消息）；打包暂停时界面会留一句人话提示。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-22 12:00:00', '0', '2026-10-22 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000006, 'v1.9.2', '「系统支持」不再白屏，更新日志能正常打开',
  '[{"type":"new","text":"「系统支持 → 更新日志」页面回来了：此前点开是空白页（迁移时只搬了外框、漏了里面的页面菜单），现在能正常看到所有版本更新记录。"},{"type":"fix","text":"直接访问目录地址（如 /support）会自动打开它下面的第一个页面，不再是空白页；其他一级目录同样受益。"},{"type":"new","text":"更新日志对所有登录用户可见——首页「全部日志」入口本来就人人可见，现在点进去也不会再被权限挡住。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-22 12:00:00', '0', '2026-10-22 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000007, 'v1.9.1', '前端类型检查清零并接入构建硬闸，顺手修好三处小毛病',
  '[{"type":"optimize","text":"前端类型检查从 183 处报错清到 0，并且接进生产构建：以后有类型错误构建直接失败，不达标上不了线。"},{"type":"optimize","text":"暗色模式下侧栏 Logo 现在会正确切换暗色版（此前判断失效恒走亮色）；AI 助手首屏问候语优先显示昵称（此前该字段名写错一直显示备用名）。"},{"type":"fix","text":"手动点击「刷新会话」「保存草稿」按钮不再被误判为静默模式：加载动画和成功提示恢复正常。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-22 12:00:00', '0', '2026-10-22 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000008, 'v1.9.0', '外部工具配钥匙、进开关矩阵，安全再上三道锁',
  '[{"type":"new","text":"外部 HTTP 工具可以配鉴权头了（比如 Authorization）：值加密存进数据库（拿到数据库也看不到原文），管理页只显示键名不显示值，操作日志也不记录。"},{"type":"new","text":"「工具开关矩阵」新增外部服务行（mcp: 开头）：可以把某个外部工具对本租户整体关闭或设为仅管理员可用，下一轮新会话生效。"},{"type":"optimize","text":"外部工具的调用记录保留 90 天自动清理，查询更快；写类外部工具会先弹确认卡再执行（与平台工具同一道安全闸）。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-22 12:00:00', '0', '2026-10-22 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000009, 'v1.8.9', '反问卡加固：不再替你选、不再卡死提交',
  '[{"type":"fix","text":"反问卡的「提交回答」按钮修好了必填没填时的判定（此前即使填完也按不下去）；选择题不再自动替你选第一项，必须自己选。"},{"type":"optimize","text":"反问等待改用服务器给的剩余时间倒计时，两台电脑时间不准也不会一打开就显示超时；对话结束或出错时，已失效的反问卡会自动收走。"},{"type":"new","text":"管理后台审计里新增了反问相关的中文说明（反问用户 / 已作答 / 被拒绝 / 被关闭 / 超时关闭）。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-22 12:00:00', '0', '2026-10-22 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000010, 'v1.8.8', '外部工具会反问了，答完接着跑',
  '[{"type":"new","text":"接了外部工具（MCP）的对话里，工具缺信息时会弹出一张「反问卡」直接问你（比如查天气缺城市时让你选城市）；提交回答后工具继续执行，不用重新问一遍。也可以拒绝回答或直接关闭。"},{"type":"optimize","text":"反问等待有倒计时（默认 5 分钟），超时自动关闭不会一直挂着；每次反问和作答都有审计记录。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-22 12:00:00', '0', '2026-10-22 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000011, 'v1.8.7', '不挑模型也能知道在跟谁说话了',
  '[{"type":"new","text":"管理员没开「允许用户自选模型」时，助手输入框上会显示一个只读的小标签，标明当前生效的默认模型；想换模型找管理员即可（窄屏放不下时收成图标，悬停可看全名）。"},{"type":"new","text":"顺手修活了一个一直没生效的提醒：管理员设置的默认模型失效时，选择器会如实标出「默认模型不可用」（之前这个提醒从未亮过）。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-21 12:00:00', '0', '2026-10-21 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000012, 'v1.8.6', '停止按钮真的停了，危险操作必过确认',
  '[{"type":"fix","text":"对话里的「停止」现在会真正叫停后台生成（之前只是不再显示，后台还在跑），停下来的对话会明确标记为「用户已停止」。"},{"type":"fix","text":"修复一个隐藏问题：助手的写类操作（如建任务草稿）此前可能不经确认直接执行——现在一律先弹确认卡，点了取消的操作会留审计记录。"},{"type":"optimize","text":"管理员调整工具开关后，正在聊的对话下一句就按新配置生效，不用重开会话。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-21 12:00:00', '0', '2026-10-21 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000013, 'v1.8.5', '外部工具试点加固（三审回修）',
  '[{"type":"fix","text":"修掉两处会导致外部工具挂不上/悄悄被关的问题：重名预检现在先握手再列工具；闲置回收认对了会话，不再误关正在用的连接。"},{"type":"fix","text":"只读的外部工具（如天气查询）现在声明只读身份，对话里直接用，不再每次弹确认框。"},{"type":"optimize","text":"管理页外部工具列表补上「编辑」入口，开关失败自动弹回原样，增删改后审计列表立刻刷新。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-21 12:00:00', '0', '2026-10-21 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000014, 'v1.8.4', '助手能连外部工具了（MCP 试点）',
  '[{"type":"new","text":"AI 助手管理新增「外部工具」页签：管理员可以把只读的 MCP 服务挂进助手（对话里就能查天气这类外部数据），每次挂载和调用都有审计记录。"},{"type":"optimize","text":"安全闸全开：只允许免密钥只读服务，脚本/地址都要在白名单内才保存得了；单个服务挂了不影响正常对话。"},{"type":"optimize","text":"每次调用有次数上限（每分钟 30 次），超限会明确提示，防止外部服务被刷。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-21 12:00:00', '0', '2026-10-21 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000015, 'v1.8.3', '助手的工具，管理员现在能自己开关了',
  '[{"type":"new","text":"AI 助手管理新增「工具开关」页签：每个工具可以调成全员可用 / 仅管理员 / 关闭，也可以勾选一批工具批量设置，不用再等发版。"},{"type":"optimize","text":"改动只对本单位生效；正在聊的对话下一句起就按新开关生效（此前的旧版说的是\"要重开会话\"，后来已改进为即时生效）。"},{"type":"optimize","text":"「能力全景」页同步多了一列「本租户生效档」：哪个工具被关闭/收紧，一眼能看到（关闭的标红）。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-21 12:00:00', '0', '2026-10-21 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000016, 'v1.8.2', '配钥匙不再问地址，长对话不再被误判',
  '[{"type":"fix","text":"钥匙管理选了厂商就不用再填接口地址：系统按厂商官方地址自动接入（页面也不再显示这个输入框）；要走中转地址才需要选「自定义」厂商手填，自定义留空会直接提示。"},{"type":"new","text":"修了一个误判：排队高峰时刚说完的回合可能被后台兜底线程抢先标成「已断开」并退回额度，现在真终态写入期间兜底线程会让行。"},{"type":"fix","text":"订阅（积分）租户的回合退款口径与扣费口径对齐：没扣过当日回合的订阅轮不再误退计数。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-21 12:00:00', '0', '2026-10-21 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000017, 'v1.8.1', '引擎升级到 AgentScope 2.0.4，两处暗雷排掉',
  '[{"type":"optimize","text":"AI 引擎底座升级到 AgentScope 2.0.4（对话/思考/工具/心跳/确认卡全链路实测正常），为后续技能与外部工具扩展铺路。"},{"type":"fix","text":"修掉一个暗改道问题：模型接口地址留空的配置行会让对话悄悄发往 OpenAI 官方地址然后超时，现在直接提示「请补全接口地址」，不再干等。"},{"type":"optimize","text":"删除用户数据时框架会话表的清理补上了表名前缀，此前这步清理一直在静默失败重试。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-21 12:00:00', '0', '2026-10-21 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000018, 'v1.8.0', '合规三件套：能打码、能删人、能追责',
  '[{"type":"new","text":"新增「合规中心」页：哪些字段算敏感（手机/邮箱/搜索词…）改成一张清单自己配，配好全站查询自动打码；没有对应权限的账号看不到分数列和逐事件轨迹。"},{"type":"optimize","text":"删得干净也删得放心：输入主体 ID 先预览会删掉多少事件/会话/快照，回显 ID 确认后一次性清干净（含匿名期与冷库归档），删完自动重算统计，每笔删除留审计行。"},{"type":"new","text":"分群导出 CSV 每次留痕（行数/是否明文/文件指纹），明文导出会站内提醒管理员；文件做了防公式注入处理，Excel 打开不会执行公式；采集上报支持站点级签名，伪造和重放会被整批拒收。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000019, 'v1.7.1', '说出需求自动带出技能，助手运行状况看得见',
  '[{"type":"fix","text":"在输入框说出「帮我建任务」「做个模板」这类话，会自动挂上对应技能（胶囊标「自动」），点 × 移除后不再自动加回来。"},{"type":"new","text":"管理页新增「运行健康」卡：回答是正常收尾还是中断、追问有没有生成、为什么没生成，一眼看清（回答太短/超预算不发属正常节流）。"},{"type":"optimize","text":"技能列表改为后台下发：以后加新技能、改触发词不用更新前端。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000020, 'v1.7.0', '数据算错能重跑了，引擎健康看得见了',
  '[{"type":"new","text":"新增「数据运维」页：改错口径或补了历史数据，可以按时间窗回放重算（会话/日报/模型分数可选），支持断点续跑，完成后给出前后对账数字。"},{"type":"new","text":"新增「引擎健康」页：事件延迟、重建耗时、任务失败率、队列积压四项指标一屏看清，越线自动给管理员发站内提醒。"},{"type":"new","text":"数据可以按业务源设保留天数（过期先进冷库再删）；身份合错了能拆开，误并的匿名事件归属会改回去并重算。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000021, 'v1.6.2', '回答更稳了：等再久也不会自动重发，追问等得更久、回来就能看',
  '[{"type":"fix","text":"等回答时后台每 20 秒报一次平安；真等不到时先弹窗问你「继续等还是停止」，不再自动重发（重发会白扣一次额度）。"},{"type":"optimize","text":"追问建议的等待窗口从 30 秒放宽到 90 秒（排队高峰也能等到）；切走再切回页面，建议立刻刷出来。"},{"type":"optimize","text":"追问问题放宽到 24 字以内，表达更完整；后台挑轻快模型时自动避开「思考关不掉」的型号，追问更少悄悄失败。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000022, 'v1.6.1', '助手名字可以自己起，MiniMax 换国内入口',
  '[{"type":"new","text":"管理员可以在「AI 助手管理」里给助手改名，欢迎语、使用说明弹窗会跟着变；清空就恢复默认「SmartDock 小助手」。"},{"type":"optimize","text":"MiniMax 预置换成国内站接口，默认模型升到 M3.1-Flash；选它时页面会提醒先去开放平台申请钥匙再填进来。"},{"type":"optimize","text":"聊天里的「缓存命中」加了说明：解释它为什么更快更省、几成算正常，以及什么时候会暂时变低。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000023, 'v1.6.0', '评分模型有了发布纪律，分群能发通知了',
  '[{"type":"fix","text":"改评分规则不再直接生效：先存草稿、试算看新旧分数对比（含稳定性提示）再发布，出问题可一键回滚到历史版本。"},{"type":"new","text":"分群可以给成员发站内信（发送名单当时冻结、每人只收一次、失败可重试），也能导出成员名单（默认打码）。"},{"type":"new","text":"低分复核支持按天设处理时限，超时的标红置顶；用户列表新增风险筛选和「依据」查看，风险阈值改由后端统一下发。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000024, 'v1.5.3', '登录页品牌与产品窗口再升级，左右两栏重新找平',
  '[{"type":"new","text":"左侧新增 SmartDock 标记；产品窗口补了顶部导航、侧栏图标、评分 +4、事件类型点和趋势 Y 轴，更像真实产品截图。"},{"type":"new","text":"产品窗口在品牌与页脚之间垂直居中，右侧登录卡新增分组线和渐变描边，1440/1366/1024/1920 观感一致。"},{"type":"fix","text":"手机端两个勾选行的点按高度从 20px 提到 32px；390×560 横屏不再多出滚动。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000025, 'v1.5.2', '登录页再细抠一轮：小字更清楚，助手浮层不压字',
  '[{"type":"optimize","text":"左侧产品窗口里的姓名、事件、图表标题放大了一档，顶部能力行和底部说明也提亮了。"},{"type":"fix","text":"「指定单位登录」、版权行和输入框提示文字的对比度提高，1366/1024 下卡片底部不再贴边。"},{"type":"fix","text":"1024 宽度下助手浮层向下让出最后一条事件，不再盖住事件文字。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000026, 'v1.5.1', '登录页换成画像工作台实景，右栏只留登录',
  '[{"type":"fix","text":"左侧改成品牌字标加画像工作台、AI 助手浮层示意，不再用抽象球体和轨道动画。"},{"type":"optimize","text":"右侧删掉口号和「画像 / 助手」两枚标签，只保留登录表单；验证码、记住我、协议勾选、指定单位登录一个不少。"},{"type":"optimize","text":"手机端顶部保留 150px 品牌条；修了「指定单位登录」展开后校验提示压住「记住我」的问题。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000027, 'v1.5.0', '分析中心新增路径、活跃、实时三张视图',
  '[{"type":"new","text":"新标签「路径分析」：看用户从哪页进来、下一步去哪页；「活跃分析」：日活/周活/月活和粘性趋势；「实时看板」：最近 5 分钟的流量脉冲，5 秒自动刷新、可一键暂停。"},{"type":"new","text":"漏斗页新增「流失去向」（流失的访客接下来去了哪）和渠道归因表（不同来源各带来多少转化）；采集页新增「全域总览」，网站、订单、供应链等业务源一屏对账。"},{"type":"fix","text":"修复三处老问题：共享浏览器下访客时间线串台、路径图因回访成环不显示、跨时区电脑上「最近写入」时间显示错误。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000028, 'v1.4.4', '首页几个数字的说法改准了',
  '[{"type":"optimize","text":"画像卡第三个数改为「低分主体」（此前误把低风险人数当成低分人数）。"},{"type":"fix","text":"首页「已提交待审」现在跳到我的待办，不再误跳审核队列；没有分析权限时不再显示「数据分析」按钮。"},{"type":"optimize","text":"手机上历史会话抽屉关闭后，键盘 Tab 不会再钻进去；按 Esc 或点「新会话」会自动收起。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000029, 'v1.4.3', '小屏上的搜索、分页和助手会话又顺了一截',
  '[{"type":"fix","text":"手机上打开顶部搜索，弹窗不再超出屏幕，输入框和结果都能完整操作。"},{"type":"fix","text":"AI 助手会话页在手机上重新占满可用高度，底部不再留一大片空白。"},{"type":"fix","text":"窄屏分页自动收起页码跳转与每页条数；富文本输入在手机上聚焦时不再被整页放大。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000030, 'v1.4.2', '手机上的任务列表和 AI 助手更好用了',
  '[{"type":"optimize","text":"任务管理、我的待办、我的消息、审核队列在手机上改用卡片显示，不用再左右拖动表格。"},{"type":"optimize","text":"AI 助手的确认操作改为在输入框位置弹出，确认前不会误发新消息；取消后还能展开查看当时确认的内容。"},{"type":"fix","text":"AI 助手会话页在手机上新增「历史会话」抽屉，对话区不再被侧栏挤成一条缝；悬浮球的主动提醒改到底部卡片，避免遮挡内容。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000031, 'v1.4.1', '首页换成双引擎驾驶舱，菜单重新分了组',
  '[{"type":"optimize","text":"首页现在能直接一句话问 AI 助手，画像健康、数据脉搏和最近更新一屏看完。"},{"type":"optimize","text":"画像相关页面独立成「画像与分析」菜单，任务中心只留任务相关入口。"},{"type":"optimize","text":"登录页视觉升级，表单更好看也更好点了。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000032, 'v1.4.0', '报表数字更可信了：机器人与外来灌数不再混进统计',
  '[{"type":"new","text":"「数据采集」页新增采集质量卡片：今天拦了多少机器人、拒绝了哪些外来网站、重复上报多少，一目了然。"},{"type":"optimize","text":"网站埋点脚本升级：断网或刷新期间的访问记录先存在浏览器里、网络恢复自动补传，关页面也不丢最后的停留时长。"},{"type":"new","text":"访客可以一键撤回数据采集授权（setConsent(false)），撤回后立即停止采集并清空本地暂存。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-20 12:00:00', '0', '2026-10-20 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000033, 'v1.3.2', '一处小细节修正',
  '[{"type":"fix","text":"带刘海的手机横屏上，小助手面板里的内容不再贴进刘海区域。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-19 12:00:00', '0', '2026-10-19 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000034, 'v1.3.1', '修掉手机和平板上的几个小毛病',
  '[{"type":"optimize","text":"平板竖屏、手机横屏时点输入框，页面不会再突然放大。"},{"type":"fix","text":"小助手的确认按钮遇到网络卡顿失灵的问题已修复，现在可以正常再点。"},{"type":"optimize","text":"手机上从详情返回列表，之前填的筛选条件不会再丢。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-19 12:00:00', '0', '2026-10-19 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000035, 'v1.3', '手机上更好找、更好点、更好读',
  '[{"type":"optimize","text":"手机顶栏保留搜索和消息提醒，不用再为了看未读切回电脑。"},{"type":"optimize","text":"手机底部给手势条留出空间，页面最后一行的按钮不会再被挡住。"},{"type":"fix","text":"点击输入框不再把整页放大，填表时视野保持稳定。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-19 12:00:00', '0', '2026-10-19 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000036, 'v1.2', '画像工作室上线，圈人和看数据更直观',
  '[{"type":"new","text":"新增「画像工作室」：按人群特征自由圈人，名单可随时回看。"},{"type":"new","text":"画像规则和评分可以自己调整，改完马上能看到影响范围。"},{"type":"optimize","text":"画像详情补充了来源与变化记录，判断依据更清楚。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-18 12:00:00', '0', '2026-10-18 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000037, 'v1.1', '小助手能帮你动真数据了',
  '[{"type":"optimize","text":"对着小助手说一句话就能新建任务，真正执行前会先请你确认。"},{"type":"optimize","text":"确认卡、任务表单和模板清单都能在对话里直接填写和修改。"},{"type":"new","text":"回答里的表格和数据可以直接下载成文档，汇报不用再手工整理。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-10-08 12:00:00', '0', '2026-10-08 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

INSERT INTO public.dcs_changelog (log_id, version, title, items, target_tenants, status, publish_time, del_flag, create_time) VALUES (
  9200000000038, 'v1.0', '消息、反馈和审核记录都更好找',
  '[{"type":"optimize","text":"顶部消息提醒一眼看到未读数，点开就能处理。"},{"type":"new","text":"新增问题反馈入口，提交后能看到处理进度。"},{"type":"fix","text":"每一次提交、通过和退回都有记录，退回原因不再丢。"}]'::jsonb,
  (SELECT COALESCE(jsonb_agg(t.tenant_id ORDER BY t.tenant_id), '[]'::jsonb) FROM public.sys_tenant t WHERE t.del_flag = '0' AND t.status = '0'),
  '1', '2026-09-24 12:00:00', '0', '2026-09-24 12:00:00')
ON CONFLICT (version) WHERE (del_flag = '0'::bpchar) DO NOTHING;

-- ===================== 2026-10-25_deepopt_r2_scale_export.sql =====================
-- 2026-10-25 深度优化批 R2: 分析中心 CSV 导出按钮位(菜单 11988, 权限串 analytics:center:export)
-- 可重复执行: ON CONFLICT / NOT EXISTS 幂等
-- 对应 baseline 同步: script/sql/baseline/07_analytics.sql 菜单区

INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark) VALUES
(11988, '数据分析导出', 11986, 15, '', NULL, NULL, '1', '0', 'F', '1', '0', 'analytics:center:export', '#', 103, 1, '2026-10-25 00:00:00', NULL, NULL, '访问明细/自定义分析 CSV 导出')
ON CONFLICT (menu_id) DO NOTHING;

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 1, 11988 WHERE NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 1 AND menu_id = 11988);

-- ===================== 2026-10-26_assistant_menu_memory.sql =====================
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

-- ===================== 2026-10-26_deepopt_b4b_indexes.sql =====================
-- 批4B 归档表查询索引: 主体维度回溯 + 匿名 ID 反查(部分索引)
-- 可重复执行: IF NOT EXISTS。
CREATE INDEX IF NOT EXISTS idx_profile_event_archive_subject
    ON public.profile_event_archive USING btree (tenant_id, source, subject_type, subject_id);
CREATE INDEX IF NOT EXISTS idx_profile_event_archive_anon
    ON public.profile_event_archive (tenant_id, source, anonymous_id) WHERE anonymous_id IS NOT NULL;

-- ===================== 2026-10-26_deepopt_b5b_backfill_kind.sql =====================
-- =====================================================================
-- 批5B 日汇总兜底(2026-10-26): 重建留痕 kind 白名单增加 BACKFILL
-- 每日凌晨 AnalyticsDailyBackfillJob 与手工补数接口共用 backfillDaily,
-- 留痕 kind=BACKFILL; 原 CHECK 未含该值导致留痕插入被吞(非致命, 健康页缺行)。
-- 可重复执行: 先 DROP IF EXISTS 再重建同名约束。
-- =====================================================================

ALTER TABLE public.analytics_rebuild_log DROP CONSTRAINT IF EXISTS ck_analytics_rebuild_kind;
ALTER TABLE public.analytics_rebuild_log
    ADD CONSTRAINT ck_analytics_rebuild_kind CHECK (kind IN ('RECENT', 'MANUAL', 'REPLAY', 'UNMERGE', 'DELETION', 'BACKFILL'));

-- ===================== 2026-10-26_deepopt_r22_engine_admin.sql =====================
-- R2-2 引擎管理页: 画像与分析目录(12000)下新增 C 菜单「引擎管理」
-- 页面内写操作(重算/协议/Schema/灌数)复用既有 11971-11976 按钮权限位, 本迁移只加页面入口。
-- 可重复执行: IF NOT EXISTS 语义 + ON CONFLICT 跳过。
BEGIN;

INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 11989, '引擎管理', 12000, 9, 'engine-admin', 'dcs/profile/admin', '', '1', '0', 'C', '0', '0', 'profile:engine:query', 'setting', 103, 1, '2026-10-26 00:00:00', NULL, NULL, '画像引擎运维: 重算/协议/Schema/灌数(写操作复用 11971-11976 按钮位)'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 11989);

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 1, 11989
WHERE NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 1 AND menu_id = 11989);

COMMIT;

-- ===================== 2026-10-26_deepopt_r24_custom_report.sql =====================
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

-- ===================== 2026-10-26_deepopt_review_fixes.sql =====================
-- 2026-10-26 画像与分析深度优化批·三审回修 DDL
-- 内容: ①backfill 手工入口独立权限位 analytics:backfill:run(菜单 11995, 只授超管角色 1)
-- 说明: 跨租户补数是运维级操作, 不能复用租户级的 analytics:center:edit(普通分析编辑者会被误授全租户遍历)
-- 可重复执行: IF NOT EXISTS + ON CONFLICT

INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 11995, '日汇总补数', 11986, 18, '', NULL, NULL, '1', '0', 'F', '1', '0', 'analytics:backfill:run', '#', 103, 1, '2026-10-26 00:00:00', NULL, NULL, '手工触发跨租户日汇总补数(遍历所有租户)'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 11995);

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 1, 11995
WHERE EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 11995)
ON CONFLICT (role_id, menu_id) DO NOTHING;

-- ===================== 2026-10-27_direction_b1_menu_collapse.sql =====================
-- ============================================================================
-- 画像方向重构 · 第一批：菜单收缩 11 → 3（2026-10-27）
-- 板: 20261027-profile-direction-board.html 题0★D 六件套②
--   一级只留 数据分析/用户画像；其余 5 项收进「引擎与运维」折叠组(12018)；
--   数据运维/引擎健康/引擎管理 三页合一(引擎运维)；低分复核并入用户画像页。
-- 权限不倒退: 被合并页面菜单转隐藏 F 按钮(权限串/角色绑定全保留)，
--   合一页 tab 与画像页复核 drawer 用 v-hasPermi 按同一权限串控制。
-- 幂等可重复执行。
-- ============================================================================

-- 1) 新目录「引擎与运维」12018(12011-12017 已被合规按钮占用)
INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, remark)
VALUES (12018, '引擎与运维', 12000, 3, 'engine-ops', NULL, NULL, '1', '0', 'M', '0', '0', '', 'setting', 103, 1, now(), '画像方向重构: 工作室/RFM/分群/合规/引擎运维收纳为折叠组')
ON CONFLICT (menu_id) DO UPDATE SET menu_name = EXCLUDED.menu_name, parent_id = EXCLUDED.parent_id, order_num = EXCLUDED.order_num, path = EXCLUDED.path, update_time = now();

-- 2) 四页挪进折叠组
UPDATE public.sys_menu SET parent_id = 12018, order_num = 1, update_time = now() WHERE menu_id = 11979; -- 画像工作室
UPDATE public.sys_menu SET parent_id = 12018, order_num = 2, update_time = now() WHERE menu_id = 11980; -- 画像RFM
UPDATE public.sys_menu SET parent_id = 12018, order_num = 3, update_time = now() WHERE menu_id = 11981; -- 画像分群
UPDATE public.sys_menu SET parent_id = 12018, order_num = 4, update_time = now() WHERE menu_id = 12010; -- 合规中心

-- 3) 数据运维 → 引擎运维(三页合一宿主)，挪进折叠组
UPDATE public.sys_menu SET parent_id = 12018, order_num = 5, menu_name = '引擎运维',
  remark = '画像方向重构: 数据运维+引擎健康+引擎管理三页合一', update_time = now()
WHERE menu_id = 12001;

-- 4) 引擎健康/引擎管理 菜单转隐藏按钮挂到 12001 下(权限串与角色绑定原样保留)
UPDATE public.sys_menu SET menu_type = 'F', parent_id = 12001, order_num = 20, path = '', component = NULL,
  menu_name = '引擎健康(页内)', visible = '1', update_time = now() WHERE menu_id = 12002;
UPDATE public.sys_menu SET menu_type = 'F', parent_id = 12001, order_num = 21, path = '', component = NULL,
  menu_name = '引擎管理(页内)', visible = '1', update_time = now() WHERE menu_id = 11989;

-- 5) 低分复核 菜单转隐藏按钮挂到 9070 用户画像下(drawer 入口用同一权限串)
UPDATE public.sys_menu SET menu_type = 'F', parent_id = 9070, order_num = 22, path = '', component = NULL,
  menu_name = '低分复核(画像页内)', visible = '1', update_time = now() WHERE menu_id = 11982;

-- 6) 一级排序: 数据分析 1 / 用户画像 2 / 折叠组 3
UPDATE public.sys_menu SET order_num = 1, update_time = now() WHERE menu_id = 11986;
UPDATE public.sys_menu SET order_num = 2, update_time = now() WHERE menu_id = 9070;

-- 7) 折叠组目录授权 = 原子菜单绑定角色并集(适配任意环境的既有绑定)
--    并集含被合并的 12002/11989(引擎健康/管理); 11982(低分复核)转按钮挂 9070,
--    9070 本身是显式菜单不需要并集, 但要给「只授过 11982 没授过 9070」的角色补宿主
--    (三审 glm53 P1: 少这半句, 这类角色权限串还在但画像页路由不可达, 复核抽屉静默失联)
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 12018 FROM public.sys_role_menu rm
WHERE rm.menu_id IN (11979, 11980, 11981, 12001, 12010, 12002, 11989)
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = 12018);
-- 转隐藏按钮的三页: 确保其权限串挂靠的父菜单对授过它们的角色可达
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 12001 FROM public.sys_role_menu rm
WHERE rm.menu_id IN (12002, 11989)
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = 12001);
-- 低分复核(11982)→用户画像(9070) 宿主保证: 只授过复核没授过画像页的角色补画像页入口
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT DISTINCT rm.role_id, 9070 FROM public.sys_role_menu rm
WHERE rm.menu_id = 11982
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu x WHERE x.role_id = rm.role_id AND x.menu_id = 9070);

-- 8) 清理已删角色残留的 12018 绑定(三审 dspro 补充: 并集 INSERT 不会回收死角色的行)
DELETE FROM public.sys_role_menu rm
WHERE rm.menu_id = 12018
  AND NOT EXISTS (SELECT 1 FROM public.sys_role r WHERE r.role_id = rm.role_id);

-- ===================== 2026-10-27_direction_b2_source_config.sql =====================
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

-- ===================== 2026-10-28_singlesource_m1_closeout.sql =====================
-- ============================================================
-- 单源重构 M1 批·批末收口迁移 (2026-10-28)
-- 可重复执行: 全部 IF NOT EXISTS / WHERE NOT EXISTS / 带条件 DELETE
-- ============================================================

BEGIN;

-- ------------------------------------------------------------
-- ① 身份链接补两个查询索引(批末回修)
--   a) 跨类型身份桥 selectLinkShared 按 (tenant,source,identifier_type,identifier_value)
--      取行——唯一键改含 subject_type 后(B 片 identity_scope), 旧四列前缀不再命中;
--   b) countStrongLinks 按 (tenant,source,subject_id) 计强标识(不分类型),
--      现有索引第三列都是 subject_type, 前缀同样不命中。
-- ------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_profile_identity_link_identifier
    ON public.profile_identity_link (tenant_id, source, identifier_type, identifier_value);

CREATE INDEX IF NOT EXISTS idx_profile_identity_link_strong
    ON public.profile_identity_link (tenant_id, source, subject_id)
    WHERE status = 'ACTIVE';

-- ------------------------------------------------------------
-- ② 登录名→user_id 回查的定位保障: (tenant_id, user_name) 软删过滤唯一索引
--   服务层查重只挡单请求; 并发注册同名用户竞态由库层兜底。
--   前置检重: 上线前已核 smartdock_shots / smartdock_scratch 两库均 0 重复;
--   若目标库存在重复, 建唯一索引会直接报错中止——先人工合并同名账号再重跑。
-- ------------------------------------------------------------

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_tenant_name
    ON public.sys_user (tenant_id, user_name)
    WHERE del_flag = '0';

-- ------------------------------------------------------------
-- ③ 孤儿按钮位清理: 12019「来源改名停用」(profile:source:edit)
--   其唯一消费方(引擎管理「来源管理」页签)已随 D 片单源化删除,
--   后端写端点亦已批末退役——菜单行与角色绑定一并移除, 防止重跑/重排 SQL
--   时 04_role_menu 的「跟随 11989 授权」块把权限再悄悄发出去。
-- ------------------------------------------------------------

DELETE FROM public.sys_role_menu WHERE menu_id = 12019;
DELETE FROM public.sys_menu WHERE menu_id = 12019 AND perms = 'profile:source:edit';

COMMIT;

-- ===================== 2026-10-28_singlesource_m1_engine_tab.sql =====================
-- 单源重构第一批·题4: 测试发送权限错位修复(完整版)
-- 现状两个问题(板上题4 实证 + 落库核对新发现):
--   ① 引擎管理页签入口 profile:engine:query(11989) 只授角色1, 租户管理员进不去测试发送页;
--   ② 发布按钮权限 profile:event:publish 原先没有任何菜单按钮挂载, 非超管(通配)永远看不到按钮。
-- 修复: 新增按钮位 12020(profile:event:publish, 挂引擎运维 12001 下) 授角色1+6;
--       角色6(租户管理员)补 12001(引擎运维页)+11989(引擎管理页签)。
-- 边界: 角色6 本就持有 schema/协议/维度/规则/重算等按钮权限但此前无页面入口(11971 recalc:run 同样被埋),
--       本次只是把已授权限的页面入口补齐, 未新增任何写权限; 来源改(12019)不随此开放。
-- 可重复执行: NOT EXISTS 跳过
BEGIN;

-- 1) 补挂发布按钮权限位
INSERT INTO public.sys_menu (menu_id, menu_name, parent_id, order_num, path, component, query_param, is_frame, is_cache, menu_type, visible, status, perms, icon, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 12020, '测试发送(引擎管理页内)', 12001, 23, '', NULL, NULL, '1', '0', 'F', '1', '0', 'profile:event:publish', '#', 103, 1, '2026-10-28 00:00:00', NULL, NULL, '单源重构第一批(题4): 补挂发布按钮权限位, 与页签入口(11989)一并对齐'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_menu WHERE menu_id = 12020);

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 1, 12020 WHERE NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 1 AND menu_id = 12020);

-- 2) 租户管理员: 引擎运维页 + 引擎管理页签 + 发布按钮
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 6, 12001 WHERE EXISTS (SELECT 1 FROM public.sys_role WHERE role_id = 6)
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 6 AND menu_id = 12001);

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 6, 11989 WHERE EXISTS (SELECT 1 FROM public.sys_role WHERE role_id = 6)
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 6 AND menu_id = 11989);

INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT 6, 12020 WHERE EXISTS (SELECT 1 FROM public.sys_role WHERE role_id = 6)
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu WHERE role_id = 6 AND menu_id = 12020);

COMMIT;

-- ===================== 2026-10-28_singlesource_m1_force_enable_unified_source.sql =====================
-- 单源重构 M1 D 片(2026-10-28): 「来源管理」页签已随单源口径退役, 页面上不再存在
-- 任何启用入口。若租户曾用旧界面把统一源 dcs 停用(enabled='0'), 升级后画像/分析
-- 会拿空源清单且无处恢复——存量停用行一律恢复启用。可重复执行(第二遍 0 行)。
UPDATE profile_source_config
SET enabled    = '1',
    update_time = now()
WHERE source = 'dcs'
  AND enabled = '0';

-- ===================== 2026-10-28_singlesource_m1_identity_scope.sql =====================
-- 单源重构第一批·题0-丁: 身份链接按主体类型隔离(强标识), 匿名桥保持跨类型单行
-- 现状: 唯一键 (tenant_id, source, identifier_type, identifier_value) 不含 subject_type,
--       员工(user)与顾客(member)同号标识(如 user_id=123)会互相串号甚至互改挂靠(板上题0-丁实证)。
-- 修复: 唯一键与强标识查询/计数/登记全部加 subject_type(各命名空间独立);
--       anonymous_id 走 selectLinkShared 不分类型(身份桥), 改挂沿用既有行 subject_type 保单行。
-- 前置检重(应为 0 行, 开发库已核): SELECT tenant_id,source,identifier_type,identifier_value,
--   count(DISTINCT subject_type) FROM profile_identity_link GROUP BY 1,2,3,4 HAVING count(DISTINCT subject_type)>1;
BEGIN;
ALTER TABLE profile_identity_link DROP CONSTRAINT IF EXISTS uk_profile_identity_link;
ALTER TABLE profile_identity_link
    ADD CONSTRAINT uk_profile_identity_link
    UNIQUE (tenant_id, source, subject_type, identifier_type, identifier_value);
COMMIT;

-- ===================== 2026-10-28_singlesource_m1_mainview_indexes.sql =====================
-- 单源重构第一批 B 片三审回修: 画像主视图/跨源查询形态的专用索引。
-- 背景: 主视图(c74523a)与占位守卫读路径都不带 source 条件, 而既有索引全部以
-- source 为第二列, 前缀无法命中——大租户下退化为顺序扫描。本文件补三条 partial 索引,
-- 与既有索引不重叠(过滤条件/列序不同)。可重复执行(IF NOT EXISTS), 包事务。
BEGIN;

-- 快照按 (租户, 类型, 主体) 直取最新——主视图 LATERAL / scan() / 模型主体页共用形态
CREATE INDEX IF NOT EXISTS idx_profile_snap_xsrc
    ON public.profile_score_snapshot USING btree
    (tenant_id, subject_type, subject_id, calc_time DESC, snapshot_id DESC)
    WHERE (del_flag = '0'::bpchar);

-- 复核队列"该主体是否有待复核"探测——主视图/守卫销案的 EXISTS 形态
CREATE INDEX IF NOT EXISTS idx_review_pending_xsrc
    ON public.profile_review_queue USING btree (tenant_id, subject_type, subject_id)
    WHERE (status = 'PENDING'::text AND del_flag = '0'::bpchar);

-- 主体登记跨源聚合——主视图 GROUP BY subject_id 与身份桥按主体定位的形态
CREATE INDEX IF NOT EXISTS idx_profile_subject_xsrc
    ON public.profile_subject USING btree (tenant_id, subject_type, subject_id)
    WHERE (del_flag = '0'::bpchar);

COMMIT;

-- ===================== 2026-10-28_singlesource_m1_mark_legacy_placeholders.sql =====================
-- 单源重构 M1 D 片(消 B 片夜审登记的存量 P2): 97ad78f 之前的未配置维度快照
-- 形状是 '{}' 且总分恒 50——读侧(画像主列表/依据抽屉/总览 KPI)按 _noDimensions
-- 标记识别占位, 不补标记这些行会继续冒充真分数、其引发的 PENDING 复核永远挂队。
-- 与引擎侧 supersedeIfPending 同列约定(status/review_note/reviewed_at/update_time)。可重复执行。
-- 批末终审(glm53 P2)登记: 另一残留形状「维度非空但全跳过」(STRATEGY 维度 bean 失效期的
-- 老快照, detail 含 "skipped":true 条目而非 '{}')不在本迁移覆盖——两库实测 0 行,
-- 且全跳过检测需逐维度 JSON 判定, 复杂度大于收益。升级前可自查:
--   SELECT count(*) FROM profile_score_snapshot WHERE del_flag='0' AND total_score=50
--     AND dimension_detail::text LIKE '%"skipped":true%' AND NOT dimension_detail::text LIKE '%_noDimensions%';
-- 非 0 的库把这些行的 dimension_detail 手工置 '{"_noDimensions": true}'::jsonb 即可归一。

UPDATE profile_score_snapshot
   SET dimension_detail = '{"_noDimensions": true}'::jsonb
 WHERE del_flag = '0'
   AND total_score = 50
   AND dimension_detail = '{}'::jsonb;

UPDATE profile_review_queue q
   SET status = 'SUPERSEDED',
       review_note = '未配置打分口径的占位分数，补标记后自动销案',
       reviewed_at = CURRENT_TIMESTAMP,
       update_time = CURRENT_TIMESTAMP
  FROM (
    SELECT DISTINCT ON (tenant_id, source, subject_type, subject_id)
           tenant_id, source, subject_type, subject_id, dimension_detail
      FROM profile_score_snapshot
     WHERE del_flag = '0'
     ORDER BY tenant_id, source, subject_type, subject_id, calc_time DESC, snapshot_id DESC
  ) latest
 WHERE q.tenant_id = latest.tenant_id
   AND q.source = latest.source
   AND q.subject_type = latest.subject_type
   AND q.subject_id = latest.subject_id
   AND latest.dimension_detail @> '{"_noDimensions": true}'::jsonb
   AND q.status = 'PENDING'
   AND q.del_flag = '0';

-- ===================== 2026-10-28_singlesource_m1_merge_legacy_sources.sql =====================
-- 单源重构 M1 E 片(2026-10-28): 存量非 dcs 源的身份链接与首见记录合并到统一源 dcs。
-- 背景: B 片三席 dspro P1——单源化后 identity_link/首见表按 (tenant, source) 键控,
-- 旧源(web/b10src 等)的行成为孤儿: 同一用户键的首见丢档 → 老同事被当新用户;
-- 身份链接断 → 匿名到登录的归并链失效。
-- 合并范围刻意收窄: 只搬「身份与首见」两表; 事件/会话/日汇总按源冻结不合并
-- (合并=同一个人两源计数翻倍), 旧源分析数据仍可按源查询, 发布说明写明「旧源派生数据冻结」。
-- 三席回修: ①首见只搬「在 dcs 事件账本里真实出现过」的键——web 匿名 cookie 键没有 dcs 事件,
--   搬过去会以幽灵身份混进「新同事」分母; ②ON CONFLICT 钉到业务唯一键——不带目标的 DO NOTHING
--   连主键撞车也静默吞行(link_id 手工编号与雪花并发同毫秒可撞), 钉死后 PK 撞车变成显式报错。
-- 执行须知: 建议停机窗口执行(避免与运行中的应用并发写 identity_link);
-- 执行后对 dcs 源跑一次重建汇总(POST /analytics/rebuild?source=dcs&days=365), 否则会话/日汇总仍是旧口径。
-- 可重复执行: 两段均幂等(冲突保留 dcs 原行 / 首见只取更早)。

-- 1) 用户首见: 只搬在 dcs 事件账本真实出现过的键, 已存在时保留更早时间(首次使用就该取最早一次)
INSERT INTO analytics_visitor_first_seen (tenant_id, source, visitor_key, first_seen)
SELECT fs.tenant_id, 'dcs', fs.visitor_key, fs.first_seen
FROM analytics_visitor_first_seen fs
WHERE fs.source <> 'dcs'
  AND EXISTS (SELECT 1 FROM profile_event pe
      WHERE pe.tenant_id = fs.tenant_id AND pe.source = 'dcs' AND pe.del_flag = '0'
        AND COALESCE(NULLIF(pe.anonymous_id, ''), pe.subject_id) = fs.visitor_key)
ON CONFLICT (tenant_id, source, visitor_key)
DO UPDATE SET first_seen = LEAST(analytics_visitor_first_seen.first_seen, EXCLUDED.first_seen);

-- 2) 身份链接: 复制到 dcs(同键已存在则保留 dcs 原行)。
-- link_id 无库内序列(应用侧雪花)——用 max+row_number 生成不与存量冲突的新 id
INSERT INTO profile_identity_link (link_id, tenant_id, source, subject_type, subject_id,
    identifier_type, identifier_value, identifier_hash, confidence, is_secure, status,
    first_seen, last_seen, create_time, update_time)
SELECT (SELECT COALESCE(max(l2.link_id), 0) FROM profile_identity_link l2) + row_number() OVER (ORDER BY link_id),
       tenant_id, 'dcs', subject_type, subject_id,
       identifier_type, identifier_value, identifier_hash, confidence, is_secure, status,
       first_seen, last_seen, now(), now()
FROM profile_identity_link
WHERE source <> 'dcs'
ON CONFLICT (tenant_id, source, subject_type, identifier_type, identifier_value) DO NOTHING;

-- ===================== 2026-10-28_singlesource_m1_protocol_events.sql =====================
-- 单源重构 M1 E 片三席回修(2026-10-28): 接入协议清单补两个自监控事件。
-- 背景(dspro P2): user.login 与 data.audited 是采用度指标的取数口径, 但协议文档(v1.0.0)的
-- events 清单没有它们——管理员在「协议与 Schema」页看不到, 配维度/规则时无从下手。
-- 协议表每源只允许一个 PUBLISHED 版本(uk_profile_protocol_published 部分唯一):
-- 先把 v1.0.0 转SUPERSEDED 再发布 v1.1.0(保留旧版本可追溯)。幂等可重跑:
-- 二次执行时 UPDATE 命中 0 行、INSERT 撞 (tenant,source,version) 唯一键跳过。
-- 批末回修(dspro P2-5): 原版只处理默认租户 000000, 多租户部署的非默认租户升级后
-- 仍停在 v1.0.0(读侧按当前租户查、无 000000 回退)——改为对每个已配置过 dcs 协议的
-- 租户逐个派生; 主键用 max(id)+row_number() 单调派发, 重跑时先撞 (tenant,source,version)
-- 唯一键被 DO NOTHING 吸收。

UPDATE public.profile_protocol
SET status = 'SUPERSEDED', update_time = now()
WHERE source = 'dcs' AND version = '1.0.0'
  AND status = 'PUBLISHED' AND del_flag = '0';

WITH manifest AS (
    SELECT $manifest$
    {
      "apiVersion": "profile.protocol/v1",
      "kind": "ProfileManifest",
      "metadata": {
        "namespace": "dcs",
        "version": "1.1.0",
        "title": "任务填报画像",
        "description": "内置演示业务源：登录、任务发布、提交、审核（通过/退回记提交人，完成审核记审核人）、任务关闭。"
      },
      "subjectTypes": [
        {"name": "user", "displayName": "用户", "idField": "userId"}
      ],
      "events": [
        {"name": "user.login", "displayName": "登录成功", "subjectTypes": ["user"]},
        {"name": "task.published", "displayName": "任务发布", "subjectTypes": ["user"]},
        {"name": "task.closed", "displayName": "任务关闭", "subjectTypes": ["user"]},
        {"name": "data.submitted", "displayName": "数据提交", "subjectTypes": ["user"]},
        {"name": "data.audit_passed", "displayName": "审核通过(记提交人)", "subjectTypes": ["user"]},
        {"name": "data.audit_rejected", "displayName": "审核退回(记提交人)", "subjectTypes": ["user"]},
        {"name": "data.audited", "displayName": "完成审核(记审核人)", "subjectTypes": ["user"]}
      ],
      "dimensions": [
        {"name": "completion", "displayName": "完成率", "operator": "RATE", "scoreMax": 100},
        {"name": "response", "displayName": "响应时效", "operator": "AVG", "scoreMax": 100},
        {"name": "quality", "displayName": "填报质量", "operator": "RATE", "scoreMax": 100},
        {"name": "negative", "displayName": "负面指标", "operator": "COUNT", "scoreMax": 100}
      ],
      "metrics": [
        {"name": "approval_rate", "displayName": "通过率", "operator": "RATE"},
        {"name": "avg_response_hours", "displayName": "平均响应小时", "operator": "AVG"}
      ],
      "rules": [
        {
          "name": "v1-builtin",
          "displayName": "内置权重",
          "weights": {"completion": 0.35, "response": 0.25, "quality": 0.25, "negative": 0.15}
        }
      ],
      "scoreModels": [],
      "segments": [],
      "identity": {
        "strong": ["user_id"],
        "weak": ["anonymous_id", "device_id", "email_sha256", "phone_sha256"]
      }
    }
    $manifest$::jsonb AS doc
),
targets AS (
    SELECT DISTINCT tenant_id FROM public.profile_protocol
    WHERE source = 'dcs' AND del_flag = '0'
),
ids AS (
    SELECT tenant_id,
           (SELECT coalesce(max(protocol_id), 9600) FROM public.profile_protocol)
             + row_number() OVER (ORDER BY tenant_id) AS protocol_id
    FROM targets
)
INSERT INTO public.profile_protocol
    (protocol_id, tenant_id, source, version, status, manifest, checksum,
     published_at, published_by, create_dept, create_by, create_time)
SELECT i.protocol_id, i.tenant_id, 'dcs', '1.1.0', 'PUBLISHED', doc,
       encode(sha256(convert_to(doc::text, 'UTF8')), 'hex'),
       '2026-10-28 00:00:00', 1, 103, 1, '2026-10-28 00:00:00'
FROM ids i CROSS JOIN manifest
ON CONFLICT (tenant_id, source, version) DO NOTHING;

-- ===================== 2026-10-28_singlesource_m1_scenario_templates.sql =====================
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

-- ===================== 2026-10-28_singlesource_m1_score_subject_type.sql =====================
-- 单源重构第一批 B 片(题0-乙): 评分配置三表加 subject_type——员工/会员各一套维度/权重/模型
-- 可重复执行(IF NOT EXISTS)。存量行经 DEFAULT 全部落 'user'(与事件主体缺省一致);
-- 唯一键加列重建: 旧键保证加列前无重复, 新键只可能更宽, DROP+ADD 不会撞唯一冲突。
-- B 片三审回修: 包事务——DROP 与 CREATE 非原子时, 中途失败会让库停在唯一性保护真空。
BEGIN;

ALTER TABLE public.profile_score_dimension ADD COLUMN IF NOT EXISTS subject_type character varying(32) DEFAULT 'user'::character varying NOT NULL;
ALTER TABLE public.profile_score_rule      ADD COLUMN IF NOT EXISTS subject_type character varying(32) DEFAULT 'user'::character varying NOT NULL;
ALTER TABLE public.profile_score_model     ADD COLUMN IF NOT EXISTS subject_type character varying(32) DEFAULT 'user'::character varying NOT NULL;

DROP INDEX IF EXISTS public.uk_profile_score_dimension;
CREATE UNIQUE INDEX IF NOT EXISTS uk_profile_score_dimension
    ON public.profile_score_dimension USING btree (tenant_id, source, subject_type, dim_code)
    WHERE (del_flag = '0'::bpchar);

DROP INDEX IF EXISTS public.uk_profile_rule_published;
CREATE UNIQUE INDEX IF NOT EXISTS uk_profile_rule_published
    ON public.profile_score_rule USING btree (tenant_id, source, subject_type)
    WHERE ((status = 'PUBLISHED'::text) AND (del_flag = '0'::bpchar));

DROP INDEX IF EXISTS public.uk_profile_rule_draft;
CREATE UNIQUE INDEX IF NOT EXISTS uk_profile_rule_draft
    ON public.profile_score_rule USING btree (tenant_id, source, subject_type)
    WHERE ((status = 'DRAFT'::text) AND (del_flag = '0'::bpchar));

DROP INDEX IF EXISTS public.uk_profile_model_code;
CREATE UNIQUE INDEX IF NOT EXISTS uk_profile_model_code
    ON public.profile_score_model USING btree (tenant_id, source, subject_type, model_code)
    WHERE (del_flag = '0'::bpchar);

COMMIT;

-- ===================== 2026-10-28_singlesource_m1_seen_backfill.sql =====================
-- 单源重构 M1 E 片三席回修(2026-10-28): 首见物化表回填 + 幽灵行清理 + 用户键表达式索引。
-- 背景(dspro P1): 首见表只有「事件写入时增量 upsert」一个写入点——
-- C 片期间自监控事件被守卫挡在首见表外, E 片守卫反转后没有任何代码补这批行,
-- 升级当天存量老同事会因首见表无行而被当成「新同事」; 反向, 删除事件/演示数据清理
-- 不回删首见行, 留下无事件支撑的幽灵行混进新同事分母。
-- 文件名以 seen_backfill 排在 merge_legacy_sources 之后: 清理段要能收掉合并段可能引入的幽灵行。
-- 可重复执行: 三段均幂等。执行后建议对 dcs 源跑一次重建汇总。

-- 1) 回填: 首见=该用户键在事件账本里的最早一次(含自监控), 只降不升
INSERT INTO analytics_visitor_first_seen (tenant_id, source, visitor_key, first_seen)
SELECT tenant_id, source, COALESCE(NULLIF(anonymous_id, ''), subject_id), min(occurred_time)
FROM profile_event
WHERE del_flag = '0'
GROUP BY 1, 2, 3
ON CONFLICT (tenant_id, source, visitor_key)
DO UPDATE SET first_seen = LEAST(analytics_visitor_first_seen.first_seen, EXCLUDED.first_seen);

-- 2) 用户键表达式索引(dspro P1): 采用度查询(周活/两步上手/首周激活)与按人取事件都走
--    COALESCE(NULLIF(anonymous_id,''), subject_id) 用户键, 活表此前无该键任何索引——
--    大租户首屏 KPI 会退化成逐行过滤。表达式与读侧逐字一致(空格不影响 PG 解析树匹配)。
-- 同名陷阱: 2026-10-25 起的存量库已带旧版 idx_profile_event_visitor(anonymous_id 键)，
-- CREATE INDEX IF NOT EXISTS 只看名字不看定义——不先 DROP 的话新表达式索引会静默跳过，
-- 大租户采用度查询继续全表扫。DROP 后立即重建，重复执行结果不变。
-- (批末 dspro P2-4: 本段排在幽灵行清理之前——清理段的 NOT EXISTS 按 COALESCE 用户键比对,
--  要吃到这里刚建好的表达式索引, 否则大租户逐行全表扫)
DROP INDEX IF EXISTS idx_profile_event_visitor;
CREATE INDEX idx_profile_event_visitor
    ON public.profile_event (tenant_id, source,
        (COALESCE(NULLIF(anonymous_id, ''), subject_id)), event_name, occurred_time)
    WHERE del_flag = '0';

-- 3) 清理: 删掉无事件支撑的幽灵行(测试/演示数据删了事件但首见残留; 旧合并段搬来的无 dcs 事件键)
DELETE FROM analytics_visitor_first_seen fs
WHERE NOT EXISTS (SELECT 1 FROM profile_event pe
    WHERE pe.tenant_id = fs.tenant_id AND pe.source = fs.source AND pe.del_flag = '0'
      AND COALESCE(NULLIF(pe.anonymous_id, ''), pe.subject_id) = fs.visitor_key);

-- ===================== 2026-10-28_singlesource_m1_self_monitor.sql =====================
-- 单源重构 C 片(题1): 引擎配置加自监控总开关。
-- '1'=记录本产品用户登录与站内核心操作画像事件(默认, 静默发布不触发打分/汇总);
-- '0'=停用(画像页同屏开关, D 片出 UI; 只停新增, 已有数据保留)。可重复执行。
BEGIN;

ALTER TABLE public.profile_engine_config
    ADD COLUMN IF NOT EXISTS self_monitor char(1) DEFAULT '1'::bpchar NOT NULL;

-- 三审回修: 与同表 event_archive 等开关列对齐补 CHECK, 拒直连脏值
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_profile_engine_self_monitor') THEN
        ALTER TABLE public.profile_engine_config
            ADD CONSTRAINT ck_profile_engine_self_monitor CHECK (self_monitor IN ('0', '1'));
    END IF;
END $$;

COMMIT;

-- ===================== 2026-10-28_skill_pg_5b.sql =====================
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

-- ===================== 2026-10-30_assistant_title_fragment_cleanup.sql =====================
-- 2026-10-30 题3：AI 会话标题残片存量清洗
-- 背景：模型流式输出被截断时，标题侧车会把半截 JSON（如 {"title":"按周复盘适合的）当标题收下，
--       写回走 updateTitleIfMatch 的 CAS，脏标题一旦落库就再也不会被清洗逻辑碰到（永不自愈）。
--       写入端已在 AssistantTitleService.cleanTitle 堵住（JSON 残片直接放弃），本脚本只清历史残留。
-- 判据：只认「结构性残片」——以 {" 开头的对象、以 [{ 开头的数组，或正文含 "title" 键。
--       注意不能用「以 [ 开头」一刀切：重命名接口允许任意 1-60 字标题，
--       用户手写的 [草稿] 周报 / [重要] 客户投诉 会被无差别清空（三审 P1 实锤）。
--       同理裸引号开头的中文标题（"重要"客户）也不在清洗范围内——宁可留一条脏标题，
--       也不能不可逆地删掉用户自己写的东西。
-- 处置：先整表备份命中行，再置 NULL（不是改写成人话）；前端按「（未命名会话·MM-DD）」兜底展示。
-- 可重复执行：清洗后不再命中，二次执行影响 0 行；备份表已存在则跳过建表。
-- 2026-11-01 补（全量重放会漏备）：只靠上面那句 CTAS，备份表已存在时 SELECT 不再执行，
--       而随后的 UPDATE 仍把新命中行置 NULL —— 这些行没有任何备份。这里补一段与
--       2026-10-31 那份完全相同的「每次先补备份、按 id+标题去重」的 INSERT，谁先跑都不漏。
--       备份表是 CTAS 建的表（本份带命中行、10-31 那份建空表），没有主键，防重复全靠下面
--       INSERT 的 NOT EXISTS（不是撞键报错）。并发同时跑两份脚本的极端窗口仍可能插出重复备份行
--       （只多备份、不影响清洗正确性），属已知取舍。
-- 事务：整段包在 BEGIN/COMMIT 里——psql 默认遇错继续，若不包事务，备份 INSERT 失败后 UPDATE 仍会把行置 NULL，
--       又回到「清了不备」。包上之后任一句失败即整体回滚。

BEGIN;

CREATE TABLE IF NOT EXISTS public.dcs_assistant_conversation_title_bak_20261030 AS
SELECT id, tenant_id, thread_id, title, update_time
  FROM public.dcs_assistant_conversation
 WHERE title IS NOT NULL
   AND (btrim(title) LIKE '{"%'
        OR btrim(title) LIKE '[{%'
        OR title ~* '"title"\s*:');

INSERT INTO public.dcs_assistant_conversation_title_bak_20261030 (id, tenant_id, thread_id, title, update_time)
SELECT c.id, c.tenant_id, c.thread_id, c.title, c.update_time
  FROM public.dcs_assistant_conversation c
 WHERE c.title IS NOT NULL
   AND (btrim(c.title) LIKE '{"%'
        OR btrim(c.title) LIKE '[{%'
        OR c.title ~* '"title"\s*:')
   AND NOT EXISTS (
         SELECT 1
           FROM public.dcs_assistant_conversation_title_bak_20261030 b
          WHERE b.id = c.id
            AND b.title IS NOT DISTINCT FROM c.title);

UPDATE public.dcs_assistant_conversation
   SET title = NULL
 WHERE title IS NOT NULL
   AND (btrim(title) LIKE '{"%'
        OR btrim(title) LIKE '[{%'
        OR title ~* '"title"\s*:');

COMMIT;

-- ===================== 2026-10-30_drop_legacy_sys_notice_seed.sql =====================
-- 2026-10-30 题6：删除框架自带的 2018 年演示公告
-- 背景：RuoYi 模板在 sys_notice 里塞了两条假数据（「温馨提醒：2018-07-01 新版本发布啦」/
--       「维护通知：2018-07-01 系统凌晨维护」）。开源用户第一次点开「系统支持 → 公告」
--       看到的就是这两条 8 年前的模板文案，像系统没装好。基线已同步删除（02_reference.sql）。
-- 判据：同时满足「id 是模板自带的 1/2」+「标题含 2018-07-01」+「默认租户」才删——
--       只按 id 删会误伤真实环境里自己建的 1/2 号公告。
-- 连带：sys_notice_read 没有外键，公告删了读态会留下孤儿行（演示库实测 1 行），
--       同步清掉「对应公告已不存在」的读态。
-- 可重复执行：删过就不再有命中行。

DELETE FROM public.sys_notice
 WHERE notice_id IN (1, 2)
   AND tenant_id = '000000'
   AND notice_title LIKE '%2018-07-01%';

DELETE FROM public.sys_notice_read r
 WHERE r.notice_id IN (1, 2)
   AND NOT EXISTS (SELECT 1 FROM public.sys_notice n WHERE n.notice_id = r.notice_id);

-- ===================== 2026-10-31_assistant_skill_usage.sql =====================
-- 2026-10-31 题2 案3：技能使用统计（只记次数与最近一次，不做调用明细归档）
-- 背景：管理页技能库要能看见「这个技能到底有没有人用」，避免凭感觉删改技能。
-- 形状：按 (tenant_id, skill_id) 唯一，一技一行；内置技能没有在线行也能统计。
-- 可重复执行：IF NOT EXISTS 建表/索引；upsert 由应用侧 ON CONFLICT 完成。

CREATE TABLE IF NOT EXISTS public.dcs_assistant_skill_usage (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    skill_id character varying(64) NOT NULL,
    use_count bigint DEFAULT 0 NOT NULL,
    last_used_at timestamp without time zone,
    update_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT dcs_assistant_skill_usage_pk PRIMARY KEY (id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_dcs_assistant_skill_usage_tenant
    ON public.dcs_assistant_skill_usage (tenant_id, skill_id);

COMMENT ON TABLE public.dcs_assistant_skill_usage IS '助手技能使用统计（题2 案3）：一技一行，use_count=成功取用次数，last_used_at=最近一次';

-- ===================== 2026-10-31_assistant_title_backup_idempotent.sql =====================
-- 2026-10-31 题9：把「先备份再置 NULL」补成真正幂等的备份
-- 背景：2026-10-30 那份迁移用 `CREATE TABLE IF NOT EXISTS … AS SELECT` 建备份表。
--       备份表已存在时 SELECT 不再执行，而随后的 UPDATE 仍会把新命中的行置 NULL ——
--       这些行没有任何备份，与脚本第 10 行「先整表备份命中行，再置 NULL」的注释不一致。
--       正常写入路径已被 AssistantTitleService.cleanTitle 堵住，只有「手动重命名标题里带
--       "title": 字面量」这种极端输入会漏；本脚本把语义补齐，不动已执行过的 10-30 那份。
-- 处置：①备份表若不存在，按与 10-30 迁移完全相同的 5 列形状建一张空表（列形状必须一致，否则下方 INSERT 会报列不存在）；
--       ②每次执行先把命中行补进备份表（按 id + title 去重，重复执行不长行；同一会话后来又被弄脏会补一条新备份）；
--       ③再把命中行置 NULL。
-- 可重复执行：二次执行 INSERT 0 行、UPDATE 0 行。

CREATE TABLE IF NOT EXISTS public.dcs_assistant_conversation_title_bak_20261030 AS
SELECT id, tenant_id, thread_id, title, update_time
  FROM public.dcs_assistant_conversation
 WHERE false;

INSERT INTO public.dcs_assistant_conversation_title_bak_20261030 (id, tenant_id, thread_id, title, update_time)
SELECT c.id, c.tenant_id, c.thread_id, c.title, c.update_time
  FROM public.dcs_assistant_conversation c
 WHERE c.title IS NOT NULL
   AND (btrim(c.title) LIKE '{"%'
        OR btrim(c.title) LIKE '[{%'
        OR c.title ~* '"title"\s*:')
   AND NOT EXISTS (
         SELECT 1
           FROM public.dcs_assistant_conversation_title_bak_20261030 b
          WHERE b.id = c.id
            AND b.title IS NOT DISTINCT FROM c.title);

UPDATE public.dcs_assistant_conversation
   SET title = NULL
 WHERE title IS NOT NULL
   AND (btrim(title) LIKE '{"%'
        OR btrim(title) LIKE '[{%'
        OR title ~* '"title"\s*:');

-- ===================== 2026-11-01_assistant_action_audit.sql =====================
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

-- ===================== 2026-11-01_assistant_action_revoke.sql =====================
-- 2026-11-01 三批④ 全自动完成通知+撤回：dcs_assistant_action 补撤回留痕两列
-- 内容: 撤回成功后账本行 status=REVOKED，并记 revoked_by（撤回人）/ revoked_at（撤回时刻）
--       ——审计页（三批③ 11936 菜单）按 status=REVOKED 可见撤回行；本迁移只加列不改存量数据
-- 幂等: ADD COLUMN IF NOT EXISTS 可重复执行（存量环境跑两遍无副作用）
-- 回退: ALTER TABLE public.dcs_assistant_action
--         DROP COLUMN IF EXISTS revoked_by, DROP COLUMN IF EXISTS revoked_at;
--       （回退前先把 status='REVOKED' 的行视为无效态处理——REVOKED 无旧值可还原，回退即丢撤回留痕）

ALTER TABLE public.dcs_assistant_action
    ADD COLUMN IF NOT EXISTS revoked_by bigint,
    ADD COLUMN IF NOT EXISTS revoked_at timestamp without time zone;

COMMENT ON COLUMN public.dcs_assistant_action.revoked_by
    IS '撤回人user_id(status=REVOKED时非空; 与账本行user_id同源=本人撤自己的)';

COMMENT ON COLUMN public.dcs_assistant_action.revoked_at
    IS '撤回时刻(status=REVOKED时非空)';

-- ===================== 2026-11-01_assistant_attachment_owner.sql =====================
-- 多实例：附件解析认领归属（2026-11-01 四审 dspro-P1-2）
-- 没有归属时，另一台实例启动/巡检会把本机排队中的 PENDING 行一起认领，
-- 两边各解析一遍；迟到的任务还会把 PARSED 翻回 PARSING / 覆盖成 FAILED。
-- parse_owner=认领实例 id；markParsing/finish* 都带 owner 守卫，谁认领谁回写。
ALTER TABLE public.dcs_assistant_attachment
    ADD COLUMN IF NOT EXISTS parse_owner character varying(64);

COMMENT ON COLUMN public.dcs_assistant_attachment.parse_owner IS
    '解析认领实例 id（多实例防重复解析；空=未认领）';

-- ===================== 2026-11-01_assistant_run_owner.sql =====================
-- 多实例批 3：run 归属实例与末次事件时间
-- 幂等：可重复执行（IF NOT EXISTS）
ALTER TABLE public.dcs_assistant_run
    ADD COLUMN IF NOT EXISTS owner_instance character varying(128);

ALTER TABLE public.dcs_assistant_run
    ADD COLUMN IF NOT EXISTS last_event_at timestamp without time zone;

-- 收割候选查询用 COALESCE(last_event_at, create_time)，表达式索引才对得上
-- （二审回修：不建 (status,last_event_at) 单列索引——没有任何查询命中它，只增写放大；
--   已部署环境若跑过旧版迁移，另执行 DROP INDEX IF EXISTS idx_dcs_asst_run_status_last_event）
CREATE INDEX IF NOT EXISTS idx_dcs_asst_run_status_event_ref
    ON public.dcs_assistant_run USING btree (status, COALESCE(last_event_at, create_time));

DROP INDEX IF EXISTS public.idx_dcs_asst_run_status_last_event;

-- ===================== 2026-11-02_smallfix_retire_invite_button.sql =====================
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

-- ===================== 2026-11-03_agent_rework.sql =====================
-- 2026-11-03 批3.5·题5（拍板：全按推荐）：技能使用统计加「人」维度。
-- 背景：dcs_assistant_skill_usage 原先按 (tenant_id, skill_id) 一技一行（单位级），撑不住
--       工作台「能做什么 · 你最近用过」的按人取数；本迁移补 user_id 并换唯一键。
-- 口径：历史单位级行落 user_id=0（不再新增）；管理页统计改为按技能 SUM 聚合（口径=全单位合计，不变）。
-- 可重复执行：ADD COLUMN IF NOT EXISTS / DROP INDEX IF EXISTS / CREATE UNIQUE INDEX IF NOT EXISTS。

ALTER TABLE public.dcs_assistant_skill_usage
    ADD COLUMN IF NOT EXISTS user_id bigint NOT NULL DEFAULT 0;

DROP INDEX IF EXISTS uk_dcs_assistant_skill_usage_tenant;

CREATE UNIQUE INDEX IF NOT EXISTS uk_dcs_assistant_skill_usage_tenant_user
    ON public.dcs_assistant_skill_usage (tenant_id, user_id, skill_id);

COMMENT ON COLUMN public.dcs_assistant_skill_usage.user_id IS '使用人（雪花 userId；批3.5·题5 加列，历史单位级行=0）';
COMMENT ON CONSTRAINT dcs_assistant_skill_usage_pk ON public.dcs_assistant_skill_usage IS 'PRIMARY KEY (id)';

-- ===================== 2026-11-03_agent_workspace_menu.sql =====================
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

-- ===================== 2026-11-05_agent_settings_audit.sql =====================
-- 2026-11-05 工作台设置页·审计分节授权（拍板 A3 / 题5 乙）
-- 背景：智能体工作台的「设置」改成独立页后，审计从设置页进（只读，复用
--       /dcs/assistant/action/audit/* 现有接口与数据）。业务系统里的「写操作审计」
--       11936 保持原样、保持 0 角色绑定——不给 admin 绑 11936，避免业务侧栏多出菜单。
-- 做法：新增 1 条 F 型权限行 12061（menu_type=F，path 空，不生成动态路由），perms 复用
--       dcs:assistant:audit:query；再显式授给 role_key IN ('admin','tenant_admin')。
-- 边界：成员角色 5 不得获得（本脚本只按 role_key 选，天然不含 member）；
--       file_reviewer 等其它角色也不授。超管走 *:*:* 不需要绑定。
-- 幂等：菜单 ON CONFLICT (menu_id) DO UPDATE；角色绑定 NOT EXISTS。
-- 回退：DELETE FROM sys_role_menu WHERE menu_id = 12061;
--       DELETE FROM sys_menu WHERE menu_id = 12061;

INSERT INTO public.sys_menu
    (menu_id, menu_name, parent_id, order_num, path, component, query_param,
     is_frame, is_cache, menu_type, visible, status, perms, icon,
     create_dept, create_by, create_time, update_by, update_time, remark)
VALUES
    (12061, '工作台审计查看', 11930, 11, '', NULL, NULL,
     '1', '0', 'F', '0', '0', 'dcs:assistant:audit:query', '#',
     103, 1, now(), NULL, NULL, '工作台设置页审计分节(只读; 仅授 admin/tenant_admin, 不生成路由)')
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

-- 显式授权：只给管理员（租户管理员 + 演示库的自定义 admin 角色），不给成员
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT r.role_id, 12061
FROM public.sys_role r
WHERE r.role_key IN ('admin', 'tenant_admin')
  AND r.del_flag = '0'
  AND r.status = '0'
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu x WHERE x.role_id = r.role_id AND x.menu_id = 12061);

-- ===================== 2026-11-05_assistant_automation.sql =====================
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

-- ===================== 2026-11-06_automation_mcp_servers.sql =====================
-- 2026-11-06 旧板题 3 乙：自动化可显式勾选「只读外部服务」（MCP 只读）
-- 默认 NULL/空 = 一个都不挂（与改造前行为一致）；挂载面二次过滤 read_only='1'
ALTER TABLE public.dcs_automation
    ADD COLUMN IF NOT EXISTS mcp_servers character varying(500);

COMMENT ON COLUMN public.dcs_automation.mcp_servers IS
    '只读外部服务白名单（逗号分隔的 MCP serverName）；空=不挂任何外部工具';
