-- G4 三审回修（2026-10-22）：审计保留期清理的支撑索引
-- 背景：每日清理 DELETE ... WHERE create_time < cutoff（分批 5000 子查询）；既有两个
-- 索引前导列分别是 tenant_id / server_name，create_time 无前导索引 → 每批顺序扫
-- （dspro/dsflash 双席共识 P2）。本索引专供清理路径；可重复执行。

CREATE INDEX IF NOT EXISTS idx_dcs_mcp_call_create_time
    ON public.dcs_mcp_call_log (create_time);
