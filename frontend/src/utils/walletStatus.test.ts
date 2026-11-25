import { describe, expect, it } from 'vitest';
import { LOW_BALANCE, orderStatusPill, orderStatusText, walletStatusPill, walletStatusText } from './walletStatus';

/**
 * 状态话术表（2026-11-25 钱包批 Q12）。
 *
 * 钉三件事：① 每个已知状态都有 label 与 hint（不许空话术）；② 未知码不许静默成「正常」——
 * 那会把「读不出来」伪装成「一切正常」，是这类映射表最典型的静默错误；③ 药丸颜色只取四组语义色。
 */
describe('walletStatusText', () => {
  it('三个已知钱包状态都有 label 与 hint', () => {
    for (const code of ['ACTIVE', 'FROZEN', 'DISABLED']) {
      const t = walletStatusText(code);
      expect(t.label).toBeTruthy();
      expect(t.hint).toBeTruthy();
    }
    expect(walletStatusText('ACTIVE').label).toBe('正常');
    expect(walletStatusText('FROZEN').label).toBe('已冻结');
    expect(walletStatusText('DISABLED').label).toBe('已停用');
  });

  it('未知码不许静默成「正常」，要把原始码露出来', () => {
    const t = walletStatusText('SOMETHING_NEW');
    expect(t.label).toBe('SOMETHING_NEW');
    expect(t.label).not.toBe('正常');
    expect(t.tone).toBe('muted');
    // 空值同理，不能显示 null/undefined
    expect(walletStatusText(null).label).toBe('未知');
    expect(walletStatusText(undefined).label).toBe('未知');
  });

  it('药丸颜色只取四组语义色', () => {
    const allowed = new Set(['pos', 'warn', 'neg', 'muted']);
    for (const code of ['ACTIVE', 'FROZEN', 'DISABLED', 'NOPE']) {
      expect(allowed.has(walletStatusText(code).tone)).toBe(true);
    }
    expect(walletStatusPill('ACTIVE')).toBe('ui-pill--pos');
    expect(walletStatusPill('FROZEN')).toBe('ui-pill--warn');
  });

  it('低余额是预警话术，不占用 status 枚举', () => {
    expect(LOW_BALANCE.label).toBe('低于阈值');
    expect(LOW_BALANCE.tone).toBe('warn');
    expect(walletStatusText('LOW')).not.toEqual(LOW_BALANCE);
  });
});

describe('orderStatusText', () => {
  it('四个订单状态都有话术', () => {
    for (const code of ['PENDING', 'PAID', 'CLOSED', 'FAILED']) {
      expect(orderStatusText(code).label).toBeTruthy();
      expect(orderStatusText(code).hint).toBeTruthy();
    }
    expect(orderStatusText('PAID').label).toBe('已支付');
    expect(orderStatusPill('PENDING')).toBe('ui-pill--warn');
  });

  it('未知订单状态同样不静默', () => {
    expect(orderStatusText('WEIRD').label).toBe('WEIRD');
    expect(orderStatusText(null).label).toBe('未知');
  });
});
