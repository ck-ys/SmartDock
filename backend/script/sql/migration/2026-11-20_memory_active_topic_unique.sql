-- 批 7 前置 STEP 1（05C 题 3 案 1，D1，2026-11-20 拍板 Q1-1/Q2-1/Q3-1；多审回修 v2）：
-- 同一（租户,人,主题）只能一条生效中记忆。
--
-- 病根：saveReturningId 原先「预读主题清单 → 插入 → 覆盖旧条」三步无事务包裹，
-- 并发保存同主题会留下两条 ACTIVE（批 6 二轮多审登记：D1，事务注解挡不住——
-- 读与写之间没有栅栏，两个事务都能通过「预读时没看见对方」）。
-- 修法三件套（代码侧已在 AssistantMemoryTurnService / AssistantMemoryService 落地）：
--   ① 本文件：库层部分唯一索引（最终栅栏，应用侧漏防也能拦住）；
--   ② 保存口「先腾位后插入」同一事务（TransactionTemplate，REQUIRES_NEW）；
--   ③ 撞唯一索引时重读主题清单重试（上限 3 轮）。
-- 可重复执行（幂等去重 + IF NOT EXISTS）；与 baseline/01_schema.sql 同步。
--
-- 执行约定（多审席2/席3/席4 一致）：
--   · 整文件单事务 + SHARE ROW EXCLUSIVE 锁——去重与建索引之间不留「再写入重复行」的窗口，
--     建索引期间阻塞写入、不阻塞查询；锁前设 lock_timeout=3s，等不到锁快速失败重试，
--     避免被一个卡住的长写事务拖成记忆表写入无限期冻结（二轮席3）；
--   · 低峰/停写执行；滚动部署先摘旧节点（索引建好后旧版节点保存同主题会直接撞 23505 且不重试）；
--   · 顺序照 docs/deploy.md 的「数据库先升级再放流量」。
--   · 执行方式：psql 直接执行（默认 autocommit）。禁止 -1/--single-transaction——文件自带
--     BEGIN/COMMIT，外层事务会被文件内 COMMIT 提前提交，后半段失败回滚不到前半段（二轮席3 实测）；
--     单串多语句执行（如 node pg 一次 query 全文）中途失败会把连接留在 aborted 事务（后续全 25P02），
--     执行器出错请显式 ROLLBACK 或弃连接。
--   · 缺索引时后端会拒绝启动（AssistantMemoryFenceStartupCheck），漏跑会被当场拦下。

BEGIN;

-- 锁等待上限（仅本事务内生效）：等不到锁就快速失败，别排长队
SET LOCAL lock_timeout = '3s';

-- 先锁表再动手：SHARE ROW EXCLUSIVE 阻塞其它写入（允许查询）——
-- 否则「去重提交 → 建索引」之间还有窗口塞进新的重复行，建索引会失败中断迁移
LOCK TABLE public.assistant_memory IN SHARE ROW EXCLUSIVE MODE;

-- 存量去重（幂等，重跑为 0 行）：同 (租户,人,主题) 多条 ACTIVE 只留一条——
-- 保留口径 = COALESCE(update_time, create_time) 最新的（多审席2：max(id) ≠ 最后保存，
-- 行内编辑是原地 UPDATE 不改 id，用 id 会把「编辑过的低 id 行」错杀成 SUPERSEDED），
-- 并列时取 id 大的；其余转 SUPERSEDED 并指向保留条——与保存口「新条覆盖旧条」同语义。
-- 去重不改写 update_time（二轮席3）：它是保留口径的依据列，改写会给「日后把行恢复 ACTIVE 再重跑」
-- 埋翻转/断链坑；留痕用 update_by。
WITH dup AS (
    SELECT id, user_id,
           FIRST_VALUE(id) OVER (
               PARTITION BY tenant_id, user_id, topic_key
               ORDER BY COALESCE(update_time, create_time) DESC NULLS LAST, id DESC
           ) AS keep_id
    FROM public.assistant_memory
    WHERE status = 'ACTIVE' AND topic_key IS NOT NULL AND del_flag = '0'
)
UPDATE public.assistant_memory m
SET status = 'SUPERSEDED', superseded_by = d.keep_id,
    update_by = d.user_id
FROM dup d
WHERE m.id = d.id AND m.id <> d.keep_id;

-- 栅栏本体：生效中条目按 (租户,人,主题) 唯一。
-- topic_key 为空的普通记忆不受限（PG 唯一索引里 NULL 互不相等）；归档/被覆盖条目天然豁免。
CREATE UNIQUE INDEX IF NOT EXISTS uk_asst_memory_active_topic
    ON public.assistant_memory USING btree (tenant_id, user_id, topic_key)
    WHERE status = 'ACTIVE' AND topic_key IS NOT NULL AND del_flag = '0';

COMMIT;
