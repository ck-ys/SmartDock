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

describe('webOnState 联网开关单一真源（题10 案1：默认自动，可停用）', () => {
  it('默认自动：无键=自动，空 threadId=自动，旧值 1 仍算自动', () => {
    const s = memStorage();
    expect(readWebOn('u1', 't1', s)).toBe(true);
    expect(readWebOn('u1', '', s)).toBe(true);
    s.map.set(webOnKey('u1', 't1'), '1');
    expect(readWebOn('u1', 't1', s)).toBe(true);
  });

  it('只有显式 0 算停用', () => {
    const s = memStorage();
    s.map.set(webOnKey('u1', 't1'), '0');
    expect(readWebOn('u1', 't1', s)).toBe(false);
  });

  it('开关写入：开=删键不留残值；关=写 0 显式停用', () => {
    const s = memStorage();
    writeWebOn('u1', 't1', false, s);
    expect(s.map.get('dcs:assistant:webOn:u1:t1')).toBe('0');
    expect(readWebOn('u1', 't1', s)).toBe(false);
    writeWebOn('u1', 't1', true, s);
    expect(s.map.has('dcs:assistant:webOn:u1:t1')).toBe(false);
    expect(readWebOn('u1', 't1', s)).toBe(true);
  });

  it('会话/用户分键互不干扰（userId+threadId 双维度）', () => {
    const s = memStorage();
    writeWebOn('u1', 't1', false, s);
    expect(readWebOn('u1', 't2', s)).toBe(true);
    expect(readWebOn('u2', 't1', s)).toBe(true);
    writeWebOn('u2', 't1', false, s);
    writeWebOn('u1', 't1', true, s);
    expect(readWebOn('u2', 't1', s)).toBe(false);
    expect(readWebOn('u1', 't1', s)).toBe(true);
  });

  it('storage 抛异常=保守关（读不到停用记录就别出网）/静默忽略不炸', () => {
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
    expect(() => writeWebOn('u1', 't1', false, boom)).not.toThrow();
  });
});
