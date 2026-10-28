-- 单源重构 C 片(题1): 引擎配置加自监控总开关。
-- '1'=记录本产品用户登录与站内核心操作画像事件(默认, 静默发布不触发打分/汇总);
-- '0'=停用(画像页同屏开关, D 片出 UI; 只停新增, 已有数据保留)。可重复执行。
BEGIN;

ALTER TABLE public.profile_engine_config
    ADD COLUMN IF NOT EXISTS self_monitor char(1) DEFAULT '1'::bpchar NOT NULL;

-- 三审回修: 与同表 event_archive 等开关列对齐补 CHECK, 拒直连脏值
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_profile_engine_self_monitor') THEN
        ALTER TABLE public.profile_engine_config
            ADD CONSTRAINT ck_profile_engine_self_monitor CHECK (self_monitor IN ('0', '1'));
    END IF;
END $$;

COMMIT;
