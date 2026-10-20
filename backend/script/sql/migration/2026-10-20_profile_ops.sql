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
