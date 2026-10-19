/**
 * 批6-#2 前端三用例（ISSUE-006，2026-10-14）：中断标记与远端轮询去重。
 * ①interrupted 徽标数据位：历史回放 endReason=partial/truncated 置位、正常轮恒 false；
 * ②多段合并 or 语义：首段中断后，后续正常段不得把标记洗掉（洗掉=断连答案冒充完整）；
 * ③remoteWatch 跨组件去重（m3 P0-2）：同 thread 先到者盯、后到者跳过、释放后可再盯。
 * 拆独立文件防 useAssistant.test.ts（1195 行）继续膨胀——纯逻辑无 DOM 断言。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { AguiHandlers } from '@/api/dcs/assistant/types';

vi.mock('@/api/dcs/assistant/aguiClient', () => ({
  createAguiClient: (_opts: unknown) => ({
    startTurn: (_input: unknown, _handlers: AguiHandlers) => ({
      stop: () => undefined,
      done: new Promise<'finished'>(() => undefined)
    })
  })
}));
vi.mock('@/api/dcs/assistant', () => ({
  getPendingHitl: async () => ({ data: [] as unknown[] }),
  getConversationTodo: async () => ({ code: 200, msg: 'ok', data: [] })
}));

import { useAssistant, tryAcquireRemoteWatch, releaseRemoteWatch } from './useAssistant';

function setup() {
  return { a: useAssistant({}) };
}

beforeEach(() => {
  vi.clearAllTimers();
});

describe('批6-#2 interrupted 徽标（历史回放映射）', () => {
  it('endReason=partial（刚断连仍在生成）→ interrupted 置位；正常轮恒 false', () => {
    const { a } = setup();
    a.rebuildFromHistory([
      { role: 'user', content: '帮我查未交' },
      { role: 'assistant', content: '查到一半断连的部分', endReason: 'partial' },
      { role: 'user', content: '再来一条正常的' },
      { role: 'assistant', content: '完整回答', endReason: 'stop' }
    ]);
    expect(a.state.turns[0].interrupted).toBe(true);
    expect(a.state.turns[1].interrupted).toBeFalsy();
  });

  it('endReason=truncated（终局中断/失联收割）→ interrupted 置位', () => {
    const { a } = setup();
    a.rebuildFromHistory([
      { role: 'user', content: 'q' },
      { role: 'assistant', content: '被收割的残余', endReason: 'truncated' }
    ]);
    expect(a.state.turns[0].interrupted).toBe(true);
  });

  it('多段合并 or 语义：首段中断后，后续正常段不洗掉标记', () => {
    const { a } = setup();
    a.rebuildFromHistory([
      { role: 'user', content: 'q' },
      { role: 'assistant', content: '断连首段', endReason: 'partial' },
      { role: 'assistant', content: '续写恢复段', endReason: 'stop' }
    ]);
    expect(a.state.turns[0].interrupted).toBe(true);
    expect(a.state.turns[0].bufText).toBe('断连首段\n\n续写恢复段');
  });
});

describe('批6-#2 remoteWatch 跨组件去重（m3 P0-2）', () => {
  it('同 thread 先到者盯、后到者跳过；释放后可再盯', () => {
    releaseRemoteWatch('th-dedup'); // 清场：模块级 Set 跨用例残留
    expect(tryAcquireRemoteWatch('th-dedup')).toBe(true);
    expect(tryAcquireRemoteWatch('th-dedup')).toBe(false);
    releaseRemoteWatch('th-dedup');
    expect(tryAcquireRemoteWatch('th-dedup')).toBe(true);
    releaseRemoteWatch('th-dedup');
  });

  it('不同 thread 互不排斥', () => {
    expect(tryAcquireRemoteWatch('th-a')).toBe(true);
    expect(tryAcquireRemoteWatch('th-b')).toBe(true);
    releaseRemoteWatch('th-a');
    releaseRemoteWatch('th-b');
  });
});
