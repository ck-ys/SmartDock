-- 2026-10-23 拍板：移除外部网站接入能力（公开采集口/SDK/siteKey 全链下线）
-- 系统回归内部数据源：业务系统经 ProfileEventPublisher 喂事件，离线数据走 CSV 补录。
-- 本迁移可重复执行：仅删除外部采集专属的两张表（幂等）。
DROP TABLE IF EXISTS public.analytics_site;
DROP TABLE IF EXISTS public.analytics_collect_log;
