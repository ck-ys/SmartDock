-- 2026-10-30 题3：AI 会话标题残片存量清洗
-- 背景：模型流式输出被截断时，标题侧车会把半截 JSON（如 {"title":"按周复盘适合的）当标题收下，
--       写回走 updateTitleIfMatch 的 CAS，脏标题一旦落库就再也不会被清洗逻辑碰到（永不自愈）。
--       写入端已在 AssistantTitleService.cleanTitle 堵住（JSON 残片直接放弃），本脚本只清历史残留。
-- 判据：只认「结构性残片」——以 {" 开头的对象、以 [{ 开头的数组，或正文含 "title" 键。
--       注意不能用「以 [ 开头」一刀切：重命名接口允许任意 1-60 字标题，
--       用户手写的 [草稿] 周报 / [重要] 客户投诉 会被无差别清空（三审 P1 实锤）。
--       同理裸引号开头的中文标题（"重要"客户）也不在清洗范围内——宁可留一条脏标题，
--       也不能不可逆地删掉用户自己写的东西。
-- 处置：先整表备份命中行，再置 NULL（不是改写成人话）；前端按「（未命名会话·MM-DD）」兜底展示。
-- 可重复执行：清洗后不再命中，二次执行影响 0 行；备份表已存在则跳过建表。
-- 2026-11-01 补（全量重放会漏备）：只靠上面那句 CTAS，备份表已存在时 SELECT 不再执行，
--       而随后的 UPDATE 仍把新命中行置 NULL —— 这些行没有任何备份。这里补一段与
--       2026-10-31 那份完全相同的「每次先补备份、按 id+标题去重」的 INSERT，谁先跑都不漏。
--       备份表是 CTAS 建的表（本份带命中行、10-31 那份建空表），没有主键，防重复全靠下面
--       INSERT 的 NOT EXISTS（不是撞键报错）。并发同时跑两份脚本的极端窗口仍可能插出重复备份行
--       （只多备份、不影响清洗正确性），属已知取舍。
-- 事务：整段包在 BEGIN/COMMIT 里——psql 默认遇错继续，若不包事务，备份 INSERT 失败后 UPDATE 仍会把行置 NULL，
--       又回到「清了不备」。包上之后任一句失败即整体回滚。

BEGIN;

CREATE TABLE IF NOT EXISTS public.dcs_assistant_conversation_title_bak_20261030 AS
SELECT id, tenant_id, thread_id, title, update_time
  FROM public.dcs_assistant_conversation
 WHERE title IS NOT NULL
   AND (btrim(title) LIKE '{"%'
        OR btrim(title) LIKE '[{%'
        OR title ~* '"title"\s*:');

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
          WHERE b.id = c.id
            AND b.title IS NOT DISTINCT FROM c.title);

UPDATE public.dcs_assistant_conversation
   SET title = NULL
 WHERE title IS NOT NULL
   AND (btrim(title) LIKE '{"%'
        OR btrim(title) LIKE '[{%'
        OR title ~* '"title"\s*:');

COMMIT;
