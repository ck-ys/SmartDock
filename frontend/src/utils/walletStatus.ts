/**
 * 钱包/额度状态的唯一话术表（2026-11-25 钱包批 Q12 = 案 1）。
 *
 * 病根：同一件事在三个页面各写一套话——总览页「正常」、流水页「ACTIVE」、套餐页「已启用」，
 * 颜色也不一致（正常有时绿有时灰）。新增一个状态要改三处，改漏一处就出现「同一个钱包两种说法」。
 *
 * 口径：
 * - `label` 是给用户看的一句话，不出现英文码；
 * - `hint` 是「这意味着什么 / 我该怎么办」，一句话说完；
 * - `tone` 只取四组语义色（pos 成功 / warn 警告 / neg 危险 / muted 中性），不许自造蓝阶。
 *
 * 以后新增状态只改这里；页面一律 `walletStatusText(code)` 取。
 */

export type WalletStatusTone = 'pos' | 'warn' | 'neg' | 'muted';

export interface WalletStatusText {
  label: string;
  hint: string;
  tone: WalletStatusTone;
}

/** 钱包状态（tenant_wallet.status） */
const WALLET: Record<string, WalletStatusText> = {
  ACTIVE: { label: '正常', hint: '可用余额充足，可以正常消费', tone: 'pos' },
  FROZEN: { label: '已冻结', hint: '管理员暂停了消费，解冻后自动恢复', tone: 'warn' },
  DISABLED: { label: '已停用', hint: '该单位已停用，请联系平台管理员', tone: 'muted' }
};

/** 低余额预警（不是 status 字段，由余额与阈值现算） */
export const LOW_BALANCE: WalletStatusText = {
  label: '低于阈值',
  hint: '余额低于提醒线，建议尽快充值',
  tone: 'warn'
};

/** 未知状态兜底：把原始码放进 label 但不吞掉，方便排障 */
export const unknownWalletStatus = (code?: string | null): WalletStatusText => ({
  label: code || '未知',
  hint: '状态未识别，请联系平台管理员',
  tone: 'muted'
});

/** 取钱包状态话术；未知码不静默成「正常」 */
export const walletStatusText = (code?: string | null): WalletStatusText =>
  (code && WALLET[code]) || unknownWalletStatus(code);

/** 状态 → Element Plus 药丸类名（四组语义色，与全站一致） */
export const walletStatusPill = (code?: string | null): string => `ui-pill--${walletStatusText(code).tone}`;

/** 充值订单状态（wallet_payment_order.status） */
const ORDER: Record<string, WalletStatusText> = {
  PENDING: { label: '待支付', hint: '订单已创建，等待支付完成', tone: 'warn' },
  PAID: { label: '已支付', hint: '款项已到账并计入钱包余额', tone: 'pos' },
  CLOSED: { label: '已关闭', hint: '订单已取消，未产生扣款', tone: 'muted' },
  FAILED: { label: '支付失败', hint: '支付未成功，可重新发起', tone: 'neg' }
};

export const orderStatusText = (code?: string | null): WalletStatusText =>
  (code && ORDER[code]) || { label: code || '未知', hint: '订单状态未识别，请联系平台管理员', tone: 'muted' };

export const orderStatusPill = (code?: string | null): string => `ui-pill--${orderStatusText(code).tone}`;
