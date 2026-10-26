-- =====================================================================
-- 批5B 日汇总兜底(2026-10-26): 重建留痕 kind 白名单增加 BACKFILL
-- 每日凌晨 AnalyticsDailyBackfillJob 与手工补数接口共用 backfillDaily,
-- 留痕 kind=BACKFILL; 原 CHECK 未含该值导致留痕插入被吞(非致命, 健康页缺行)。
-- 可重复执行: 先 DROP IF EXISTS 再重建同名约束。
-- =====================================================================

ALTER TABLE public.analytics_rebuild_log DROP CONSTRAINT IF EXISTS ck_analytics_rebuild_kind;
ALTER TABLE public.analytics_rebuild_log
    ADD CONSTRAINT ck_analytics_rebuild_kind CHECK (kind IN ('RECENT', 'MANUAL', 'REPLAY', 'UNMERGE', 'DELETION', 'BACKFILL'));
