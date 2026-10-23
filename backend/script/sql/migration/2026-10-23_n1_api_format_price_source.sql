-- N1 批（2026-10-23）：接口格式三选一 + 价格来源标记（可重复执行）
-- Q5-A：老配置/老钥匙默认 CHAT_COMPLETIONS，行为不变
ALTER TABLE public.dcs_ai_config
    ADD COLUMN IF NOT EXISTS api_format character varying(24) DEFAULT 'CHAT_COMPLETIONS'::character varying NOT NULL;

ALTER TABLE public.dcs_ai_provider_access
    ADD COLUMN IF NOT EXISTS api_format character varying(24) DEFAULT 'CHAT_COMPLETIONS'::character varying NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_dcs_ai_config_api_format') THEN
        ALTER TABLE public.dcs_ai_config
            ADD CONSTRAINT ck_dcs_ai_config_api_format
            CHECK (api_format IN ('CHAT_COMPLETIONS', 'ANTHROPIC_MESSAGES', 'RESPONSES'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_dcs_ai_provider_access_api_format') THEN
        ALTER TABLE public.dcs_ai_provider_access
            ADD CONSTRAINT ck_dcs_ai_provider_access_api_format
            CHECK (api_format IN ('CHAT_COMPLETIONS', 'ANTHROPIC_MESSAGES', 'RESPONSES'));
    END IF;
END $$;

-- Q6-A：价格行来源与手工覆盖标记（models.dev 同步不覆盖人工价）
ALTER TABLE public.dcs_ai_model_price
    ADD COLUMN IF NOT EXISTS price_source character varying(16) DEFAULT 'MANUAL'::character varying NOT NULL;

-- manual_override 首建时把存量行一并锁住：迁移前的价格全是人工维护的，
-- 默认 '0' 会让第一次 models.dev 同步把它们覆盖掉。包在「列不存在才执行」里保证可重跑
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
         WHERE table_schema = 'public' AND table_name = 'dcs_ai_model_price' AND column_name = 'manual_override'
    ) THEN
        ALTER TABLE public.dcs_ai_model_price
            ADD COLUMN manual_override character(1) DEFAULT '0'::bpchar NOT NULL;
        UPDATE public.dcs_ai_model_price SET manual_override = '1';
    END IF;
END $$;

ALTER TABLE public.dcs_ai_model_price
    ADD COLUMN IF NOT EXISTS last_synced_at timestamp without time zone;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_dcs_ai_model_price_source') THEN
        ALTER TABLE public.dcs_ai_model_price
            ADD CONSTRAINT ck_dcs_ai_model_price_source
            CHECK (price_source IN ('MANUAL', 'MODELS_DEV', 'IMPORT'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_dcs_ai_model_price_manual_override') THEN
        ALTER TABLE public.dcs_ai_model_price
            ADD CONSTRAINT ck_dcs_ai_model_price_manual_override
            CHECK (manual_override IN ('0', '1'));
    END IF;
END $$;

CREATE INDEX IF NOT EXISTS idx_dcs_ai_model_price_source
    ON public.dcs_ai_model_price (tenant_id, price_source)
    WHERE del_flag = '0';
