-- 单源重构 M1 D 片(消 B 片夜审登记的存量 P2): 97ad78f 之前的未配置维度快照
-- 形状是 '{}' 且总分恒 50——读侧(画像主列表/依据抽屉/总览 KPI)按 _noDimensions
-- 标记识别占位, 不补标记这些行会继续冒充真分数、其引发的 PENDING 复核永远挂队。
-- 与引擎侧 supersedeIfPending 同列约定(status/review_note/reviewed_at/update_time)。可重复执行。
-- 批末终审(glm53 P2)登记: 另一残留形状「维度非空但全跳过」(STRATEGY 维度 bean 失效期的
-- 老快照, detail 含 "skipped":true 条目而非 '{}')不在本迁移覆盖——两库实测 0 行,
-- 且全跳过检测需逐维度 JSON 判定, 复杂度大于收益。升级前可自查:
--   SELECT count(*) FROM profile_score_snapshot WHERE del_flag='0' AND total_score=50
--     AND dimension_detail::text LIKE '%"skipped":true%' AND NOT dimension_detail::text LIKE '%_noDimensions%';
-- 非 0 的库把这些行的 dimension_detail 手工置 '{"_noDimensions": true}'::jsonb 即可归一。

UPDATE profile_score_snapshot
   SET dimension_detail = '{"_noDimensions": true}'::jsonb
 WHERE del_flag = '0'
   AND total_score = 50
   AND dimension_detail = '{}'::jsonb;

UPDATE profile_review_queue q
   SET status = 'SUPERSEDED',
       review_note = '未配置打分口径的占位分数，补标记后自动销案',
       reviewed_at = CURRENT_TIMESTAMP,
       update_time = CURRENT_TIMESTAMP
  FROM (
    SELECT DISTINCT ON (tenant_id, source, subject_type, subject_id)
           tenant_id, source, subject_type, subject_id, dimension_detail
      FROM profile_score_snapshot
     WHERE del_flag = '0'
     ORDER BY tenant_id, source, subject_type, subject_id, calc_time DESC, snapshot_id DESC
  ) latest
 WHERE q.tenant_id = latest.tenant_id
   AND q.source = latest.source
   AND q.subject_type = latest.subject_type
   AND q.subject_id = latest.subject_id
   AND latest.dimension_detail @> '{"_noDimensions": true}'::jsonb
   AND q.status = 'PENDING'
   AND q.del_flag = '0';
