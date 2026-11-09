-- ============================================================================
-- SmartDock 基线种子 · 开箱演示数据（可选）
-- 一条已发布的日常任务 + 四类字段，让新部署打开「我的待办」就有东西可玩。
-- 前置: 01_schema.sql → 03_identity.sql；可与 04_role_menu.sql 并行（无外键依赖）。
-- 不想带演示数据可跳过本文件，重放 01-04 即是干净空库。
-- ============================================================================

-- 演示任务: 办公用品领用登记（发布人=admin, 面向三名演示成员, 收集中）
INSERT INTO public.dcs_task (id, tenant_id, title, description, publisher_user_id, status, deadline, publish_time, assignee_user_ids, audit_mode, auditor_user_ids, create_dept, create_by, create_time)
VALUES (9200000000000000001, '000000', '办公用品领用登记',
        '本周办公用品领用登记：填写领用物品与数量，管理员汇总后统一采购发放。',
        1, 'COLLECTING', NULL, now(), '[9100000001, 9100000002, 9100000003]'::jsonb,
        'PUBLISHER', '[]'::jsonb, 103, 1, now());

-- 四类字段各一：文本 / 数字 / 单选 / 多选（options 为 JSON 数组）
INSERT INTO public.dcs_task_field (id, tenant_id, task_id, field_code, field_name, field_type, required, options, order_num, create_time) VALUES
  (9200000000000000011, '000000', 9200000000000000001, 'purpose',    '领用用途', 'text',   '1', '[]'::jsonb,                                            1, now()),
  (9200000000000000012, '000000', 9200000000000000001, 'quantity',   '领用数量', 'number', '1', '[]'::jsonb,                                            2, now()),
  (9200000000000000013, '000000', 9200000000000000001, 'item_type',  '物品类型', 'select', '1', '["中性笔", "笔记本", "便签纸", "文件夹", "订书机"]'::jsonb, 3, now()),
  (9200000000000000014, '000000', 9200000000000000001, 'urgency',    '紧急程度', 'multi_select',  '0', '["当天需要", "本周内", "不急"]'::jsonb,                  4, now());
