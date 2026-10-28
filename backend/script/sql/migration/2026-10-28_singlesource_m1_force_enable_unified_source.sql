-- 单源重构 M1 D 片(2026-10-28): 「来源管理」页签已随单源口径退役, 页面上不再存在
-- 任何启用入口。若租户曾用旧界面把统一源 dcs 停用(enabled='0'), 升级后画像/分析
-- 会拿空源清单且无处恢复——存量停用行一律恢复启用。可重复执行(第二遍 0 行)。
UPDATE profile_source_config
SET enabled    = '1',
    update_time = now()
WHERE source = 'dcs'
  AND enabled = '0';
