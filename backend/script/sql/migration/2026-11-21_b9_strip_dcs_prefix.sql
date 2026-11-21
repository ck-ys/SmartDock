-- 2026-11-21_b9_strip_dcs_prefix.sql
-- 批 9A: 去 dcs 前缀硬切(存量环境)。新环境直接用 baseline, 不跑本文件。
-- 幂等: 全部带 IF EXISTS/条件守卫; 重复执行不报错。
-- 白名单(表名不改): dcs_changelog(公告式更新日志表) / dcs_mid(另一 schema) / dcs_assistant_conversation_title_bak_*(备份表)
--   白名单只锁表名; 其约束/索引/序列仍按前缀归一(pk_dcs_changelog→pk_changelog 恰与新 baseline 命名一致)

BEGIN;
SET lock_timeout = '5s'; -- R2 P2-6: ~60 张表 ACCESS EXCLUSIVE, 排队超 5s 快速失败防堆积
SET lock_timeout = '5s'; -- R2 P2-6: ~60 张表 ACCESS EXCLUSIVE, 排队超 5s 快速失败防堆积

-- ── 1) 表改名 (60 张) ──
ALTER TABLE IF EXISTS agentscope.dcs_p0_probe_audit RENAME TO p0_probe_audit;
ALTER TABLE IF EXISTS agentscope.dcs_p0_probe_ledger RENAME TO p0_probe_ledger;
ALTER TABLE IF EXISTS agentscope.dcs_p0_probe_run_registry RENAME TO p0_probe_run_registry;
ALTER TABLE IF EXISTS agentscope.dcs_p0_probe_usage RENAME TO p0_probe_usage;
ALTER TABLE IF EXISTS agentscope.dcs_p0_probe_wallet RENAME TO p0_probe_wallet;
ALTER TABLE IF EXISTS public.dcs_ai_config RENAME TO ai_config;
ALTER TABLE IF EXISTS public.dcs_ai_credit_ledger RENAME TO ai_credit_ledger;
ALTER TABLE IF EXISTS public.dcs_ai_credit_plan RENAME TO ai_credit_plan;
ALTER TABLE IF EXISTS public.dcs_ai_credit_subscription RENAME TO ai_credit_subscription;
ALTER TABLE IF EXISTS public.dcs_ai_doc_domain RENAME TO ai_doc_domain;
ALTER TABLE IF EXISTS public.dcs_ai_model_capability RENAME TO ai_model_capability;
ALTER TABLE IF EXISTS public.dcs_ai_model_health RENAME TO ai_model_health;
ALTER TABLE IF EXISTS public.dcs_ai_model_price RENAME TO ai_model_price;
ALTER TABLE IF EXISTS public.dcs_ai_provider_access RENAME TO ai_provider_access;
ALTER TABLE IF EXISTS public.dcs_ai_usage RENAME TO ai_usage;
ALTER TABLE IF EXISTS public.dcs_assistant_action RENAME TO assistant_action;
ALTER TABLE IF EXISTS public.dcs_assistant_announcement RENAME TO assistant_announcement;
ALTER TABLE IF EXISTS public.dcs_assistant_attachment RENAME TO assistant_attachment;
ALTER TABLE IF EXISTS public.dcs_assistant_compaction RENAME TO assistant_compaction;
ALTER TABLE IF EXISTS public.dcs_assistant_config RENAME TO assistant_config;
ALTER TABLE IF EXISTS public.dcs_assistant_conversation RENAME TO assistant_conversation;
ALTER TABLE IF EXISTS public.dcs_assistant_favorite RENAME TO assistant_favorite;
ALTER TABLE IF EXISTS public.dcs_assistant_feedback RENAME TO assistant_feedback;
ALTER TABLE IF EXISTS public.dcs_assistant_memory RENAME TO assistant_memory;
ALTER TABLE IF EXISTS public.dcs_assistant_memory_pref RENAME TO assistant_memory_pref;
ALTER TABLE IF EXISTS public.dcs_assistant_message RENAME TO assistant_message;
ALTER TABLE IF EXISTS public.dcs_assistant_model_setting RENAME TO assistant_model_setting;
ALTER TABLE IF EXISTS public.dcs_assistant_run RENAME TO assistant_run;
ALTER TABLE IF EXISTS public.dcs_assistant_run_issue RENAME TO assistant_run_issue;
ALTER TABLE IF EXISTS public.dcs_assistant_skill RENAME TO assistant_skill;
ALTER TABLE IF EXISTS public.dcs_assistant_skill_usage RENAME TO assistant_skill_usage;
ALTER TABLE IF EXISTS public.dcs_assistant_user_pref RENAME TO assistant_user_pref;
ALTER TABLE IF EXISTS public.dcs_assistant_user_quota RENAME TO assistant_user_quota;
ALTER TABLE IF EXISTS public.dcs_audit RENAME TO audit;
ALTER TABLE IF EXISTS public.dcs_automation RENAME TO automation;
ALTER TABLE IF EXISTS public.dcs_automation_run RENAME TO automation_run;
ALTER TABLE IF EXISTS public.dcs_captcha_log RENAME TO captcha_log;
ALTER TABLE IF EXISTS public.dcs_feedback RENAME TO feedback;
ALTER TABLE IF EXISTS public.dcs_file_guard_log RENAME TO file_guard_log;
ALTER TABLE IF EXISTS public.dcs_file_review RENAME TO file_review;
ALTER TABLE IF EXISTS public.dcs_file_review_config RENAME TO file_review_config;
ALTER TABLE IF EXISTS public.dcs_mcp_call_log RENAME TO mcp_call_log;
ALTER TABLE IF EXISTS public.dcs_mcp_server RENAME TO mcp_server;
ALTER TABLE IF EXISTS public.dcs_notice RENAME TO notice;
ALTER TABLE IF EXISTS public.dcs_profile_review RENAME TO profile_review;
ALTER TABLE IF EXISTS public.dcs_risk_action RENAME TO risk_action;
ALTER TABLE IF EXISTS public.dcs_risk_event RENAME TO risk_event;
ALTER TABLE IF EXISTS public.dcs_risk_rule RENAME TO risk_rule;
ALTER TABLE IF EXISTS public.dcs_risk_whitelist RENAME TO risk_whitelist;
ALTER TABLE IF EXISTS public.dcs_sensitive_word RENAME TO sensitive_word;
ALTER TABLE IF EXISTS public.dcs_service_charge_rule RENAME TO service_charge_rule;
ALTER TABLE IF EXISTS public.dcs_submission RENAME TO submission;
ALTER TABLE IF EXISTS public.dcs_task RENAME TO task;
ALTER TABLE IF EXISTS public.dcs_task_field RENAME TO task_field;
ALTER TABLE IF EXISTS public.dcs_tenant_billing_mode RENAME TO tenant_billing_mode;
ALTER TABLE IF EXISTS public.dcs_tenant_wallet RENAME TO tenant_wallet;
ALTER TABLE IF EXISTS public.dcs_tenant_wallet_ledger RENAME TO tenant_wallet_ledger;
ALTER TABLE IF EXISTS public.dcs_wallet_alert_state RENAME TO wallet_alert_state;
ALTER TABLE IF EXISTS public.dcs_wallet_payment_order RENAME TO wallet_payment_order;
ALTER TABLE IF EXISTS public.dcs_word_fp_feedback RENAME TO word_fp_feedback;

-- ── 1b) 特例: 旧表 profile_review(原 dcs_profile_review) 的历史索引名带 queue 字样,
--      直译会与新表 profile_review_queue 的同名索引相撞, 贴表名改为 idx_profile_review
--      (R1 并发席 P2-1: 源/目标双侧守卫——目标名已占用时跳过, 不让 42710 把整份事务回滚)
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_dcs_profile_review_queue')
     AND NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_profile_review') THEN
    ALTER INDEX public.idx_dcs_profile_review_queue RENAME TO idx_profile_review;
  END IF;
END $$;

-- ── 2) 约束改名(动态循环: 显式命名 + PG 自动 {table}_pkey; 撞名跳过保幂等) ──
DO $$
DECLARE r record; newn text;
BEGIN
  FOR r IN SELECT c.conrelid::regclass AS tbl, c.conname
           FROM pg_constraint c JOIN pg_class cc ON cc.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = cc.relnamespace
           WHERE n.nspname IN ('public', 'agentscope') AND c.conname LIKE 'dcs\_%' LOOP
    newn := substring(r.conname from 5);
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = newn AND conrelid = r.tbl) THEN
      EXECUTE format('ALTER TABLE %s RENAME CONSTRAINT %I TO %I', r.tbl, r.conname, newn);
    END IF;
  END LOOP;
  FOR r IN SELECT c.conrelid::regclass AS tbl, c.conname
           FROM pg_constraint c JOIN pg_class cc ON cc.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = cc.relnamespace
           WHERE n.nspname = 'public' AND c.conname LIKE '%\_dcs\_%' LOOP
    newn := replace(r.conname, '_dcs_', '_');
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = newn AND conrelid = r.tbl) THEN
      EXECUTE format('ALTER TABLE %s RENAME CONSTRAINT %I TO %I', r.tbl, r.conname, newn);
    END IF;
  END LOOP;
END $$;

-- ── 3) 索引改名(约束自有索引随约束走, 这里收独立索引; 撞名跳过) ──
DO $$
DECLARE r record; newn text;
BEGIN
  FOR r IN SELECT schemaname, indexname FROM pg_indexes WHERE schemaname IN ('public', 'agentscope')
           AND (indexname LIKE 'dcs\_%' OR indexname LIKE 'idx\_dcs\_%' OR indexname LIKE 'uk\_dcs\_%') LOOP
    newn := CASE WHEN r.indexname LIKE 'dcs\_%' THEN substring(r.indexname from 5)
                 ELSE replace(r.indexname, '_dcs_', '_') END;
    IF NOT EXISTS (SELECT 1 FROM pg_indexes WHERE schemaname = r.schemaname AND indexname = newn) THEN
      EXECUTE format('ALTER INDEX %I.%I RENAME TO %I', r.schemaname, r.indexname, newn);
    END IF;
  END LOOP;
END $$;

-- ── 3b) 序列改名(R1 并发席 P2-2: RENAME TABLE 不带走属主序列, 实测残留
--      dcs_ai_usage_usage_id_seq / dcs_p0_probe_*_id_seq; 撞名跳过) ──
DO $$
DECLARE r record; newn text;
BEGIN
  FOR r IN SELECT n.nspname AS schemaname, c.relname AS seqname
           FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
           WHERE c.relkind = 'S' AND n.nspname IN ('public', 'agentscope') AND c.relname LIKE 'dcs\_%' LOOP
    newn := substring(r.seqname from 5);
    IF NOT EXISTS (SELECT 1 FROM pg_class cc JOIN pg_namespace nn ON nn.oid = cc.relnamespace
                   WHERE cc.relname = newn AND nn.nspname = r.schemaname) THEN
      EXECUTE format('ALTER SEQUENCE %I.%I RENAME TO %I', r.schemaname, r.seqname, newn);
    END IF;
  END LOOP;
END $$;

-- ── 4) sys_menu: 权限串/组件路径/目录路由 ──
UPDATE sys_menu SET perms = 'smartdock:' || substring(perms from 5) WHERE perms LIKE 'dcs:%';
UPDATE sys_menu SET component = 'smartdock/' || substring(component from 5) WHERE component LIKE 'dcs/%';
UPDATE sys_menu SET path = 'smartdock' WHERE path = 'dcs';

-- ── 5) sys_config: 配置键(同租户已存在去前缀键时跳过该行, 防 uk_sys_config_tenant_key 撞键回滚整单;
--      R1 并发席 P2-4) ──
UPDATE sys_config t SET config_key = substring(t.config_key from 5)
WHERE t.config_key LIKE 'dcs.%'
  AND NOT EXISTS (SELECT 1 FROM sys_config n
                  WHERE n.tenant_id = t.tenant_id AND n.config_key = substring(t.config_key from 5)
                    AND n.config_id <> t.config_id);

-- ── 6) sys_dict_type / sys_dict_data: 字典类型键(与代码 useDict 同步改名) ──
UPDATE sys_dict_type t SET dict_type = substring(t.dict_type from 5)
WHERE t.dict_type LIKE 'dcs\_%'
  AND NOT EXISTS (SELECT 1 FROM sys_dict_type n
                  WHERE n.tenant_id = t.tenant_id AND n.dict_type = substring(t.dict_type from 5)
                    AND n.dict_id <> t.dict_id);
UPDATE sys_dict_data t SET dict_type = substring(t.dict_type from 5) WHERE t.dict_type LIKE 'dcs\_%';

COMMIT;
