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

CREATE TABLE IF NOT EXISTS public.dcs_assistant_conversation_title_bak_20261030 AS
SELECT id, tenant_id, thread_id, title, update_time
  FROM public.dcs_assistant_conversation
 WHERE title IS NOT NULL
   AND (btrim(title) LIKE '{"%'
        OR btrim(title) LIKE '[{%'
        OR title ~* '"title"\s*:');

UPDATE public.dcs_assistant_conversation
   SET title = NULL
 WHERE title IS NOT NULL
   AND (btrim(title) LIKE '{"%'
        OR btrim(title) LIKE '[{%'
        OR title ~* '"title"\s*:');
