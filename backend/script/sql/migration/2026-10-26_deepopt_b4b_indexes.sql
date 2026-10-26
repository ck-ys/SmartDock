-- 批4B 归档表查询索引: 主体维度回溯 + 匿名 ID 反查(部分索引)
-- 可重复执行: IF NOT EXISTS。
CREATE INDEX IF NOT EXISTS idx_profile_event_archive_subject
    ON public.profile_event_archive USING btree (tenant_id, source, subject_type, subject_id);
CREATE INDEX IF NOT EXISTS idx_profile_event_archive_anon
    ON public.profile_event_archive (tenant_id, source, anonymous_id) WHERE anonymous_id IS NOT NULL;
