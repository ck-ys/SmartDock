import { describe, expect, it } from 'vitest';
import { PRESET_TABS, groupSessions, rowTime, sessionTitle } from './agentView';
import type { AssistantConversation } from '@/api/dcs/assistant';

function conv(id: number, last: string, title: string | null = `会话${id}`): AssistantConversation {
  return { id, threadId: `t-${id}`, title, lastMessageTime: last, status: 'ACTIVE' };
}

/** 固定「现在」= 2026-11-03 12:00 北京墙钟（epoch 与浏览器时区无关） */
const NOW = Date.parse('2026-11-03T12:00:00+08:00');

describe('智能体工作台 · 起手式场景', () => {
  it('三组场景齐全，每组至少 4 张卡', () => {
    expect(PRESET_TABS.map((t) => t.key)).toEqual(['office', 'dev', 'design']);
    for (const t of PRESET_TABS) {
      expect(t.label.length).toBeGreaterThan(0);
      expect(t.chips.length).toBeGreaterThanOrEqual(4);
    }
  });

  it('每张卡都有可读标签和非空提示词，标签不重复', () => {
    for (const t of PRESET_TABS) {
      const labels = t.chips.map((c) => c.label);
      expect(new Set(labels).size).toBe(labels.length);
      for (const c of t.chips) {
        expect(c.label.trim().length).toBeGreaterThan(0);
        expect(c.prompt.trim().length).toBeGreaterThan(c.label.length);
      }
    }
  });
});

describe('智能体工作台 · 最近会话分组（北京自然日）', () => {
  it('按今天 / 昨天 / 更早分三组，空组不返回', () => {
    const list = [conv(1, '2026-11-03 09:00:00'), conv(2, '2026-11-02 23:59:00'), conv(3, '2026-10-20 10:00:00')];
    const groups = groupSessions(list, NOW);
    expect(groups.map((g) => g.key)).toEqual(['今天', '昨天', '更早']);
    expect(groups[0].items.map((c) => c.id)).toEqual([1]);
    expect(groups[1].items.map((c) => c.id)).toEqual([2]);
    expect(groups[2].items.map((c) => c.id)).toEqual([3]);
  });

  it('日界精确：今天 00:00 算今天，昨天 00:00 算昨天，前天 23:59:59 算更早', () => {
    const list = [conv(1, '2026-11-03 00:00:00'), conv(2, '2026-11-02 00:00:00'), conv(3, '2026-11-01 23:59:59')];
    const groups = groupSessions(list, NOW);
    expect(groups.map((g) => g.key)).toEqual(['今天', '昨天', '更早']);
    expect(groups[0].items.map((c) => c.id)).toEqual([1]);
    expect(groups[1].items.map((c) => c.id)).toEqual([2]);
    expect(groups[2].items.map((c) => c.id)).toEqual([3]);
  });

  it('组内保持后端顺序，时间缺失/非法落「更早」不丢行', () => {
    const list = [conv(1, '2026-11-03 10:00:00'), conv(2, '2026-11-03 08:00:00'), conv(3, ''), conv(4, 'not-a-date')];
    const groups = groupSessions(list, NOW);
    expect(groups[0].items.map((c) => c.id)).toEqual([1, 2]);
    expect(groups[1].key).toBe('更早');
    expect(groups[1].items.map((c) => c.id)).toEqual([3, 4]);
  });

  it('北京时间凌晨的会话不会被浏览器时区带到「昨天」', () => {
    // 串是北京墙钟 2026-11-03 00:30；若用裸 new Date 在 GMT-4 会落到 11-02 → 错组。
    expect(groupSessions([conv(1, '2026-11-03 00:30:00')], NOW)[0].key).toBe('今天');
  });

  it('未来时间（时钟毛刺）按今天处理，与会话页口径一致', () => {
    expect(groupSessions([conv(1, '2026-11-03 18:00:00')], NOW)[0].key).toBe('今天');
    expect(rowTime('2026-11-03 18:00:00', NOW)).toBe('18:00');
  });

  it('空列表返回空分组', () => {
    expect(groupSessions([], NOW)).toEqual([]);
  });
});

describe('智能体工作台 · 会话行紧凑时间', () => {
  it('今天显示北京 HH:mm，昨天显示「昨天」，更早显示 MM-DD', () => {
    expect(rowTime('2026-11-03 09:05:00', NOW)).toBe('09:05');
    expect(rowTime('2026-11-02 23:59:00', NOW)).toBe('昨天');
    expect(rowTime('2026-10-20 10:00:00', NOW)).toBe('10-20');
  });

  it('非法/缺失时间返回空串', () => {
    expect(rowTime('', NOW)).toBe('');
    expect(rowTime(null, NOW)).toBe('');
    expect(rowTime('not-a-date', NOW)).toBe('');
  });
});

describe('智能体工作台 · 会话标题兜底', () => {
  it('null / 空白标题显示「新会话」', () => {
    expect(sessionTitle(conv(1, '2026-11-03 09:00:00', null))).toBe('新会话');
    expect(sessionTitle(conv(2, '2026-11-03 09:00:00', '   '))).toBe('新会话');
  });

  it('正常标题原样显示并去首尾空白', () => {
    expect(sessionTitle(conv(3, '2026-11-03 09:00:00', '数据问询 · 上周渠道'))).toBe('数据问询 · 上周渠道');
  });

  it('历史 JSON 残片标题按会话页同款规则清洗，不原样露出', () => {
    expect(sessionTitle(conv(4, '2026-11-03 09:00:00', '{"title":"按周复盘'))).toContain('未命名会话');
  });
});
