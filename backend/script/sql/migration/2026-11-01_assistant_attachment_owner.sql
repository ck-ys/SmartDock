-- 多实例：附件解析认领归属（2026-11-01 四审 dspro-P1-2）
-- 没有归属时，另一台实例启动/巡检会把本机排队中的 PENDING 行一起认领，
-- 两边各解析一遍；迟到的任务还会把 PARSED 翻回 PARSING / 覆盖成 FAILED。
-- parse_owner=认领实例 id；markParsing/finish* 都带 owner 守卫，谁认领谁回写。
ALTER TABLE public.dcs_assistant_attachment
    ADD COLUMN IF NOT EXISTS parse_owner character varying(64);

COMMENT ON COLUMN public.dcs_assistant_attachment.parse_owner IS
    '解析认领实例 id（多实例防重复解析；空=未认领）';
