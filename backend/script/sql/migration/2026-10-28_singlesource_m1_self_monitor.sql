-- 单源重构 C 片(题1): 引擎配置加自监控总开关。
-- '1'=记录本产品用户登录与站内核心操作画像事件(默认, 静默发布不触发打分/汇总);
-- '0'=停用(画像页同屏开关, D 片出 UI; 只停新增, 已有数据保留)。可重复执行。
BEGIN;

ALTER TABLE public.profile_engine_config
    ADD COLUMN IF NOT EXISTS self_monitor char(1) DEFAULT '1'::bpchar NOT NULL;

COMMIT;
