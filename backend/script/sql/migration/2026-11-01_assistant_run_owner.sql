-- 多实例批 3：run 归属实例与末次事件时间
-- 幂等：可重复执行（IF NOT EXISTS）
ALTER TABLE public.dcs_assistant_run
    ADD COLUMN IF NOT EXISTS owner_instance character varying(128);

ALTER TABLE public.dcs_assistant_run
    ADD COLUMN IF NOT EXISTS last_event_at timestamp without time zone;

-- 收割候选查询用 COALESCE(last_event_at, create_time)，表达式索引才对得上
-- （二审回修：不建 (status,last_event_at) 单列索引——没有任何查询命中它，只增写放大；
--   已部署环境若跑过旧版迁移，另执行 DROP INDEX IF EXISTS idx_dcs_asst_run_status_last_event）
CREATE INDEX IF NOT EXISTS idx_dcs_asst_run_status_event_ref
    ON public.dcs_assistant_run USING btree (status, COALESCE(last_event_at, create_time));

DROP INDEX IF EXISTS public.idx_dcs_asst_run_status_last_event;
