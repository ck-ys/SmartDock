-- 2026-10-21 批1 钥匙管理：标准厂商空 base_url 补官方默认地址
-- 背景：批0 实证——空 base_url 的配置行会让运行时静默回退框架默认 api.openai.com
-- （本网络被 DNS 污染=30s 建连超时）。后端已改为保存时自动补齐（DcsAiProvider.defaultBaseUrl，
-- updateAccess/addKey/upsert 三处同口径），本迁移修复存量空地址行。
-- 口径：与后端枚举 DcsAiProvider 完全一致；CUSTOM 厂商不补（必须手填）。
-- 幂等可重跑：仅命中空地址行，补过即不再命中。

UPDATE dcs_ai_config SET base_url = 'https://api.openai.com/v1'          WHERE provider = 'OPENAI'          AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://api.deepseek.com/v1'       WHERE provider = 'DEEPSEEK'        AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://dashscope.aliyuncs.com/compatible-mode/v1' WHERE provider = 'QWEN'          AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://open.bigmodel.cn/api/paas/v4'              WHERE provider = 'ZHIPU'         AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://api.moonshot.ai/v1'        WHERE provider = 'KIMI'            AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://qianfan.baidubce.com/v2'   WHERE provider = 'BAIDU_QIANFAN'   AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://api.hunyuan.cloud.tencent.com/v1'           WHERE provider = 'TENCENT_HUNYUAN' AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://spark-api-open.xf-yun.com/v1'              WHERE provider = 'XFYUN_SPARK'   AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://api.minimax.cn/v1'         WHERE provider = 'MINIMAX'        AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://api.siliconflow.cn/v1'     WHERE provider = 'SILICONFLOW'    AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://opencode.ai/zen/go/v1'     WHERE provider = 'OPENCODE'       AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://api.stepfun.com/v1'        WHERE provider = 'STEPFUN'        AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_config SET base_url = 'https://openrouter.ai/api/v1'      WHERE provider = 'OPENROUTER'     AND COALESCE(trim(base_url), '') = '';

UPDATE dcs_ai_provider_access SET base_url = 'https://api.openai.com/v1'          WHERE provider = 'OPENAI'          AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://api.deepseek.com/v1'       WHERE provider = 'DEEPSEEK'        AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://dashscope.aliyuncs.com/compatible-mode/v1' WHERE provider = 'QWEN'          AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://open.bigmodel.cn/api/paas/v4'              WHERE provider = 'ZHIPU'         AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://api.moonshot.ai/v1'        WHERE provider = 'KIMI'            AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://qianfan.baidubce.com/v2'   WHERE provider = 'BAIDU_QIANFAN'   AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://api.hunyuan.cloud.tencent.com/v1'           WHERE provider = 'TENCENT_HUNYUAN' AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://spark-api-open.xf-yun.com/v1'              WHERE provider = 'XFYUN_SPARK'   AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://api.minimax.cn/v1'         WHERE provider = 'MINIMAX'        AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://api.siliconflow.cn/v1'     WHERE provider = 'SILICONFLOW'    AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://opencode.ai/zen/go/v1'     WHERE provider = 'OPENCODE'       AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://api.stepfun.com/v1'        WHERE provider = 'STEPFUN'        AND COALESCE(trim(base_url), '') = '';
UPDATE dcs_ai_provider_access SET base_url = 'https://openrouter.ai/api/v1'      WHERE provider = 'OPENROUTER'     AND COALESCE(trim(base_url), '') = '';
