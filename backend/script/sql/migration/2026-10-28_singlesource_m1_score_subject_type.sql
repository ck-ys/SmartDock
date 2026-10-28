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
