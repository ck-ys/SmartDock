import { describe, expect, it } from 'vitest';
import { dayKeyOf, groupByDay, pageTotals } from './ledgerGroup';

/**
 * 流水按日期分组（2026-11-25 钱包批 Q3）。
 *
 * 三个坑各钉一条：① 日期必须从字符串前 10 位取，不能 new Date() 转本地时区（跨时区会把凌晨行分到前一天）；
 * ② 没有日期的行不能丢（丢了「合计对不上」）；③ 组内顺序保持入参顺序（翻页一致性）。
 */
describe('dayKeyOf', () => {
  it('两种后端时间格式都取到同一个日期', () => {
    expect(dayKeyOf('2026-11-25 21:00:00')).toBe('2026-11-25');
    expect(dayKeyOf('2026-11-25T21:00:00.000+08:00')).toBe('2026-11-25');
  });

  it('凌晨 00:30 不会被推到前一天', () => {
    // 关键回归：若用 new Date(...).toLocaleDateString() 在 UTC 机器上会变成 11-24
    expect(dayKeyOf('2026-11-25 00:30:00')).toBe('2026-11-25');
    expect(dayKeyOf('2026-11-25T00:30:00Z')).toBe('2026-11-25');
  });

  it('取不到日期返回空串而不是乱猜', () => {
    expect(dayKeyOf(null)).toBe('');
    expect(dayKeyOf(undefined)).toBe('');
    expect(dayKeyOf('')).toBe('');
    expect(dayKeyOf('not-a-date')).toBe('');
  });
});

describe('groupByDay', () => {
  const rows = [
    { createTime: '2026-11-25 10:24:00', direction: 'OUT', amount: 8.4 },
    { createTime: '2026-11-25 09:11:00', direction: 'OUT', amount: 4.4 },
    { createTime: '2026-11-24 18:02:00', direction: 'IN', amount: 100 },
    { createTime: '2026-11-24 12:00:00', direction: 'OUT', amount: 30 }
  ];

  it('按天分组并算每天收支小计', () => {
    const days = groupByDay(rows);
    expect(days.map((d) => d.day)).toEqual(['2026-11-25', '2026-11-24']);
    expect(days[0].totalOut).toBeCloseTo(12.8, 4);
    expect(days[0].totalIn).toBe(0);
    expect(days[1].totalIn).toBe(100);
    expect(days[1].totalOut).toBe(30);
  });

  it('组内顺序保持入参顺序，不重排', () => {
    const days = groupByDay(rows);
    expect(days[0].rows.map((r) => r.amount)).toEqual([8.4, 4.4]);
  });

  it('没有日期的行归到最后一组，不丢弃', () => {
    const withUnknown = [...rows, { createTime: null, direction: 'OUT', amount: 1 }];
    const days = groupByDay(withUnknown);
    const last = days[days.length - 1];
    expect(last.day).toBe('');
    expect(last.label).toBe('日期未知');
    // 原 4 行 + 新增 1 行 = 5 行，一行都不许丢
    expect(days.reduce((s, d) => s + d.rows.length, 0)).toBe(5);
    expect(last.rows.length).toBe(1);
  });

  it('空列表返回空数组，不抛错', () => {
    expect(groupByDay([])).toEqual([]);
  });

  it('标签是 MM-DD + 星期', () => {
    const days = groupByDay(rows);
    expect(days[0].label).toBe('11-25');
    // 2026-11-25 是周三
    expect(days[0].weekday).toBe('周三');
    expect(days[1].weekday).toBe('周二');
  });
});

describe('pageTotals', () => {
  it('只累加当前页，与全量合计是两回事', () => {
    const t = pageTotals([
      { direction: 'IN', amount: 100 },
      { direction: 'OUT', amount: 30 },
      { direction: 'OUT', amount: '12.8' }
    ]);
    expect(t.totalIn).toBe(100);
    expect(t.totalOut).toBeCloseTo(42.8, 4);
    expect(t.count).toBe(3);
  });

  it('坏数值当 0，不产生 NaN', () => {
    const t = pageTotals([{ direction: 'OUT', amount: 'abc' }, { direction: 'OUT', amount: null }]);
    expect(t.totalOut).toBe(0);
    expect(Number.isNaN(t.totalOut)).toBe(false);
  });
});
