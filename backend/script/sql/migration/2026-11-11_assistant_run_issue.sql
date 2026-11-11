-- 2026-11-11 拍板 Q1-3：「这轮为什么没回答」问题表 dcs_assistant_run_issue
-- 可重复执行（IF NOT EXISTS）；与 baseline/01_schema.sql 尾部同构。
-- 保留 90 天（AssistantConversationService.retentionSweep 编排），统计索引留给后续按原因聚合。

CREATE TABLE IF NOT EXISTS public.dcs_assistant_run_issue (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    user_id bigint NOT NULL,
    conversation_id bigint,
    thread_id character varying(128),
    run_id character varying(128) NOT NULL,
    message_id bigint,
    code character varying(16) NOT NULL,
    detail character varying(512),
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_dcs_assistant_run_issue PRIMARY KEY (id),
    CONSTRAINT ck_dcs_asst_issue_code CHECK (code::text = ANY (ARRAY['empty','rate','quota','timeout','auth','other']::text[]))
);

CREATE INDEX IF NOT EXISTS idx_dcs_asst_issue_conv ON public.dcs_assistant_run_issue USING btree (tenant_id, user_id, conversation_id);
CREATE INDEX IF NOT EXISTS idx_dcs_asst_issue_stats ON public.dcs_assistant_run_issue USING btree (tenant_id, code, create_time);
-- 终审 P1（2026-11-11）：run_id 唯一约束——「同 run 只落一条」的 DB 兜底。
-- 两条写入路（终态闸门 / doOnError 捕获）当前互斥靠的是框架行为，升级即可能叠行；这里锁死。
-- 已按旧版执行过的库：先删普通索引再建唯一（表新无重复行，安全）。
DROP INDEX IF EXISTS public.idx_dcs_asst_issue_run;
CREATE UNIQUE INDEX IF NOT EXISTS uk_dcs_asst_issue_run ON public.dcs_assistant_run_issue USING btree (run_id);
