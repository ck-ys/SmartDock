-- 单源重构第一批 B 片三审回修: 画像主视图/跨源查询形态的专用索引。
-- 背景: 主视图(c74523a)与占位守卫读路径都不带 source 条件, 而既有索引全部以
-- source 为第二列, 前缀无法命中——大租户下退化为顺序扫描。本文件补三条 partial 索引,
-- 与既有索引不重叠(过滤条件/列序不同)。可重复执行(IF NOT EXISTS), 包事务。
BEGIN;

-- 快照按 (租户, 类型, 主体) 直取最新——主视图 LATERAL / scan() / 模型主体页共用形态
CREATE INDEX IF NOT EXISTS idx_profile_snap_xsrc
    ON public.profile_score_snapshot USING btree
    (tenant_id, subject_type, subject_id, calc_time DESC, snapshot_id DESC)
    WHERE (del_flag = '0'::bpchar);

-- 复核队列"该主体是否有待复核"探测——主视图/守卫销案的 EXISTS 形态
CREATE INDEX IF NOT EXISTS idx_review_pending_xsrc
    ON public.profile_review_queue USING btree (tenant_id, subject_type, subject_id)
    WHERE (status = 'PENDING'::text AND del_flag = '0'::bpchar);

-- 主体登记跨源聚合——主视图 GROUP BY subject_id 与身份桥按主体定位的形态
CREATE INDEX IF NOT EXISTS idx_profile_subject_xsrc
    ON public.profile_subject USING btree (tenant_id, subject_type, subject_id)
    WHERE (del_flag = '0'::bpchar);

COMMIT;
