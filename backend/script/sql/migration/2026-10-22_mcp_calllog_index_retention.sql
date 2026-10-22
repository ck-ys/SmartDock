-- G4-#5（2026-10-22）：MCP 调用审计表补服务维度索引
-- 背景：管理页审计查询按 server_name 等值 + create_time DESC 分页；现有唯一索引
-- idx_dcs_mcp_call_tenant_time 首列是 tenant_id——超管全量路径用不上、服务过滤路径
-- 首列缺失退化顺序扫描。本索引与该查询模式精确匹配。
-- 可重复执行（幂等）；保留期清理（90 天）由后端定时任务实现，无需 DDL。

CREATE INDEX IF NOT EXISTS idx_dcs_mcp_call_server_time
    ON public.dcs_mcp_call_log (server_name, create_time DESC);
