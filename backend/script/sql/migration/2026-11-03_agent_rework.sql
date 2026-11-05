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
