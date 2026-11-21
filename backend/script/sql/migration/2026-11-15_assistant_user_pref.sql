-- 2026-11-15 批C Q8 案1（2026-11-13 待办板已拍 · 12 题全按推荐）：
-- 「常规偏好」七行（发送键 / 流式输出 / 明细展开 / 失败重试 / 动效强度 / 字号 / 5 分钟自动继续）
-- 以前只存浏览器 localStorage（键带 userId），换电脑、换浏览器就回默认。
-- 本表把这一层按人入库；localStorage 降级成离线兜底（断网时仍能读写，联网后以最后一次保存为准）。
-- 可重复执行（IF NOT EXISTS）；与 baseline/01_schema.sql 同步。

CREATE TABLE IF NOT EXISTS public.assistant_user_pref (
    id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    user_id bigint NOT NULL,
    send_key character varying(16) DEFAULT 'enter'::character varying NOT NULL,
    stream_output character(1) DEFAULT '1'::bpchar NOT NULL,
    tools_open character(1) DEFAULT '0'::bpchar NOT NULL,
    auto_retry character(1) DEFAULT '1'::bpchar NOT NULL,
    motion character varying(16) DEFAULT 'system'::character varying NOT NULL,
    answer_size character varying(16) DEFAULT 'standard'::character varying NOT NULL,
    ask_auto_continue character(1) DEFAULT '1'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone,
    CONSTRAINT pk_assistant_user_pref PRIMARY KEY (id),
    CONSTRAINT uk_asst_user_pref UNIQUE (tenant_id, user_id)
);

COMMENT ON TABLE public.assistant_user_pref IS '助手常规偏好（按人）；缺行=全部默认值';

-- 兜底：CREATE TABLE IF NOT EXISTS 遇到「表已存在但缺唯一键」时会整句跳过，UPSERT 的 ON CONFLICT 就匹配不到。
-- 只在缺唯一约束时才补一条唯一索引（正常环境不加第二个重复索引）；ON CONFLICT (tenant_id, user_id) 可匹配唯一索引。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.assistant_user_pref'::regclass AND contype = 'u'
    ) THEN
        CREATE UNIQUE INDEX IF NOT EXISTS uk_asst_user_pref_uidx
            ON public.assistant_user_pref (tenant_id, user_id);
    END IF;
END $$;
