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
