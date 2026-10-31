-- 2026-10-31 题9：把「先备份再置 NULL」补成真正幂等的备份
-- 背景：2026-10-30 那份迁移用 `CREATE TABLE IF NOT EXISTS … AS SELECT` 建备份表。
--       备份表已存在时 SELECT 不再执行，而随后的 UPDATE 仍会把新命中的行置 NULL ——
--       这些行没有任何备份，与脚本第 10 行「先整表备份命中行，再置 NULL」的注释不一致。
--       正常写入路径已被 AssistantTitleService.cleanTitle 堵住，只有「手动重命名标题里带
--       "title": 字面量」这种极端输入会漏；本脚本把语义补齐，不动已执行过的 10-30 那份。
-- 处置：①备份表若不存在，按与 10-30 迁移完全相同的 5 列形状建一张空表；
--       ②每次执行先把命中行补进备份表（NOT EXISTS 去重，重复执行不会长行）；
--       ③再把命中行置 NULL。
-- 可重复执行：二次执行 INSERT 0 行、UPDATE 0 行；备份表已存在也不会因列数不符报错。

CREATE TABLE IF NOT EXISTS public.dcs_assistant_conversation_title_bak_20261030 AS
SELECT id, tenant_id, thread_id, title, update_time
  FROM public.dcs_assistant_conversation
 WHERE false;

INSERT INTO public.dcs_assistant_conversation_title_bak_20261030 (id, tenant_id, thread_id, title, update_time)
SELECT c.id, c.tenant_id, c.thread_id, c.title, c.update_time
  FROM public.dcs_assistant_conversation c
 WHERE c.title IS NOT NULL
   AND (btrim(c.title) LIKE '{"%'
        OR btrim(c.title) LIKE '[{%'
        OR c.title ~* '"title"\s*:')
   AND NOT EXISTS (
         SELECT 1
           FROM public.dcs_assistant_conversation_title_bak_20261030 b
          WHERE b.id = c.id);

UPDATE public.dcs_assistant_conversation
   SET title = NULL
 WHERE title IS NOT NULL
   AND (btrim(title) LIKE '{"%'
        OR btrim(title) LIKE '[{%'
        OR title ~* '"title"\s*:');
