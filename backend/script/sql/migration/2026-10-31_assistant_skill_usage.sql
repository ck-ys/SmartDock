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
