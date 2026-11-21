import { describe, expect, it, vi } from 'vitest';

const store = new Map<string, string>();
vi.stubGlobal('localStorage', {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => store.set(k, String(v)),
  removeItem: (k: string) => store.delete(k)
});

const { migrateDraft, readDraft, removeDraft, writeDraft } = await import('./draftStore');

const key = (uid: string, scope: string) => `smartdock-agent-drafts:v1:${uid}:${scope}`;

describe('draftStore（批A/B：按用户、按会话存草稿）', () => {
  it('写读一致；纯空白不落盘，删空即删键', () => {
    writeDraft('1', 'draft', '明天见');
    expect(readDraft('1', 'draft')).toBe('明天见');

    writeDraft('1', 'draft', '   ');
    expect(store.has(key('1', 'draft'))).toBe(false);
    expect(readDraft('1', 'draft')).toBe('');
  });

  it('换账号读不到对方草稿', () => {
    writeDraft('1', 'draft', '甲的草稿');
    expect(readDraft('2', 'draft')).toBe('');
  });

  it('草稿态拿到真实会话后迁过去：目标已有内容以目标为准，旧键必删', () => {
    writeDraft('1', 'draft', '甲的草稿');
    migrateDraft('1', 'draft', 't-100');
    expect(readDraft('1', 't-100')).toBe('甲的草稿');
    expect(store.has(key('1', 'draft'))).toBe(false);

    writeDraft('1', 'draft', '新草稿');
    writeDraft('1', 't-200', '目标已有');
    migrateDraft('1', 'draft', 't-200');
    expect(readDraft('1', 't-200')).toBe('目标已有');
    expect(store.has(key('1', 'draft'))).toBe(false);
  });

  it('removeDraft 只删指定会话，不碰同用户其它草稿', () => {
    writeDraft('1', 't-100', 'x');
    writeDraft('1', 't-300', '留着');
    removeDraft('1', 't-100');
    expect(readDraft('1', 't-100')).toBe('');
    expect(readDraft('1', 't-300')).toBe('留着');
  });

  it('存储不可用（隐私模式/配额满）不抛，退化为空串', () => {
    const original = globalThis.localStorage;
    const boom = () => {
      throw new Error('denied');
    };
    vi.stubGlobal('localStorage', { getItem: boom, setItem: boom, removeItem: boom });
    expect(readDraft('1', 'draft')).toBe('');
    expect(() => writeDraft('1', 'draft', 'x')).not.toThrow();
    expect(() => removeDraft('1', 'draft')).not.toThrow();
    expect(() => migrateDraft('1', 'draft', 't-9')).not.toThrow();
    vi.stubGlobal('localStorage', original);
  });
});
