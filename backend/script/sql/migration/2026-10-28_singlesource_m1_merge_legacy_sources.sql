-- 单源重构 M1 E 片(2026-10-28): 存量非 dcs 源的身份链接与首见记录合并到统一源 dcs。
-- 背景: B 片三席 dspro P1——单源化后 identity_link/首见表按 (tenant, source) 键控,
-- 旧源(web/b10src 等)的行成为孤儿: 同一用户键的首见丢档 → 老同事被当新用户;
-- 身份链接断 → 匿名到登录的归并链失效。
-- 合并范围刻意收窄: 只搬「身份与首见」两表; 事件/会话/日汇总按源冻结不合并
-- (合并=同一个人两源计数翻倍), 旧源分析数据仍可按源查询, 发布说明写明「旧源派生数据冻结」。
-- 三席回修: ①首见只搬「在 dcs 事件账本里真实出现过」的键——web 匿名 cookie 键没有 dcs 事件,
--   搬过去会以幽灵身份混进「新同事」分母; ②ON CONFLICT 钉到业务唯一键——不带目标的 DO NOTHING
--   连主键撞车也静默吞行(link_id 手工编号与雪花并发同毫秒可撞), 钉死后 PK 撞车变成显式报错。
-- 执行须知: 建议停机窗口执行(避免与运行中的应用并发写 identity_link);
-- 执行后对 dcs 源跑一次重建汇总(POST /analytics/rebuild?source=dcs&days=365), 否则会话/日汇总仍是旧口径。
-- 可重复执行: 两段均幂等(冲突保留 dcs 原行 / 首见只取更早)。

-- 1) 用户首见: 只搬在 dcs 事件账本真实出现过的键, 已存在时保留更早时间(首次使用就该取最早一次)
INSERT INTO analytics_visitor_first_seen (tenant_id, source, visitor_key, first_seen)
SELECT fs.tenant_id, 'dcs', fs.visitor_key, fs.first_seen
FROM analytics_visitor_first_seen fs
WHERE fs.source <> 'dcs'
  AND EXISTS (SELECT 1 FROM profile_event pe
      WHERE pe.tenant_id = fs.tenant_id AND pe.source = 'dcs' AND pe.del_flag = '0'
        AND COALESCE(NULLIF(pe.anonymous_id, ''), pe.subject_id) = fs.visitor_key)
ON CONFLICT (tenant_id, source, visitor_key)
DO UPDATE SET first_seen = LEAST(analytics_visitor_first_seen.first_seen, EXCLUDED.first_seen);

-- 2) 身份链接: 复制到 dcs(同键已存在则保留 dcs 原行)。
-- link_id 无库内序列(应用侧雪花)——用 max+row_number 生成不与存量冲突的新 id
INSERT INTO profile_identity_link (link_id, tenant_id, source, subject_type, subject_id,
    identifier_type, identifier_value, identifier_hash, confidence, is_secure, status,
    first_seen, last_seen, create_time, update_time)
SELECT (SELECT COALESCE(max(l2.link_id), 0) FROM profile_identity_link l2) + row_number() OVER (ORDER BY link_id),
       tenant_id, 'dcs', subject_type, subject_id,
       identifier_type, identifier_value, identifier_hash, confidence, is_secure, status,
       first_seen, last_seen, now(), now()
FROM profile_identity_link
WHERE source <> 'dcs'
ON CONFLICT (tenant_id, source, subject_type, identifier_type, identifier_value) DO NOTHING;
