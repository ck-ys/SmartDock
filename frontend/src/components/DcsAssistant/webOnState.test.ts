import { describe, expect, it } from 'vitest';
import { readWebOn, webOnKey, writeWebOn, type KVLike } from './webOnState';

/** node 环境无 localStorage——内存桩 */
function memStorage(): KVLike & { map: Map<string, string> } {
  const map = new Map<string, string>();
  return {
    map,
    getItem: (k) => (map.has(k) ? (map.get(k) as string) : null),
    setItem: (k, v) => void map.set(k, v),
    removeItem: (k) => void map.delete(k)
  };
}

describe('webOnState 联网开关单一真源（业务批3-④）', () => {
  it('默认关：无键=关，读非 1 值=关，空 threadId=关', () => {
    const s = memStorage();
    expect(readWebOn('u1', 't1', s)).toBe(false);
    s.map.set(webOnKey('u1', 't1'), '0');
    expect(readWebOn('u1', 't1', s)).toBe(false);
    s.map.set(webOnKey('u1', 't1'), 'yes');
    expect(readWebOn('u1', 't1', s)).toBe(false);
    expect(readWebOn('u1', '', s)).toBe(false);
  });

  it('开关写入：开=键值 1；关=删键不留脏 0', () => {
    const s = memStorage();
    writeWebOn('u1', 't1', true, s);
    expect(s.map.get('dcs:assistant:webOn:u1:t1')).toBe('1');
    expect(readWebOn('u1', 't1', s)).toBe(true);
    writeWebOn('u1', 't1', false, s);
    expect(s.map.has('dcs:assistant:webOn:u1:t1')).toBe(false);
  });

  it('会话/用户分键互不干扰（userId+threadId 双维度）', () => {
    const s = memStorage();
    writeWebOn('u1', 't1', true, s);
    expect(readWebOn('u1', 't2', s)).toBe(false);
    expect(readWebOn('u2', 't1', s)).toBe(false);
    writeWebOn('u2', 't1', true, s);
    writeWebOn('u1', 't1', false, s);
    expect(readWebOn('u2', 't1', s)).toBe(true);
    expect(readWebOn('u1', 't1', s)).toBe(false);
  });

  it('storage 抛异常=保守关/静默忽略不炸', () => {
    const boom: KVLike = {
      getItem: () => {
        throw new Error('quota exceeded');
      },
      setItem: () => {
        throw new Error('quota exceeded');
      },
      removeItem: () => {
        throw new Error('quota exceeded');
      }
    };
    expect(readWebOn('u1', 't1', boom)).toBe(false);
    expect(() => writeWebOn('u1', 't1', true, boom)).not.toThrow();
  });
});
