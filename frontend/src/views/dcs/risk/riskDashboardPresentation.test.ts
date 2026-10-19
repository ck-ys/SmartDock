import { describe, expect, it } from 'vitest';
import { formatRiskRelativeTime, parseDcsServerTime, sumEventCount, sumRiskScore } from './riskDashboardPresentation';

describe('risk dashboard presentation', () => {
  it('sums PostgreSQL map values even when numeric fields are strings', () => {
    const stats = [
      { eventType: 'LOGIN_FAIL', cnt: 2, totalScore: 6 },
      { eventType: 'UPLOAD_ABUSE', cnt: '3', totalScore: '18' }
    ];

    expect(sumEventCount(stats)).toBe(5);
    expect(sumRiskScore(stats)).toBe(24);
  });

  it('parses timezone-less DCS timestamps as the configured Asia/Shanghai business time', () => {
    const parsed = parseDcsServerTime('2026-07-26 20:19:40');

    expect(parsed?.toISOString()).toBe('2026-07-26T12:19:40.000Z');
    expect(formatRiskRelativeTime('2026-07-26 20:19:40', Date.parse('2026-07-26T12:20:00Z'))).toBe('20秒前');
  });

  it('never renders a negative relative duration for small clock skew', () => {
    expect(formatRiskRelativeTime('2026-07-26 20:20:05', Date.parse('2026-07-26T12:20:00Z'))).toBe('刚刚');
  });
});
