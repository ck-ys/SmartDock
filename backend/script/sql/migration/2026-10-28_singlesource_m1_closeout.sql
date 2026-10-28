-- ============================================================
-- 单源重构 M1 批·批末收口迁移 (2026-10-28)
-- 可重复执行: 全部 IF NOT EXISTS / WHERE NOT EXISTS / 带条件 DELETE
-- ============================================================

BEGIN;

-- ------------------------------------------------------------
-- ① 身份链接补两个查询索引(批末回修)
--   a) 跨类型身份桥 selectLinkShared 按 (tenant,source,identifier_type,identifier_value)
--      取行——唯一键改含 subject_type 后(B 片 identity_scope), 旧四列前缀不再命中;
--   b) countStrongLinks 按 (tenant,source,subject_id) 计强标识(不分类型),
--      现有索引第三列都是 subject_type, 前缀同样不命中。
-- ------------------------------------------------------------

CREATE INDEX IF NOT EXISTS idx_profile_identity_link_identifier
    ON public.profile_identity_link (tenant_id, source, identifier_type, identifier_value);

CREATE INDEX IF NOT EXISTS idx_profile_identity_link_strong
    ON public.profile_identity_link (tenant_id, source, subject_id)
    WHERE status = 'ACTIVE';

-- ------------------------------------------------------------
-- ② 登录名→user_id 回查的定位保障: (tenant_id, user_name) 软删过滤唯一索引
--   服务层查重只挡单请求; 并发注册同名用户竞态由库层兜底。
--   前置检重: 上线前已核 smartdock_shots / smartdock_scratch 两库均 0 重复;
--   若目标库存在重复, 建唯一索引会直接报错中止——先人工合并同名账号再重跑。
-- ------------------------------------------------------------

CREATE UNIQUE INDEX IF NOT EXISTS uk_sys_user_tenant_name
    ON public.sys_user (tenant_id, user_name)
    WHERE del_flag = '0';

-- ------------------------------------------------------------
-- ③ 孤儿按钮位清理: 12019「来源改名停用」(profile:source:edit)
--   其唯一消费方(引擎管理「来源管理」页签)已随 D 片单源化删除,
--   后端写端点亦已批末退役——菜单行与角色绑定一并移除, 防止重跑/重排 SQL
--   时 04_role_menu 的「跟随 11989 授权」块把权限再悄悄发出去。
-- ------------------------------------------------------------

DELETE FROM public.sys_role_menu WHERE menu_id = 12019;
DELETE FROM public.sys_menu WHERE menu_id = 12019 AND perms = 'profile:source:edit';

COMMIT;
