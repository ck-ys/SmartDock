-- 2026-11-06 旧板题 3 乙：自动化可显式勾选「只读外部服务」（MCP 只读）
-- 默认 NULL/空 = 一个都不挂（与改造前行为一致）；挂载面二次过滤 read_only='1'
ALTER TABLE public.dcs_automation
    ADD COLUMN IF NOT EXISTS mcp_servers character varying(500);

COMMENT ON COLUMN public.dcs_automation.mcp_servers IS
    '只读外部服务白名单（逗号分隔的 MCP serverName）；空=不挂任何外部工具';
