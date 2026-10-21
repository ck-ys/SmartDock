-- 2026-10-21 批2 Q2=A（MCP 试点包）：dcs_mcp_server / dcs_mcp_call_log 两表（可重复执行）

-- =====================================================================
-- 批2 Q2=A（MCP 试点包，2026-10-21）：外部 MCP 服务注册 + 调用审计两表
-- 设计口径：试点=平台级共享（tenant_id 预留，试点行恒 '000000'）；
-- read_only 恒 '1'（试点只收只读服务）；密钥/敏感头 headers 加密存储。
-- =====================================================================

-- MCP 服务注册表（管理台「外部工具」页签维护；STDIO/STREAMABLE_HTTP/SSE 三传输）
CREATE TABLE IF NOT EXISTS public.dcs_mcp_server (
    id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    server_name character varying(50) NOT NULL,
    display_name character varying(100),
    transport_type character varying(20) NOT NULL,
    command character varying(255),
    args text,
    url character varying(500),
    headers text,
    read_only character(1) DEFAULT '1'::bpchar NOT NULL,
    enabled character(1) DEFAULT '0'::bpchar NOT NULL,
    remark character varying(255),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone,
    update_by bigint,
    update_time timestamp without time zone,
    CONSTRAINT pk_dcs_mcp_server PRIMARY KEY (id),
    -- 试点物理删（审计表留痕），不引入逻辑删避免同名重建撞键
    CONSTRAINT uk_dcs_mcp_server_name UNIQUE (server_name, tenant_id),
    CONSTRAINT ck_dcs_mcp_transport CHECK (transport_type IN ('STDIO', 'STREAMABLE_HTTP', 'SSE')),
    CONSTRAINT ck_dcs_mcp_readonly CHECK (read_only = '1'),
    CONSTRAINT ck_dcs_mcp_enabled CHECK (enabled IN ('0', '1'))
);
CREATE INDEX IF NOT EXISTS idx_dcs_mcp_server_tenant ON public.dcs_mcp_server (tenant_id, enabled);

-- MCP 工具调用审计（挂载/每次真实调用两态都落账：MOUNT_OK/MOUNT_ERROR/CALL_OK/CALL_ERROR/RATE_LIMITED）
CREATE TABLE IF NOT EXISTS public.dcs_mcp_call_log (
    id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    user_id bigint,
    server_name character varying(50) NOT NULL,
    tool_name character varying(100),
    action character varying(20) NOT NULL,
    status character(1) DEFAULT '0'::bpchar NOT NULL,
    duration_ms integer,
    error_msg character varying(500),
    create_time timestamp without time zone,
    CONSTRAINT pk_dcs_mcp_call_log PRIMARY KEY (id),
    CONSTRAINT ck_dcs_mcp_call_action CHECK (action IN ('MOUNT_OK', 'MOUNT_ERROR', 'CALL_OK', 'CALL_ERROR', 'RATE_LIMITED')),
    CONSTRAINT ck_dcs_mcp_call_status CHECK (status IN ('0', '1'))
);
CREATE INDEX IF NOT EXISTS idx_dcs_mcp_call_tenant_time ON public.dcs_mcp_call_log (tenant_id, create_time DESC);
