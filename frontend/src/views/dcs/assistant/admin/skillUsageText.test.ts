import { describe, expect, it } from 'vitest';
import { skillLastUsedText } from './skillUsageText';

/** 固定“现在”= 2026-11-01 12:00（本机时区），测试不随系统时钟漂移 */
const NOW = new Date(2026, 10, 1, 12, 0, 0);

describe('skillLastUsedText 题2 技能统计时间展示', () => {
  it('没有记录说从未', () => {
    expect(skillLastUsedText(null, NOW)).toBe('从未');
    expect(skillLastUsedText(undefined, NOW)).toBe('从未');
    expect(skillLastUsedText('', NOW)).toBe('从未');
  });

  it('当年墙上时间只显示月日时分（后端 yyyy-MM-dd HH:mm:ss 字面量）', () => {
    expect(skillLastUsedText('2026-11-01 08:53:39', NOW)).toBe('11-01 08:53');
  });

  it('跨年补年份，避免误读成今年', () => {
    expect(skillLastUsedText('2025-03-04 05:06:07', NOW)).toBe('2025-03-04 05:06');
  });

  it('毫秒与秒级时间戳都能识别并换算本机时区', () => {
    const d = new Date(2026, 10, 1, 8, 53, 0);
    expect(skillLastUsedText(String(d.getTime()), NOW)).toBe('11-01 08:53');
    expect(skillLastUsedText(String(Math.floor(d.getTime() / 1000)), NOW)).toBe('11-01 08:53');
  });

  it('带时区的 ISO 串按本机时区换算，不把 UTC 数字直接当墙上时间', () => {
    const iso = '2026-11-01T00:53:00.000Z';
    const local = new Date(iso);
    const p = (n: number) => String(n).padStart(2, '0');
    expect(skillLastUsedText(iso, NOW)).toBe(
      `${p(local.getMonth() + 1)}-${p(local.getDate())} ${p(local.getHours())}:${p(local.getMinutes())}`
    );
    // 本机构造的 ISO 再转回来，仍是同一条墙上时间
    const wall = new Date(2026, 10, 1, 8, 53, 0).toISOString();
    expect(skillLastUsedText(wall, NOW)).toBe('11-01 08:53');
  });

  it('格式对但日期/时间越界按从未处理', () => {
    expect(skillLastUsedText('2026-13-45 99:99', NOW)).toBe('从未');
    expect(skillLastUsedText('2026-02-30 10:00:00', NOW)).toBe('从未');
    expect(skillLastUsedText('2026-00-10 10:00:00', NOW)).toBe('从未');
    // 闰日必须合法
    expect(skillLastUsedText('2024-02-29 10:00:00', NOW)).toBe('2024-02-29 10:00');
  });

  it('11/12 位数字不猜秒或毫秒，按无效处理', () => {
    expect(skillLastUsedText('17619296190', NOW)).toBe('从未');
    expect(skillLastUsedText('176192961900', NOW)).toBe('从未');
  });

  it('解析不了的值按从未处理', () => {
    expect(skillLastUsedText('not-a-time', NOW)).toBe('从未');
    expect(skillLastUsedText('20261101', NOW)).toBe('从未');
  });
});
