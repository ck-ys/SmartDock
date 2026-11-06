import { describe, expect, it, vi } from 'vitest';

const store = new Map<string, string>();
vi.stubGlobal('localStorage', {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => store.set(k, String(v)),
  removeItem: (k: string) => store.delete(k)
});

const { convPins, initConvPins, loadPins, toggleConvPin } = await import('./conversationPins');

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

  // E-10（2026-11-06）：全局键会把甲的置顶带给乙（列表里挂着别人会话 id）
  it('initConvPins 切到按用户键；旧全局键只删不迁（归属无法判定，不送给先登录者）', () => {
    store.set('dcs:assistant:pins', JSON.stringify(['legacy']));
    store.delete('dcs:assistant:pins:1');
    initConvPins(1);
    expect(loadPins()).toEqual([]);
    expect(store.has('dcs:assistant:pins')).toBe(false);
  });

  it('换账号看不到上一个人的置顶', () => {
    initConvPins(2);
    expect(convPins.value).toEqual([]);
  });
});
