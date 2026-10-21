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
EXCEPTION WHEN duplicate_object THEN NULL; END $$;
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
