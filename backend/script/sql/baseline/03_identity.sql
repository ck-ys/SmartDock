-- ============================================================================
-- SmartDock 基线种子 · 核心身份块（手工合成）
-- 机构/人员/租户/OSS/钱包 全部为演示数据，不含任何真实单位与个人信息。
-- 前置: 01_schema.sql; 同文件序执行: 02_reference.sql → 04_role_menu.sql
-- 默认口令: admin123（admin 与 user001~user003）
-- ============================================================================

-- 租户: 仅默认演示租户
INSERT INTO sys_tenant (id, tenant_id, contact_user_name, contact_phone, company_name, license_number, address, intro, domain, remark, package_id, expire_time, account_count, status, del_flag, create_dept, create_by, create_time, update_by, update_time, responsibility_enabled)
VALUES (1, '000000', '演示管理员', '13800000000', '演示单位', '0', '演示地址', 'SmartDock 演示租户', NULL, NULL, NULL, NULL, 100, '0', '0', 103, 1, now(), NULL, NULL, '0');

-- 部门: 合成组织树（演示单位 → 职能部门 → 业务组）
INSERT INTO sys_dept (dept_id, tenant_id, parent_id, ancestors, dept_name, dept_category, order_num, leader, phone, email, status, del_flag, create_dept, create_by, create_time)
VALUES
  (100, '000000', 0,   '0',           '演示单位',   '01', 0, NULL, NULL, NULL, '0', '0', 103, 1, now()),
  (200, '000000', 100, '0,100',       '综合管理部', '02', 1, NULL, NULL, NULL, '0', '0', 103, 1, now()),
  (201, '000000', 200, '0,100,200',   '信息技术部', '02', 1, NULL, NULL, NULL, '0', '0', 103, 1, now()),
  (202, '000000', 200, '0,100,200',   '产品研发部', '02', 2, NULL, NULL, NULL, '0', '0', 103, 1, now()),
  (300, '000000', 100, '0,100',       '市场运营部', '03', 2, NULL, NULL, NULL, '0', '0', 103, 1, now());

-- 用户: admin(超管) + 三名演示成员（口令均为 admin123, BCrypt）
INSERT INTO sys_user (user_id, tenant_id, dept_id, user_name, nick_name, user_type, email, phonenumber, sex, avatar, password, status, del_flag, login_ip, create_dept, create_by, create_time)
VALUES
  (1,           '000000', 100, 'admin',  '管理员',  'sys_user', 'admin@smartdock.example', '13800000001', '1', NULL, '$2a$10$8lSEujW0TtEQN4FwxZc6t.pq9D6bjHCQW/2lQrnUyHjIVny6kzS0O', '0', '0', NULL, 103, 1, now()),
  (9100000001,  '000000', 201, 'user001', '张三', 'sys_user', 'user001@smartdock.example', NULL, '0', NULL, '$2a$10$8lSEujW0TtEQN4FwxZc6t.pq9D6bjHCQW/2lQrnUyHjIVny6kzS0O', '0', '0', NULL, 103, 1, now()),
  (9100000002,  '000000', 201, 'user002', '李四', 'sys_user', 'user002@smartdock.example', NULL, '0', NULL, '$2a$10$8lSEujW0TtEQN4FwxZc6t.pq9D6bjHCQW/2lQrnUyHjIVny6kzS0O', '0', '0', NULL, 103, 1, now()),
  (9100000003,  '000000', 202, 'user003', '王五', 'sys_user', 'user003@smartdock.example', NULL, '0', NULL, '$2a$10$8lSEujW0TtEQN4FwxZc6t.pq9D6bjHCQW/2lQrnUyHjIVny6kzS0O', '0', '0', NULL, 103, 1, now());

-- 角色: 5 成员 / 6 租户管理员 / 7 文件审核员（1 超级管理员在 02_seed_tables.sql）
INSERT INTO sys_role (role_id, tenant_id, role_name, role_key, role_sort, data_scope, menu_check_strictly, dept_check_strictly, status, del_flag, create_dept, create_by, create_time, remark)
VALUES
  (5, '000000', '成员',       'member',         5, '5', 'f', 'f', '0', '0', 103, 1, now(), '成员角色: 待办/消息/看板 + AI 助手'),
  (6, '000000', '租户管理员', 'tenant_admin',   6, '2', 'f', 'f', '0', '0', 103, 1, now(), '租户管理员: 成员全部 + 任务管理/审核队列/画像'),
  (7, '000000', '文件审核员', 'file_reviewer',  7, '5', 'f', 'f', '0', '0', 103, 1, now(), '文件安全审核员');

INSERT INTO sys_user_role (user_id, role_id) VALUES
  (1, 1),
  (9100000001, 5),
  (9100000002, 5),
  (9100000003, 5);

-- OSS: 公有云演示配置(minio 指向本机 docker-compose 默认) —— 密钥均为占位
INSERT INTO sys_oss_config (oss_config_id, tenant_id, config_key, access_key, secret_key, bucket_name, prefix, endpoint, domain, is_https, region, status, ext1, remark, create_dept, create_by, create_time)
VALUES
  (1, '000000', 'minio',  'minioadmin',          'minioadmin',          'smartdock', '', '127.0.0.1:9000', '', '0', '', '0', '1', NULL, 103, 1, now()),
  (2, '000000', 'qiniu',  'XXXXXXXXXXXXXXX',     'XXXXXXXXXXXXXXX',     'ruoyi',      '', 's3-cn-north-1.qiniucs.com', '', '0', '', '1', '0', NULL, 103, 1, now()),
  (3, '000000', 'aliyun', 'XXXXXXXXXXXXXXX',     'XXXXXXXXXXXXXXX',     'ruoyi',      '', 'oss-cn-beijing.aliyuncs.com', '', '0', '', '1', '0', NULL, 103, 1, now()),
  (4, '000000', 'qcloud', 'XXXXXXXXXXXXXXX',     'XXXXXXXXXXXXXXX',     'ruoyi',      '', 'cos.ap-beijing.myqcloud.com', '', '0', '', '1', '0', NULL, 103, 1, now());

-- 租户主钱包: 零余额起步（充值/赠送后变动）
INSERT INTO dcs_tenant_wallet (wallet_id, tenant_id, wallet_type, parent_wallet_id, balance_amount, frozen_amount, currency, status, low_balance_threshold, negative_allowed, remark, version, create_dept, create_by, create_time, del_flag)
VALUES (900000000000004001, '000000', 'MAIN', NULL, 0, 0, 'CNY', 'ACTIVE', 1, 0, '基线初始化', 0, 103, 1, now(), '0');
