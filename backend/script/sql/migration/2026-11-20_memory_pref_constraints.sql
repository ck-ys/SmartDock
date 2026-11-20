-- 批 7 前置 D1 多审 P1（席3，2026-11-20；二轮席3 加固）：dcs_assistant_memory_pref 补主键 + 唯一键。
--
-- 病根：该表在 2026-11-12_assistant_memory_v2.sql 里带约束建表，但 baseline/01_schema.sql
-- 的同款 CREATE TABLE 漏抄了两条约束——全新环境只跑 baseline 时表没有主键/唯一键，
-- 开关判定的 UPSERT（ON CONFLICT (tenant_id, user_id)）会 42P10 直接 500（席3 TEMP 表实测）。
-- 与 baseline/01_schema.sql 同步补齐；可重复执行（按名幂等 + 唯一索引按列集幂等）。
-- 失败面（二轮席3 实测）：id 重复 → 23505、id 有 NULL → 23502（列类型不影响——varchar 也能建主键）。
-- 预检：select count(*) from public.dcs_assistant_memory_pref where id is null;  非 0 先补值再跑。
-- 后端启动自检（AssistantMemoryFenceStartupCheck）会顺带校验本表唯一键，漏跑会被当场拦下。

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.dcs_assistant_memory_pref'::regclass
          AND conname = 'pk_dcs_assistant_memory_pref'
    ) THEN
        ALTER TABLE public.dcs_assistant_memory_pref
            ADD CONSTRAINT pk_dcs_assistant_memory_pref PRIMARY KEY (id);
    END IF;
    -- 唯一键判定看「列集合」不看约束大类（二轮席3）：表上若已建了别的列的唯一约束，
    -- contype='u' 会误判已就绪而跳过，(tenant_id,user_id) 的 UPSERT 仍 42P10
    IF NOT EXISTS (
        SELECT 1 FROM pg_indexes
        WHERE schemaname = 'public' AND tablename = 'dcs_assistant_memory_pref'
          AND indexdef LIKE 'CREATE UNIQUE INDEX%'
          AND indexdef LIKE '%(tenant_id, user_id)%'
    ) THEN
        -- 补唯一索引即可被 ON CONFLICT (tenant_id, user_id) 匹配
        -- （约束形态与索引形态二者居一即可；避免给已有唯一键的环境加重复索引）
        CREATE UNIQUE INDEX IF NOT EXISTS uk_dcs_asst_memory_pref_uidx
            ON public.dcs_assistant_memory_pref (tenant_id, user_id);
    END IF;
END $$;
