-- 单源重构第一批·题0-丁: 身份链接按主体类型隔离(强标识), 匿名桥保持跨类型单行
-- 现状: 唯一键 (tenant_id, source, identifier_type, identifier_value) 不含 subject_type,
--       员工(user)与顾客(member)同号标识(如 user_id=123)会互相串号甚至互改挂靠(板上题0-丁实证)。
-- 修复: 唯一键与强标识查询/计数/登记全部加 subject_type(各命名空间独立);
--       anonymous_id 走 selectLinkShared 不分类型(身份桥), 改挂沿用既有行 subject_type 保单行。
-- 前置检重(应为 0 行, 开发库已核): SELECT tenant_id,source,identifier_type,identifier_value,
--   count(DISTINCT subject_type) FROM profile_identity_link GROUP BY 1,2,3,4 HAVING count(DISTINCT subject_type)>1;
BEGIN;
ALTER TABLE profile_identity_link DROP CONSTRAINT IF EXISTS uk_profile_identity_link;
ALTER TABLE profile_identity_link
    ADD CONSTRAINT uk_profile_identity_link
    UNIQUE (tenant_id, source, subject_type, identifier_type, identifier_value);
COMMIT;
