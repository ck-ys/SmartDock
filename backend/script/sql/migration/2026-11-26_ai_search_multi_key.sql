-- 联网搜索支持多把钥匙（2026-11-26 拍板板 Q2=1）
--
-- 背景：ai_search_service 建表时用了唯一索引，导致「一个租户 + 一个厂商只能一行」，
--       想挂第二把博查钥匙会被数据库直接拒绝（duplicate key）。
--       模型钥匙表 ai_provider_access 用的是普通索引，同厂商可挂多把，这里对齐它。
--
-- 改动：唯一索引 → 普通索引。索引名一并换掉（uk_ → idx_），让「曾经是唯一」这件事
--       在库结构上不再有误导性；同时保留 tenant_id+provider 的查询加速能力。
--
-- 主钥匙口径：enabled 行按 id 升序，id 最小（最先建）的那把为主钥匙。
--             与 ProviderKeyPool 的轮询起点、回退口径一致，不额外加 is_primary 字段。
--
-- 存量数据：2026-11-26 查正式库 ai_search_service 为 0 行，不存在唯一冲突。
--           若目标环境已有数据，普通索引同样兼容（不会失败、不需要清洗）。
--
-- 可重复执行：DROP ... IF EXISTS / CREATE INDEX ... IF NOT EXISTS。

DROP INDEX IF EXISTS public.uk_ai_search_service_tenant_provider;

CREATE INDEX IF NOT EXISTS idx_ai_search_service_tp
    ON public.ai_search_service USING btree (tenant_id, provider)
    WHERE (del_flag = '0'::bpchar);

COMMENT ON INDEX public.idx_ai_search_service_tp IS
    '联网搜索服务：租户+厂商查询加速（非唯一——同厂商可挂多把钥匙，主钥匙=id 最小）';
