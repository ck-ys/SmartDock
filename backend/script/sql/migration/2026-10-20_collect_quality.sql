-- =====================================================================
-- 批7 采集质量：丢弃留痕表（robot/domain_mismatch/oversize/duplicate/schema_invalid）
-- 可重复执行（IF NOT EXISTS）。同步合入 baseline/07_analytics.sql。
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.analytics_collect_log (
    log_id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    source character varying(32) NOT NULL,
    site_key character varying(64) NOT NULL,
    drop_reason character varying(32) NOT NULL,
    event_count integer DEFAULT 1 NOT NULL,
    ip_hash character varying(64),
    user_agent character varying(512),
    origin_host character varying(255),
    detail character varying(500),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_analytics_collect_log PRIMARY KEY (log_id)
);

CREATE INDEX IF NOT EXISTS idx_analytics_collect_log_time
    ON public.analytics_collect_log USING btree (tenant_id, source, create_time DESC);
CREATE INDEX IF NOT EXISTS idx_analytics_collect_log_reason
    ON public.analytics_collect_log USING btree (tenant_id, source, drop_reason);
