import { describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';

/* 批C Q8 同步段回归测试（2026-11-15 三审 P1 补）：
   服务端拉回来的值不能再原样推回去——watch 默认 flush='pre'，闸必须撑到 watcher 跑完。 */

const h = vi.hoisted(() => ({
  save: [] as unknown[],
  remote: {
    saved: true,
    sendKey: 'ctrl-enter',
    streamOutput: false,
    toolsOpen: true,
    autoRetry: false,
    motion: 'reduced',
    answerSize: 'large',
    askAutoContinue: false
  }
}));

vi.mock('@/api/dcs/assistant', () => ({
  getAssistantUserPref: () => Promise.resolve({ data: { ...h.remote } }),
  saveAssistantUserPref: (d: unknown) => {
    h.save.push(d);
    return Promise.resolve();
  }
}));

const store = new Map<string, string>();
vi.stubGlobal('localStorage', {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => store.set(k, String(v)),
  removeItem: (k: string) => store.delete(k)
});

const { getAgentPrefs } = await import('./prefsStore');

/** 让动态 import + watcher + 800ms 防抖都跑完 */
const settle = async () => {
  for (let i = 0; i < 3; i++) {
    await nextTick();
    await new Promise((r) => setTimeout(r, 0));
  }
  await new Promise((r) => setTimeout(r, 900));
  await nextTick();
};

describe('prefsStore 账号层同步', () => {
  it('拉到已存值：七项照服务端，但不反向推回（防自激回环）', async () => {
    const p = getAgentPrefs('q8-a');
    await settle();
    expect(p.answerSize).toBe('large');
    expect(p.sendKey).toBe('ctrl-enter');
    expect(h.save.length).toBe(0);
  });

  it('用户手动改一项：只推一次（闸不能把正常推送也吞掉）', async () => {
    const before = h.save.length;
    const p = getAgentPrefs('q8-b');
    await settle();
    // 拉下来的值是 large，改成 standard 才是真改动（同值赋值不触发 watch）
    p.answerSize = 'standard';
    await settle();
    expect(h.save.length - before).toBe(1);
    expect((h.save[h.save.length - 1] as { answerSize: string }).answerSize).toBe('standard');
  });
});
