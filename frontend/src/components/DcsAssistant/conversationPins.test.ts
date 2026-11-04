import { describe, expect, it, vi } from 'vitest';

const store = new Map<string, string>();
vi.stubGlobal('localStorage', {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => store.set(k, String(v)),
  removeItem: (k: string) => store.delete(k)
});

const { convPins, loadPins, toggleConvPin } = await import('./conversationPins');

describe('conversationPins', () => {
  it('新置顶排最前，再点取消', () => {
    convPins.value = [];
    toggleConvPin('a');
    toggleConvPin('b');
    expect(convPins.value).toEqual(['b', 'a']);
    toggleConvPin('b');
    expect(convPins.value).toEqual(['a']);
  });

  it('写盘与内存一致', () => {
    convPins.value = [];
    toggleConvPin('9');
    expect(JSON.parse(store.get('dcs:assistant:pins') ?? '[]')).toEqual(['9']);
  });

  it('脏值不炸列：对象/数字混入只留字符串', () => {
    store.set('dcs:assistant:pins', '{"a":1}');
    expect(loadPins()).toEqual([]);
    store.set('dcs:assistant:pins', '["x",1,null,{"y":2}]');
    expect(loadPins()).toEqual(['x']);
  });
});
