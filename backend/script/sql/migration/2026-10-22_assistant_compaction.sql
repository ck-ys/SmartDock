-- 批A-2（2026-10-22 拍板 Q9-A）：自动压缩记录表（链式审计）
-- 背景：压缩目前只落 dcs_assistant_message 的 system 分隔条，无法审计「压了几次、
--       每次前后多少、上一条是谁」。本表一行一次压缩，prev_id 指向上一条同会话记录。
-- 可重复执行（幂等）。

CREATE TABLE IF NOT EXISTS public.dcs_assistant_compaction (
    id bigint NOT NULL,
    tenant_id character varying(20) NOT NULL,
    conversation_id bigint NOT NULL,
    user_id bigint NOT NULL,
    thread_id character varying(128),
    trigger_type character varying(16) DEFAULT 'AUTO'::character varying NOT NULL,
    status character varying(16) DEFAULT 'SUCCESS'::character varying NOT NULL,
    before_tokens integer NOT NULL,
    after_tokens integer NOT NULL,
    before_messages integer NOT NULL,
    after_messages integer NOT NULL,
    summary text,
    reason character varying(64),
    prev_id bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT dcs_assistant_compaction_pk PRIMARY KEY (id)
);

-- 已建表环境补列（Q9-A：摘要正文 + 原因；幂等）
ALTER TABLE public.dcs_assistant_compaction ADD COLUMN IF NOT EXISTS summary text;
ALTER TABLE public.dcs_assistant_compaction ADD COLUMN IF NOT EXISTS reason character varying(64);

CREATE INDEX IF NOT EXISTS idx_dcs_asst_comp_conv
    ON public.dcs_assistant_compaction (tenant_id, conversation_id, id);

-- 二审 P2：保留期清理按 create_time 过滤分批删——补时间索引（幂等）
CREATE INDEX IF NOT EXISTS idx_dcs_asst_comp_time
    ON public.dcs_assistant_compaction (create_time);
