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
