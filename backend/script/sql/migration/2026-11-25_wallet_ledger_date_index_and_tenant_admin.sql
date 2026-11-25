-- ============================================================================
-- 2026-11-25 钱包/额度体验批（板 20261125-wallet-ux-board.html）
--   ① 钱包流水按日期筛选的索引：列表与合计都按 (tenant_id, create_time) 过滤 + 倒序
--   ② 租户管理员（role 6）授权：本单位只读 + 充值/购买套餐（Q9 = 案 1）
--
-- 可重复执行：索引 IF NOT EXISTS；授权 ON CONFLICT DO NOTHING。
-- baseline 同步：01_schema.sql 索引段 + 04_role_menu.sql role 6 段。
-- ============================================================================

-- ① 钱包流水按日期筛选索引（Q5：日期预设 + 自定义区间 + 合计都要走这条）
--    create_time DESC 进索引：列表默认按时间倒序，B-tree 反向扫描同样走索引。
--    带 del_flag 部分索引：逻辑删除行不进索引，与查询条件（del_flag = '0'）一致。
CREATE INDEX IF NOT EXISTS idx_tw_ledger_tenant_time
    ON public.tenant_wallet_ledger USING btree (tenant_id, create_time DESC)
    WHERE (del_flag = '0'::bpchar);

-- ② 租户管理员（role 6）钱包/积分权限
--    只授「看自己单位 + 自己买」四项，不授扣减/冻结/改价/赠送（那是平台操作）。
--    Q9 案 1 的安全前提：后端已有租户隔离——TenantWalletLedgerService 非超管强制收窄本租户，
--    TenantWalletService.walletSummary/queryPageList 同口径，AiCreditController 一律取登录租户。
--    菜单 6（平台运营目录）本身也要授，否则子菜单挂不上导航。
INSERT INTO public.sys_role_menu (role_id, menu_id) VALUES
    (6, 6)     -- 平台运营（目录）
ON CONFLICT DO NOTHING;

INSERT INTO public.sys_role_menu (role_id, menu_id) VALUES
    (6, 9067),   -- 账户与账单      smartdock:wallet:list
    (6, 9068),   -- 钱包流水        smartdock:wallet-ledger:list
    (6, 11940),  -- 积分套餐        smartdock:aicredit:list
    (6, 11941)   -- 积分订阅与加油包 smartdock:aicredit:subscribe
ON CONFLICT DO NOTHING;

-- 说明（有意不授）：
--   9097 smartdock:wallet:recharge   —— 充值是「往单位钱包里加钱」，等于平台自己的钱；
--                                       客户管理员要加钱走充值订单/线下，不给直接改余额。
--   9098 smartdock:wallet:deduct     —— 扣减是平台调账
--   9099 smartdock:wallet:status     —— 冻结/停用是平台风控
--   11942 smartdock:aicredit:plan-edit —— 改价/赠送是平台定价权
