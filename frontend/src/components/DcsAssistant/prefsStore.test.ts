import { describe, expect, it, vi } from 'vitest';
import { nextTick } from 'vue';

const store = new Map<string, string>();
vi.stubGlobal('localStorage', {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => store.set(k, String(v)),
  removeItem: (k: string) => store.delete(k)
});

const { getAgentPrefs, lastUseKey, motionReduced } = await import('./prefsStore');

const raw = (uid: string) => JSON.parse(store.get(`smartdock-agent-prefs:v1:${uid}`) ?? '{}');

describe('prefsStore（设置页「常规」七行）', () => {
  it('默认值：Enter 发送、边生成边显示、提问 5 分钟自动继续', () => {
    const p = getAgentPrefs('1');
    expect(p.sendKey).toBe('enter');
    expect(p.streamOutput).toBe(true);
    expect(p.askAutoContinue).toBe(true);
    expect(p.toolsOpen).toBe(false);
    expect(p.motion).toBe('system');
    expect(p.answerSize).toBe('standard');
  });

  it('改一项立即落盘，且不污染别人', async () => {
    getAgentPrefs('1').answerSize = 'large';
    await nextTick();
    expect(raw('1').answerSize).toBe('large');

    expect(getAgentPrefs('2').answerSize).toBe('standard');
    expect(store.has('smartdock-agent-prefs:v1:2')).toBe(false);
  });

  it('同一用户拿到同一份 reactive（改一处，别处跟着变）', () => {
    const a = getAgentPrefs('3');
    const b = getAgentPrefs('3');
    a.sendKey = 'ctrl-enter';
    expect(b.sendKey).toBe('ctrl-enter');
  });

  it('旧存档缺字段用默认值补齐（不出现 undefined 档位）', async () => {
    store.set('smartdock-agent-prefs:v1:4', JSON.stringify({ toolsOpen: true }));
    const p = getAgentPrefs('4');
    expect(p.toolsOpen).toBe(true);
    expect(p.streamOutput).toBe(true);
  });

  it('动效档位：减弱恒真，标准恒假，跟随系统看系统偏好', () => {
    const p = getAgentPrefs('5');
    p.motion = 'reduced';
    expect(motionReduced(p)).toBe(true);
    p.motion = 'full';
    expect(motionReduced(p)).toBe(false);
  });

  it('上次使用时间按用户分键', () => {
    expect(lastUseKey('1')).toBe('dcs:assistant:lastUse:1');
    expect(lastUseKey('1')).not.toBe(lastUseKey('2'));
  });
});
