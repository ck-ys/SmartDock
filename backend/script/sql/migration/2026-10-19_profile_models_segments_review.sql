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
