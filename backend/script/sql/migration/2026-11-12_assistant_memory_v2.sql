-- 2026-11-12 拍板（ckys3 解锁板 v8.2 题7~题10，全部按★案1）：
-- 题7 记忆页可用性：类型 / 出处 / 编辑 / 条数
-- 题8 同主题覆盖：旧条留痕（SUPERSEDED + superseded_by）
-- 题10 生命周期：个人开关表 + 90 天未用归档（last_used_at / status）
-- 可重复执行（IF NOT EXISTS / 幂等 DEFAULT）；与 baseline/01_schema.sql 同步。

ALTER TABLE public.dcs_assistant_memory
    ADD COLUMN IF NOT EXISTS memory_type character varying(20) DEFAULT 'user'::character varying NOT NULL,
    ADD COLUMN IF NOT EXISTS conversation_id bigint,
    ADD COLUMN IF NOT EXISTS topic_key character varying(40),
    ADD COLUMN IF NOT EXISTS status character varying(16) DEFAULT 'ACTIVE'::character varying NOT NULL,
    ADD COLUMN IF NOT EXISTS superseded_by bigint,
    ADD COLUMN IF NOT EXISTS last_used_at timestamp without time zone,
    ADD COLUMN IF NOT EXISTS archived_at timestamp without time zone;

-- 存量行回填：状态/类型默认值已由 DEFAULT 覆盖；topic_key 留空（下次保存同主题时再判）
UPDATE public.dcs_assistant_memory SET status = 'ACTIVE' WHERE status IS NULL;

-- 存量类型回填（一次，语义对齐）：ADD COLUMN 的 DEFAULT 把老行一律标成 user，
-- 但「出差/例会」这类应落 project/team。只动「从没被编辑过」（update_time IS NULL）的行
-- ——重跑安全：用户手改类型后 update_time 有值，不会被再次覆盖。
UPDATE public.dcs_assistant_memory SET memory_type = CASE
    WHEN content ~ '王工|张工|李工|赵工|刘工|同事|同学|主管|领导|负责人|对接人|经理' THEN 'team'
    WHEN content ~ '项目|出差|行程|周会|例会|会议|任务|截止|上线|部署|版本|迭代|需求|工单|排期' THEN 'project'
    WHEN content ~ '叫我|称呼|以后都|默认|偏好|喜欢|不喜欢|不要|别用|习惯|回复|回答|语气|风格' THEN 'user'
    ELSE 'reference'
END
WHERE update_time IS NULL AND del_flag = '0';

-- 存量 topic_key 回填（题8 同主题覆盖要对老数据生效：老行 topic_key 为空时
-- 「以后叫我老周」→「以后叫我老王」会并存两条互相矛盾的记忆）。
-- 规则与 AssistantMemoryService.TOPIC_RULES 同序同源；只补空值，重跑安全。
UPDATE public.dcs_assistant_memory SET topic_key = CASE
    WHEN content ~ '叫我|称呼|喊我|我的名字|名字叫' THEN 'call:name'
    WHEN content ~ '用中文|用英文|说中文|说英文|语言|英文回答|中文回答' THEN 'reply:lang'
    WHEN content ~ '催办|默认提醒|默认时间|提醒我' THEN 'remind:default'
    WHEN content ~ '时区|工作日|上班时间|下班时间|工作时间|作息' THEN 'time:zone'
END
WHERE topic_key IS NULL AND del_flag = '0'
  AND content ~ '叫我|称呼|喊我|我的名字|名字叫|用中文|用英文|说中文|说英文|语言|英文回答|中文回答|催办|默认提醒|默认时间|提醒我|时区|工作日|上班时间|下班时间|工作时间|作息';

-- 清单/容量/归档扫描共用索引（租户+属主+状态）
CREATE INDEX IF NOT EXISTS idx_dcs_asst_memory_user_status
    ON public.dcs_assistant_memory USING btree (tenant_id, user_id, status);
-- 归档扫描（只扫 ACTIVE 且 last_used_at 早于阈值）
CREATE INDEX IF NOT EXISTS idx_dcs_asst_memory_used
    ON public.dcs_assistant_memory USING btree (status, last_used_at);

-- 题10 案1：个人开关（租户级 memory_enabled 之外的用户级开关；与运算，租户关=全关）
CREATE TABLE IF NOT EXISTS public.dcs_assistant_memory_pref (
    id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    user_id bigint NOT NULL,
    enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_time timestamp without time zone,
    CONSTRAINT pk_dcs_assistant_memory_pref PRIMARY KEY (id),
    CONSTRAINT uk_dcs_asst_memory_pref UNIQUE (tenant_id, user_id)
);
