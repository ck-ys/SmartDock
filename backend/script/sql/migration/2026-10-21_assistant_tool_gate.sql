-- 2026-10-21 批1 Q1=B（开关矩阵）：dcs_assistant_config 新增 tool_gate 列
-- 存租户级工具档位覆盖 JSON（工具名→off/member/admin），空=全部按内置门槛。
-- text 存 JSON 字符串（避开 jsonb ::cast 写入链），读侧 AssistantConfigService.parseToolGate 容错解析。
-- 幂等可重跑。

ALTER TABLE public.dcs_assistant_config ADD COLUMN IF NOT EXISTS tool_gate text;
