-- 2026-10-20 AI 助手九题快刀批（拍板页 20261020 全按推荐）
-- Q6 助手称呼配置列 + Q9 MiniMax 国内站预置种子
-- 幂等，可重复执行；与 baseline 同源双写（01_schema.sql / 02_reference.sql）。

BEGIN;

-- Q6：助手对外称呼（空=平台默认「SmartDock 小助手」；管理台可改，读侧两级默认合并）
ALTER TABLE public.dcs_assistant_config
    ADD COLUMN IF NOT EXISTS assistant_name character varying(50);

-- Q9：MiniMax 预置切国内站——文档域名白名单补 api.minimax.cn 与 platform.minimax.cn
-- （枚举 baseUrl 已改 https://api.minimax.cn/v1，官方文档现行端点；与 baseline/02_reference 同值）
INSERT INTO public.dcs_ai_doc_domain (domain_id, tenant_id, domain, provider, remark, enabled, create_dept, create_by, create_time, update_by, update_time, del_flag) VALUES
    (2110200000000000001, '000000', 'api.minimax.cn', 'MINIMAX', 'MiniMax 国内站 API（快刀批 Q9）', '1', 103, 1, '2026-10-20 12:00:00', NULL, NULL, '0'),
    (2110200000000000002, '000000', 'platform.minimax.cn', 'MINIMAX', 'MiniMax 国内站开放平台（快刀批 Q9）', '1', 103, 1, '2026-10-20 12:00:00', NULL, NULL, '0')
ON CONFLICT (domain_id) DO NOTHING;

-- Q9：MiniMax-M3.1-Flash 人民币价格种子（默认模型升级的计费配套；CNY 计价是硬约束，
-- 计费链 CNY 闸会过滤非 CNY 行——官方价 ¥1 / ¥0.1（缓存）/ ¥4 每百万 token；
-- 与 baseline/02_reference 同值）
INSERT INTO public.dcs_ai_model_price (price_id, tenant_id, provider, model_name, currency, input_price_per_million, cached_input_price_per_million, output_price_per_million, source_url, enabled, remark, create_dept, create_by, create_time, update_by, update_time, del_flag, pricing_region, pricing_tier_code, input_token_min, input_token_max, billing_formula, pricing_schema_json, source_updated_at) VALUES
    (900000000000003105, '000000', 'MINIMAX', 'MiniMax-M3.1-Flash', 'CNY', 1.000000, 0.100000, 4.000000, 'https://platform.minimax.cn/docs/guides/pricing', '1', 'MiniMax 国内站定价种子（快刀批 Q9）；人民币计价是硬约束——计费链 CNY 闸会过滤非 CNY 行', NULL, NULL, '2026-10-20 12:00:00', NULL, NULL, '0', NULL, 'DEFAULT', NULL, NULL, 'INPUT_OUTPUT_PER_MILLION', NULL, NULL)
ON CONFLICT (price_id) DO NOTHING;

COMMIT;
