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
