-- 2026-10-30 题6：删除框架自带的 2018 年演示公告
-- 背景：RuoYi 模板在 sys_notice 里塞了两条假数据（「温馨提醒：2018-07-01 新版本发布啦」/
--       「维护通知：2018-07-01 系统凌晨维护」）。开源用户第一次点开「系统支持 → 公告」
--       看到的就是这两条 8 年前的模板文案，像系统没装好。基线已同步删除（02_reference.sql）。
-- 判据：同时满足「id 是模板自带的 1/2」+「标题含 2018-07-01」+「默认租户」才删——
--       只按 id 删会误伤真实环境里自己建的 1/2 号公告。
-- 连带：sys_notice_read 没有外键，公告删了读态会留下孤儿行（演示库实测 1 行），
--       同步清掉「对应公告已不存在」的读态。
-- 可重复执行：删过就不再有命中行。

DELETE FROM public.sys_notice
 WHERE notice_id IN (1, 2)
   AND tenant_id = '000000'
   AND notice_title LIKE '%2018-07-01%';

DELETE FROM public.sys_notice_read r
 WHERE r.notice_id IN (1, 2)
   AND NOT EXISTS (SELECT 1 FROM public.sys_notice n WHERE n.notice_id = r.notice_id);
