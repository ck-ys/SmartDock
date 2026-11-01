-- 2026-11-01 三批④ 全自动完成通知+撤回：dcs_assistant_action 补撤回留痕两列
-- 内容: 撤回成功后账本行 status=REVOKED，并记 revoked_by（撤回人）/ revoked_at（撤回时刻）
--       ——审计页（三批③ 11936 菜单）按 status=REVOKED 可见撤回行；本迁移只加列不改存量数据
-- 幂等: ADD COLUMN IF NOT EXISTS 可重复执行（存量环境跑两遍无副作用）
-- 回退: ALTER TABLE public.dcs_assistant_action
--         DROP COLUMN IF EXISTS revoked_by, DROP COLUMN IF EXISTS revoked_at;
--       （回退前先把 status='REVOKED' 的行视为无效态处理——REVOKED 无旧值可还原，回退即丢撤回留痕）

ALTER TABLE public.dcs_assistant_action
    ADD COLUMN IF NOT EXISTS revoked_by bigint,
    ADD COLUMN IF NOT EXISTS revoked_at timestamp without time zone;

COMMENT ON COLUMN public.dcs_assistant_action.revoked_by
    IS '撤回人user_id(status=REVOKED时非空; 与账本行user_id同源=本人撤自己的)';

COMMENT ON COLUMN public.dcs_assistant_action.revoked_at
    IS '撤回时刻(status=REVOKED时非空)';
