import { describe, expect, it } from 'vitest';
import { cnDayDiff, cnDayKey, cnEpoch, relTime } from './time';

/** 批A Q12 回归锁：无偏移串=北京墙钟按 +08:00 解析（修非中国时区浏览器负差恒「刚刚」）。
 *  契约来源：2026-10-11 浏览器实测 API 原始串 `2026-10-11 21:55:02`（空格分隔无时区）。
 *  now 统一冻结在 2026-10-11T12:00:00Z（北京 20:00）。 */
const NOW = Date.parse('2026-10-11T12:00:00Z');

describe('cnEpoch/cnDayKey（审查回修 P1-1/P1-2 单一真源）', () => {
  it('cnEpoch：无偏移串按北京墙钟解析为 epoch；带 Z 原样；无效 null', () => {
    expect(cnEpoch('2026-10-11 21:55:02')).toBe(Date.parse('2026-10-11T21:55:02+08:00'));
    expect(cnEpoch('2026-10-11T13:55:02Z')).toBe(Date.parse('2026-10-11T13:55:02Z'));
    expect(cnEpoch('garbage')).toBeNull();
    expect(cnEpoch(undefined)).toBeNull();
    expect(cnEpoch(1234567890)).toBe(1234567890);
  });

  it('cnDayKey：北京自然日键（活跃绿点/今天昨天分组共用日界）', () => {
    expect(cnDayKey('2026-10-11 08:00:00', NOW)).toBe('2026-10-11'); // 北京 10-11 08:00
    expect(cnDayKey('2026-10-10 23:59:00', NOW)).toBe('2026-10-10'); // 北京昨天尾分钟
    expect(cnDayKey('2026-10-11 00:01:00', NOW)).toBe('2026-10-11'); // 北京今天首分钟
    expect(cnDayKey(null)).toBeNull();
  });

  it('cnDayDiff：now−item 天数（0=今天/1=昨天/负=未来钳今天）——批A 回修自抓 bug 的回归锁（方向写反会把昨天全挂今天组）', () => {
    expect(cnDayDiff('2026-10-11 12:00:00', NOW)).toBe(0); // 北京同一天
    expect(cnDayDiff('2026-10-10 22:00:00', NOW)).toBe(1); // 北京昨天
    expect(cnDayDiff('2026-10-09 22:00:00', NOW)).toBe(2); // 更早
    expect(cnDayDiff('2026-10-11 23:00:00', NOW)).toBe(0); // 北京同一天的未来(毛刺) → 今天
    expect(cnDayDiff('2026-10-12 01:00:00', NOW)).toBe(-1); // 跨到北京明天 → 分组按今天钳
    expect(cnDayDiff(null)).toBeNull();
  });
});

describe('relTime 会话时间阶梯', () => {
  it('空值/无效值返回空串', () => {
    expect(relTime(undefined, NOW)).toBe('');
    expect(relTime(null, NOW)).toBe('');
    expect(relTime('', NOW)).toBe('');
    expect(relTime('garbage', NOW)).toBe('');
  });

  it('60 秒内=刚刚；恰好 60 秒=1 分钟前（边界不含）；59 分=59 分钟前', () => {
    expect(relTime('2026-10-11T19:59:30', NOW)).toBe('刚刚');
    expect(relTime('2026-10-11 19:59:00', NOW)).toBe('1 分钟前'); // 空格分隔=API 真实形态
    expect(relTime('2026-10-11 19:01:00', NOW)).toBe('59 分钟前');
  });

  it('一小时以上今天=北京墙钟 HH:mm（显示值=串本身墙钟）', () => {
    expect(relTime('2026-10-11 12:30:00', NOW)).toBe('12:30');
  });

  it('昨天=昨天 HH:mm；更早=MM-DD（北京自然日分组，与相对阈值分离）', () => {
    expect(relTime('2026-10-10 22:00:00', NOW)).toBe('昨天 22:00');
    expect(relTime('2026-09-30 02:00:00', NOW)).toBe('09-30');
  });

  it('未来时间（时钟毛刺）钳 0 → 刚刚，不炸不恒定错档', () => {
    expect(relTime('2026-10-11 21:00:00', NOW)).toBe('刚刚'); // 北京 21:00 > now 北京 20:00
  });

  it('带 Z/偏移的串原样解析，不重复补偏移', () => {
    expect(relTime('2026-10-11T11:30:00.500Z', NOW)).toBe('29 分钟前'); // 500ms 使整分差 1
    expect(relTime('2026-10-11T19:00:00+08:00', NOW)).toBe('19:00'); // 11:00Z=1h 前，北京今天 19:00
  });

  it('业务 #10b 杀手回归：API 真实形态串在任意时区浏览器下 diff 一致（node 环境即跨时区行为基线）', () => {
    // 修前：GMT-4 浏览器把北京墙钟串当本地未来时间 → 恒「刚刚」；修后固定 30 分钟前
    expect(relTime('2026-10-11 19:30:00', NOW)).toBe('30 分钟前');
  });

  it('epoch 毫秒入参直接支持', () => {
    expect(relTime(NOW - 90_000, NOW)).toBe('1 分钟前');
  });
});
