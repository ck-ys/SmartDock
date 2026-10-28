-- 单源重构 M1 E 片三席回修(2026-10-28): 首见物化表回填 + 幽灵行清理 + 用户键表达式索引。
-- 背景(dspro P1): 首见表只有「事件写入时增量 upsert」一个写入点——
-- C 片期间自监控事件被守卫挡在首见表外, E 片守卫反转后没有任何代码补这批行,
-- 升级当天存量老同事会因首见表无行而被当成「新同事」; 反向, 删除事件/演示数据清理
-- 不回删首见行, 留下无事件支撑的幽灵行混进新同事分母。
-- 文件名以 seen_backfill 排在 merge_legacy_sources 之后: 清理段要能收掉合并段可能引入的幽灵行。
-- 可重复执行: 三段均幂等。执行后建议对 dcs 源跑一次重建汇总。

-- 1) 回填: 首见=该用户键在事件账本里的最早一次(含自监控), 只降不升
INSERT INTO analytics_visitor_first_seen (tenant_id, source, visitor_key, first_seen)
SELECT tenant_id, source, COALESCE(NULLIF(anonymous_id, ''), subject_id), min(occurred_time)
FROM profile_event
WHERE del_flag = '0'
GROUP BY 1, 2, 3
ON CONFLICT (tenant_id, source, visitor_key)
DO UPDATE SET first_seen = LEAST(analytics_visitor_first_seen.first_seen, EXCLUDED.first_seen);

-- 2) 用户键表达式索引(dspro P1): 采用度查询(周活/两步上手/首周激活)与按人取事件都走
--    COALESCE(NULLIF(anonymous_id,''), subject_id) 用户键, 活表此前无该键任何索引——
--    大租户首屏 KPI 会退化成逐行过滤。表达式与读侧逐字一致(空格不影响 PG 解析树匹配)。
-- 同名陷阱: 2026-10-25 起的存量库已带旧版 idx_profile_event_visitor(anonymous_id 键)，
-- CREATE INDEX IF NOT EXISTS 只看名字不看定义——不先 DROP 的话新表达式索引会静默跳过，
-- 大租户采用度查询继续全表扫。DROP 后立即重建，重复执行结果不变。
-- (批末 dspro P2-4: 本段排在幽灵行清理之前——清理段的 NOT EXISTS 按 COALESCE 用户键比对,
--  要吃到这里刚建好的表达式索引, 否则大租户逐行全表扫)
DROP INDEX IF EXISTS idx_profile_event_visitor;
CREATE INDEX idx_profile_event_visitor
    ON public.profile_event (tenant_id, source,
        (COALESCE(NULLIF(anonymous_id, ''), subject_id)), event_name, occurred_time)
    WHERE del_flag = '0';

-- 3) 清理: 删掉无事件支撑的幽灵行(测试/演示数据删了事件但首见残留; 旧合并段搬来的无 dcs 事件键)
DELETE FROM analytics_visitor_first_seen fs
WHERE NOT EXISTS (SELECT 1 FROM profile_event pe
    WHERE pe.tenant_id = fs.tenant_id AND pe.source = fs.source AND pe.del_flag = '0'
      AND COALESCE(NULLIF(pe.anonymous_id, ''), pe.subject_id) = fs.visitor_key);
