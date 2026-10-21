-- G3 三审回修（双席共识 P0）：dcs_mcp_call_log.action CHECK 约束补 ELICIT_* 五值
-- 背景：G3-A1 elicitation 反问桥写入 ELICIT_ASK/ACCEPT/DECLINE/CANCEL/TIMEOUT 审计，
-- 原五值约束 100% 拒收（异常被 audit() 静默吞掉，审计账本零落库）。
-- 可重复执行（幂等）：DROP IF EXISTS + ADD。

ALTER TABLE public.dcs_mcp_call_log DROP CONSTRAINT IF EXISTS ck_dcs_mcp_call_action;

ALTER TABLE public.dcs_mcp_call_log ADD CONSTRAINT ck_dcs_mcp_call_action CHECK (
    action IN ('MOUNT_OK', 'MOUNT_ERROR', 'CALL_OK', 'CALL_ERROR', 'RATE_LIMITED',
               'ELICIT_ASK', 'ELICIT_ACCEPT', 'ELICIT_DECLINE', 'ELICIT_CANCEL', 'ELICIT_TIMEOUT')
);
