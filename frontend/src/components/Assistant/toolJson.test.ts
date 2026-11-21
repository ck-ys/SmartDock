import { describe, expect, it } from 'vitest';
import { parseToolJson, summarizeArgs } from './toolJson';

describe('parseToolJson 批C-2', () => {
  it('双层 JSON 解包：字符串字面量包一层也能取出 rows', () => {
    const inner = JSON.stringify({ asOf: '2026', rows: [{ a: 1 }], total: 1 });
    const j = parseToolJson(JSON.stringify(inner));
    expect(j?.rows?.length).toBe(1);
  });
  it('雪花 ID 精度保真：19 位 missionId parse 前加引号不失真（P0 回归锁）', () => {
    const raw = `{"rows":[{"missionId":2096985499425107969,"missionName":"X"}],"total":1}`;
    const j = parseToolJson(raw);
    const row = (j?.rows?.[0] ?? {}) as { missionId?: unknown };
    expect(row.missionId).toBe('2096985499425107969');
  });
  it('双层形态下内层雪花 ID 同样保真（外层转义引号形态命中）', () => {
    const inner = `{"rows":[{"missionId":2096985499425107969}]}`;
    const j = parseToolJson(JSON.stringify(inner));
    const row = (j?.rows?.[0] ?? {}) as { missionId?: unknown };
    expect(row.missionId).toBe('2096985499425107969');
  });
  it('普通数字（计数/总数）不受影响', () => {
    const j = parseToolJson(`{"total":33,"rows":[{"totalCount":86,"pendingCount":12}]}`);
    expect(j?.total).toBe(33);
    expect((j?.rows?.[0] as { totalCount?: number })?.totalCount).toBe(86);
  });
  it('坏 JSON 返回 null', () => {
    expect(parseToolJson('not json')).toBeNull();
  });
  it('091：字符串字面量内「冒号+15位数字+逗号」不再整体返回 null（原串兜底）', () => {
    // shield 在 note 字符串里误插引号 → ①崩；原串本就合法 JSON（数字躺在字符串里）→ ③直取
    const raw = `{"rows":[{"note":"单号: 123456789012345678, 已处理"}],"total":1}`;
    const j = parseToolJson(raw);
    expect(j).not.toBeNull();
    expect(j?.total).toBe(1);
    expect((j?.rows?.[0] as { note?: string })?.note).toContain('单号: 123456789012345678');
  });
  it('091：裸大数字仍走 ①加引号保真，不受兜底路影响（回归锁）', () => {
    const raw = `{"rows":[{"missionId":2096985499425107969}],"total":1}`;
    const row = (parseToolJson(raw)?.rows?.[0] ?? {}) as { missionId?: unknown };
    expect(row.missionId).toBe('2096985499425107969');
  });
});

describe('summarizeArgs 批I 零技术词', () => {
  it('已知键映射中文：mission_id→任务', () => {
    expect(summarizeArgs('{"mission_id":2092652290059091970,"page":1}')).toBe('任务 2092652290059091970，页码 1');
  });
  it('未知键保留原名，值超 18 字截断', () => {
    const out = summarizeArgs('{"custom_key":"012345678901234567890123456789"}');
    expect(out).toBe('custom_key 012345678901234567890123…');
  });
  it('只取前 3 个键', () => {
    const out = summarizeArgs('{"a":1,"b":2,"c":3,"d":4}');
    expect(out).toBe('a 1，b 2，c 3');
  });
  it('非 JSON 原样返回（截断归调用方）', () => {
    expect(summarizeArgs('plain text')).toBe('plain text');
  });
});
