-- 2026-11-26 额度三级管控（超管 → 企业 → 用户）：三张表各加一列
--
-- 背景：企业买了积分套餐后全公司共用一个积分池，任何一个人都能一直花到池子见底。
-- 本迁移补「单个人最多花多少」这道闸所需的三个存储位：
--   1) assistant_config.daily_credit_limit_per_user —— 企业默认（每人每天积分上限）
--   2) assistant_user_quota.credit_limit_override   —— 个人覆盖
--   3) ai_credit_plan.suggested_daily_credit_per_user —— 套餐建议值
--
-- 取值优先级（后端 AssistantCreditQuota 实现）：个人覆盖 → 企业默认 → 套餐建议值 → 平台兜底。
--
-- 全部可空：NULL = 该级没设，落到下一级。这是「跟随上一级」的语义。
-- 企业默认列另有一个显式记号 -2 = 「显式跟随套餐」——只留 NULL 清不回跟随，
-- 因为现有保存链路把「没传」当成「保持原值」（service 先读在位行合并非空字段再整行 upsert，
-- SQL 侧又是 INSERT ... COALESCE），所以「设过之后想退回去」必须有一个能落库的值。
-- 写入时 -2 归一成 NULL（见 AssistantConfigMapper.upsert），读侧与 NULL 同义。
--
-- 可重复执行：IF NOT EXISTS。

ALTER TABLE public.assistant_config
    ADD COLUMN IF NOT EXISTS daily_credit_limit_per_user integer;

ALTER TABLE public.assistant_user_quota
    ADD COLUMN IF NOT EXISTS credit_limit_override integer;

ALTER TABLE public.ai_credit_plan
    ADD COLUMN IF NOT EXISTS suggested_daily_credit_per_user integer;

COMMENT ON COLUMN public.assistant_config.daily_credit_limit_per_user IS
    '企业默认：每人每天积分上限；NULL=跟随套餐建议值，-2=显式跟随（写入归一为 NULL）';
COMMENT ON COLUMN public.assistant_user_quota.credit_limit_override IS
    '个人覆盖：该用户每天积分上限；NULL=跟随企业默认';
COMMENT ON COLUMN public.ai_credit_plan.suggested_daily_credit_per_user IS
    '套餐建议值：该档位下每人每天建议积分上限；NULL=没给建议';

-- 存量库补套餐建议值（基线 02_reference.sql 已同步写入，新库不需要这一步）。
-- 经验值 = 每周积分 ÷ 40（每人一周最多占周池 17.5%）：轻量 2000→50 / 专业 9000→225 / 旗舰 36000→900。
-- 只填空值：超管已在套餐页改过的行不动（IS NULL 守卫，可重复执行）。
UPDATE public.ai_credit_plan SET suggested_daily_credit_per_user = 50
 WHERE plan_code = 'LITE' AND suggested_daily_credit_per_user IS NULL;
UPDATE public.ai_credit_plan SET suggested_daily_credit_per_user = 225
 WHERE plan_code = 'PRO' AND suggested_daily_credit_per_user IS NULL;
UPDATE public.ai_credit_plan SET suggested_daily_credit_per_user = 900
 WHERE plan_code = 'MAX' AND suggested_daily_credit_per_user IS NULL;

-- ── Q19=案1：租户管理员补授「企业自己管额度」闭环的 4 项 ──
-- 11927 AI助手管理（smartdock:assistant:config）+ 11935 额度操作（smartdock:assistant:quota:edit）
-- + 9060 AI 模型与钥匙（smartdock:ai:list，只授看）+ 11940 积分套餐（wallet 批已授，此处幂等重列）。
-- 页面和页里的按钮一起授——只授按钮会造出「能改额度、看不见页面」的半授权。
-- 有意不授：9060 页内的 add/edit/remove、11940 的订阅/改价（11941/11942）。
-- 前置（本批代码已修）：/ai-config/list 非超管强制本租户；MCP 成员清单按租户收窄。
-- 按 role_key 授，不写死 role_id（先例 04_role_menu.sql:227）。
INSERT INTO public.sys_role_menu (role_id, menu_id)
SELECT r.role_id, m.menu_id
FROM public.sys_role r
CROSS JOIN (VALUES (11927), (11935), (11940), (9060)) AS m(menu_id)
WHERE r.role_key IN ('admin', 'tenant_admin')
  AND r.del_flag = '0'
  AND r.status = '0'
  AND NOT EXISTS (SELECT 1 FROM public.sys_role_menu x WHERE x.role_id = r.role_id AND x.menu_id = m.menu_id);

-- ── Q22=1：平台加价系数（扣分 = 一轮成本 × 100 × 平台系数 × 手工系数）──
-- 先落 sys_config 键 ai.credit.platform-factor，缺省 1.0（= 老口径，行为不变）。
-- 这个数写错会影响所有租户的扣分，所以代码侧读失败一律回落 1.0（AiCreditServiceImpl#platformFactor）。
-- 唯一索引是 (tenant_id, config_key)，不是单列——用 WHERE NOT EXISTS 保证可重复执行。
INSERT INTO public.sys_config (config_id, tenant_id, config_name, config_key, config_value, config_type, create_dept, create_by, create_time, update_by, update_time, remark)
SELECT 2111260000000000001, '000000', '积分扣费-平台加价系数', 'ai.credit.platform-factor', '1.0', 'N', NULL, 1, '2026-11-26 09:00:00', NULL, NULL,
       '扣分 = 一轮成本（元）× 100 × 本系数 × 该模型手工系数。1.0 = 按成本价（老口径）；上调即产生毛利。改这里影响所有租户'
WHERE NOT EXISTS (SELECT 1 FROM public.sys_config c
                  WHERE c.tenant_id = '000000' AND c.config_key = 'ai.credit.platform-factor');

-- ── Q17=1：积分流水加「模型 / 倍率 / 一轮成本」三列（快照口径）──
-- 进门预检时取快照、结算写快照值——改倍率后旧流水数字不变（板上验收要求）。
-- 历史行保持 NULL（不追填：当时的倍率已经无从考证，编一个数就是假账）。
ALTER TABLE public.ai_credit_ledger ADD COLUMN IF NOT EXISTS model_name character varying(128);
ALTER TABLE public.ai_credit_ledger ADD COLUMN IF NOT EXISTS credit_multiplier numeric(8,2);
ALTER TABLE public.ai_credit_ledger ADD COLUMN IF NOT EXISTS unit_price numeric(14,6);
COMMENT ON COLUMN public.ai_credit_ledger.model_name IS '快照：结算时用的模型名；NULL=历史行（改倍率不影响已有行）';
COMMENT ON COLUMN public.ai_credit_ledger.credit_multiplier IS '快照：结算时用的倍率（平台系数 × 手工系数）；NULL=历史行';
COMMENT ON COLUMN public.ai_credit_ledger.unit_price IS '快照：结算时一轮的官方成本（元）；NULL=历史行';

-- ── Q16=1：联网搜索服务小表（余额 + 资源包次数估算）──
-- 为什么不并进 ai_provider_access：api_format CHECK 只认三种对话格式 / 厂商枚举没有博查 /
-- 探测前过域名白名单 / 钥匙管理的厂商下拉来自 ai_config（搜索没有模型，进不了下拉）。
-- 口径：balance_amount 走官方 GET /v1/fund/remaining（单位元，自动查）；
--       pack_total/pack_used 厂商没有接口，靠「管理员填总量 + 系统每搜一次减一」估算，界面必须标「估算」。
CREATE TABLE IF NOT EXISTS public.ai_search_service (
    id bigint NOT NULL,
    tenant_id character varying(20) DEFAULT '000000'::character varying NOT NULL,
    provider character varying(32) DEFAULT 'BOCHA'::character varying NOT NULL,
    label character varying(64),
    enabled character(1) DEFAULT '1'::bpchar NOT NULL,
    api_key text,
    console_url character varying(255),
    balance_amount numeric(14,4),
    balance_currency character varying(8),
    balance_checked_at timestamp without time zone,
    balance_status character varying(16),
    pack_total integer,
    pack_used integer DEFAULT 0 NOT NULL,
    pack_expire_at date,
    remark character varying(500),
    create_dept bigint,
    create_by bigint,
    create_time timestamp without time zone DEFAULT CURRENT_TIMESTAMP,
    update_by bigint,
    update_time timestamp without time zone,
    del_flag character(1) DEFAULT '0'::bpchar NOT NULL,
    CONSTRAINT pk_ai_search_service PRIMARY KEY (id)
);
COMMENT ON TABLE public.ai_search_service IS '联网搜索服务（一家一行）：余额走官方接口，资源包次数=管理员填总量+系统每搜一次减一（估算）';
COMMENT ON COLUMN public.ai_search_service.pack_used IS '估算：系统累计调用次数（厂商无查询接口，以厂商后台为准）';

CREATE UNIQUE INDEX IF NOT EXISTS uk_ai_search_service_tenant_provider
    ON public.ai_search_service USING btree (tenant_id, provider)
    WHERE (del_flag = '0'::bpchar);
