import { describe, expect, it } from 'vitest';
import { ACTION_LABELS, formatRemaining } from './riskActionPresentation';

describe('riskActionPresentation', () => {
  it('uses distinct labels for the reversible L3 and L4 actions', () => {
    expect(ACTION_LABELS.ENHANCED_VERIFICATION).toBe('增强验证');
    expect(ACTION_LABELS.TEMP_FREEZE).toBe('临时冻结');
  });

  it('shows a non-negative remaining duration and an expiry refresh hint', () => {
    expect(formatRemaining('2026-07-27T00:01:05+08:00', Date.parse('2026-07-27T00:00:00+08:00'))).toBe('1分5秒');
    expect(formatRemaining('2026-07-26T23:59:59+08:00', Date.parse('2026-07-27T00:00:00+08:00'))).toBe('等待状态刷新');
  });
});
