/**
 * AGUI SSE 客户端单测（批E Task E1）。
 * 判据：SSE 帧解析（分帧/多行 data/非 JSON 容错）；完整事件序分发；401 双形态；
 * RUN_ERROR；停止幂等；断线指数退避重试与放弃。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { createAguiClient, frameToData, parseEventFrame, parseSseFrames } from './aguiClient';
import { AGUI_EVENT, type AguiHandlers } from './types';

vi.mock('@/utils/auth', () => ({ getToken: () => 'test-token' }));

const immediate = { schedule: (fn: () => void) => fn() };

/** 造一个 SSE Response（Node 20 全局 ReadableStream） */
function sseResponse(chunks: string[], status = 200): Response {
  const encoder = new TextEncoder();
  const stream = new ReadableStream<Uint8Array>({
    start(controller) {
      for (const c of chunks) {
        controller.enqueue(encoder.encode(c));
      }
      controller.close();
    }
  });
  return new Response(stream, { status, headers: { 'content-type': 'text/event-stream' } });
}

function ev(type: string, extra: Record<string, unknown> = {}): string {
  return 'data: ' + JSON.stringify({ type, threadId: 't1', runId: 'r1', ...extra }) + '\n\n';
}

let calls: string[];
function recorder(extra: Partial<AguiHandlers> = {}): AguiHandlers {
  calls = [];
  const rec =
    (name: string) =>
    (...args: unknown[]) =>
      calls.push(name + ':' + args.join('|'));
  return {
    onTextStart: rec('ts'),
    onTextDelta: rec('td'),
    onTextEnd: rec('te'),
    onToolStart: rec('ws'),
    onToolEnd: rec('we'),
    onToolResult: rec('wr'),
    onFinished: rec('fin'),
    onRunError: rec('err'),
    ...extra
  } as AguiHandlers;
}

describe('SSE 帧解析', () => {
  it('多帧切分+尾部半帧保留', () => {
    const { frames, rest } = parseSseFrames('data: 1\n\ndata: 2\n\ndata: 3');
    expect(frames.length).toBe(2);
    expect(rest).toBe('data: 3');
  });

  it('多行 data 拼接（SSE 规范）+ 非 data 行忽略', () => {
    expect(frameToData('event: x\ndata: a\ndata: b')).toBe('a\nb');
    expect(frameToData(': keep-alive')).toBeNull();
  });

  it('非 JSON 帧容错为 null', () => {
    expect(parseEventFrame('data: not-json')).toBeNull();
  });
});

describe('createAguiClient', () => {
  const originalFetch = globalThis.fetch;
  beforeEach(() => {
    vi.stubGlobal('VITE_APP_BASE_API', undefined);
  });
  afterEach(() => {
    globalThis.fetch = originalFetch;
    vi.restoreAllMocks();
  });

  it('完整事件序分发到类型化回调+终态 finished', async () => {
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(
        sseResponse([
          ev(AGUI_EVENT.RUN_STARTED),
          ev(AGUI_EVENT.TEXT_MESSAGE_START, { messageId: 'm1', role: 'assistant' }),
          ev(AGUI_EVENT.TEXT_MESSAGE_CONTENT, { messageId: 'm1', delta: '你' }),
          ev(AGUI_EVENT.TEXT_MESSAGE_CONTENT, { messageId: 'm1', delta: '好' }),
          ev(AGUI_EVENT.TEXT_MESSAGE_END, { messageId: 'm1' }),
          ev(AGUI_EVENT.TOOL_CALL_START, { toolCallId: 'c1', toolCallName: 'query_my_todos' }),
          ev(AGUI_EVENT.TOOL_CALL_END, { toolCallId: 'c1' }),
          ev(AGUI_EVENT.TOOL_CALL_RESULT, { toolCallId: 'c1', content: '{"rows":20}' }),
          ev(AGUI_EVENT.RUN_FINISHED, { outcome: { type: 'success' } })
        ])
      );
    const h = recorder();
    const client = createAguiClient();
    const handle = client.startTurn({ runId: 'r1', threadId: 't1', messages: [] }, h);
    await expect(handle.done).resolves.toBe('finished');
    expect(calls.join(',')).toBe('ts:m1|assistant,td:m1|你,td:m1|好,te:m1,ws:c1|query_my_todos,we:c1,wr:c1|{"rows":20},fin:[object Object]');
  });

  it('HTTP 401 形态→onAuthFailure 且不重试', async () => {
    const authFail = vi.fn();
    globalThis.fetch = vi.fn().mockResolvedValue(new Response('denied', { status: 401 }));
    const client = createAguiClient({ ...immediate, onAuthFailure: authFail });
    const handle = client.startTurn({ runId: 'r1', threadId: 't1', messages: [] }, recorder());
    await expect(handle.done).resolves.toBe('error');
    expect(authFail).toHaveBeenCalledWith('HTTP 401');
    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
  });

  it('HTTP 200+body code 401（RuoYi 惯例）→onAuthFailure', async () => {
    const authFail = vi.fn();
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ code: 401, msg: '未登录' }), { status: 200, headers: { 'content-type': 'application/json' } })
      );
    const client = createAguiClient({ ...immediate, onAuthFailure: authFail });
    const handle = client.startTurn({ runId: 'r1', threadId: 't1', messages: [] }, recorder());
    await expect(handle.done).resolves.toBe('error');
    expect(authFail).toHaveBeenCalledWith('body code 401');
  });

  it('RUN_ERROR→onRunError 人话话术+终态 error', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(sseResponse([ev(AGUI_EVENT.RUN_ERROR, { message: '今日对话额度已用完', code: '429' })]));
    const h = recorder();
    const client = createAguiClient({ ...immediate });
    const handle = client.startTurn({ runId: 'r1', threadId: 't1', messages: [] }, h);
    await expect(handle.done).resolves.toBe('error');
    expect(calls[0]).toBe('err:今日对话额度已用完|429');
  });

  it('PD-5: SDK 错误 RAW 帧(event.error)→onRunError，终态 error 不重试', async () => {
    // 12机限流实测帧序：RAW{error:话术} 紧跟 RUN_FINISHED（SDK 层 catch 的收尾形态）。
    // RAW 转发经 gate 置 terminal=error 提前断流（FINISHED 不读），done=error 且不触发退避重试
    globalThis.fetch = vi
      .fn()
      .mockResolvedValue(
        sseResponse([
          ev(AGUI_EVENT.RAW, { event: { error: '今日对话额度已用完（每人每日 2 回合），明日 0 点自动恢复' } }),
          ev(AGUI_EVENT.RUN_FINISHED)
        ])
      );
    const h = recorder();
    const client = createAguiClient({ ...immediate });
    const handle = client.startTurn({ runId: 'r1', threadId: 't1', messages: [] }, h);
    await expect(handle.done).resolves.toBe('error');
    // gate 透传 (msg, code=undefined)，recorder join 后尾带 "|"——只断言前缀语义
    expect(calls[0]?.startsWith('err:今日对话额度已用完（每人每日 2 回合），明日 0 点自动恢复')).toBe(true);
    expect(globalThis.fetch).toHaveBeenCalledTimes(1);
  });

  it('PD-5: RAW 帧无 error 载荷时不误报（透传忽略）', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue(sseResponse([ev(AGUI_EVENT.RAW, { event: { foo: 'bar' } }), ev(AGUI_EVENT.RUN_FINISHED)]));
    const h = recorder();
    const client = createAguiClient({ ...immediate });
    const handle = client.startTurn({ runId: 'r1', threadId: 't1', messages: [] }, h);
    await expect(handle.done).resolves.toBe('finished');
    expect(calls.filter((c) => c.startsWith('err'))).toHaveLength(0);
  });

  it('停止幂等：stop 后 done=stopped，重复 stop 无害', async () => {
    let enqueue: (c: string) => void = () => {};
    const encoder = new TextEncoder();
    const stream = new ReadableStream<Uint8Array>({
      start(controller) {
        enqueue = (c) => controller.enqueue(encoder.encode(c));
        enqueue(ev(AGUI_EVENT.RUN_STARTED));
      }
    });
    globalThis.fetch = vi.fn().mockResolvedValue(new Response(stream, { status: 200, headers: { 'content-type': 'text/event-stream' } }));
    const client = createAguiClient({ ...immediate });
    const handle = client.startTurn({ runId: 'r1', threadId: 't1', messages: [] }, recorder());
    handle.stop();
    handle.stop();
    await expect(handle.done).resolves.toBe('stopped');
  });

  it('断线指数退避：首连失败→重试成功，onRetry 报次数', async () => {
    const retries: Array<[number, number]> = [];
    globalThis.fetch = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockResolvedValueOnce(sseResponse([ev(AGUI_EVENT.RUN_FINISHED)]));
    const client = createAguiClient({ ...immediate, onRetry: (n, ms) => retries.push([n, ms]) });
    const handle = client.startTurn({ runId: 'r1', threadId: 't1', messages: [] }, recorder());
    await expect(handle.done).resolves.toBe('finished');
    expect(retries.length).toBe(1);
    expect(retries[0][0]).toBe(1);
  });

  it('重试耗尽→onDisconnected+终态 disconnected', async () => {
    const disconnected = vi.fn();
    globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('network down'));
    const client = createAguiClient({ ...immediate, maxRetries: 2, onDisconnected: disconnected });
    const handle = client.startTurn({ runId: 'r1', threadId: 't1', messages: [] }, recorder());
    await expect(handle.done).resolves.toBe('disconnected');
    expect(disconnected).toHaveBeenCalledWith(2);
    expect(globalThis.fetch).toHaveBeenCalledTimes(3);
  });

  it('E2E 回归：content-type=application/json 的 SSE 流照常解析（starter 实测形态）', async () => {
    const disconnected = vi.fn();
    const { startTurn } = createAguiClient({ ...immediate, onDisconnected: disconnected });
    const res = sseResponse(['data: {"type":"RUN_STARTED","runId":"r1"}\n\n', 'data: {"type":"RUN_FINISHED","runId":"r1"}\n\n']);
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response(res.body, { status: 200, headers: { 'content-type': 'application/json;charset=UTF-8' } }))
    );
    const finished = vi.fn();
    const h = { onFinished: finished };
    const turn = startTurn({ runId: 'r1', threadId: 't1', messages: [] }, h);
    expect(await turn.done).toBe('finished');
    expect(finished).toHaveBeenCalledTimes(1);
    vi.unstubAllGlobals();
  });

  it('E2E 回归：HTTP 200 + 单体业务 JSON（超限话术）直取 msg 不重试', async () => {
    const onRetry = vi.fn();
    const { startTurn } = createAguiClient({ ...immediate, onRetry });
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => new Response('{"code":500,"msg":"今日 50 回合已用完"}', { status: 200, headers: { 'content-type': 'application/json' } }))
    );
    const runError = vi.fn();
    const turn = startTurn({ runId: 'r2', threadId: 't1', messages: [] }, { onRunError: runError });
    expect(await turn.done).toBe('error');
    expect(runError).toHaveBeenCalledWith('今日 50 回合已用完', '500');
    expect(onRetry).not.toHaveBeenCalled();
    vi.unstubAllGlobals();
  });

  // goal 加固 G1：无帧看门狗——停滞（连接活着但 0 字节）不再无限悬挂
  it('停滞流触发看门狗→按断线重试，onRetry 带 stall 话术', async () => {
    const stalledStream = new ReadableStream<Uint8Array>({
      start() {
        /* 永不出帧也不断流 */
      }
    });
    const retries: Array<[number, number, string | undefined]> = [];
    globalThis.fetch = vi
      .fn()
      .mockResolvedValueOnce(new Response(stalledStream, { status: 200, headers: { 'content-type': 'text/event-stream' } }))
      .mockResolvedValueOnce(sseResponse([ev(AGUI_EVENT.RUN_FINISHED)]));
    const client = createAguiClient({
      ...immediate,
      // 看门狗立即触发（生产默认 180s）
      setStallTimer: (fn) => {
        fn();
        return () => {};
      },
      onRetry: (n, ms, reason) => retries.push([n, ms, reason])
    });
    const handle = client.startTurn({ runId: 'r1', threadId: 't1', messages: [] }, recorder());
    await expect(handle.done).resolves.toBe('finished');
    expect(globalThis.fetch).toHaveBeenCalledTimes(2, '停滞那次作废，重连成功');
    expect(retries.length).toBe(1);
    expect(retries[0][2]).toBe('stall', '停滞与断网分开话术');
  });

  it('断网重试 reason=network（与停滞区分的回归锚）', async () => {
    const reasons: Array<string | undefined> = [];
    globalThis.fetch = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('network down'))
      .mockResolvedValueOnce(sseResponse([ev(AGUI_EVENT.RUN_FINISHED)]));
    const client = createAguiClient({ ...immediate, onRetry: (_n, _ms, reason) => reasons.push(reason) });
    const handle = client.startTurn({ runId: 'r1', threadId: 't1', messages: [] }, recorder());
    await expect(handle.done).resolves.toBe('finished');
    expect(reasons).toEqual(['network']);
  });

  it('看门狗按读循环反复武装（有帧流不被误杀的计数锚）', async () => {
    let armed = 0;
    globalThis.fetch = vi.fn().mockResolvedValueOnce(sseResponse([ev(AGUI_EVENT.RUN_STARTED), ev(AGUI_EVENT.RUN_FINISHED)]));
    const client = createAguiClient({
      ...immediate,
      setStallTimer: (fn, ms) => {
        armed += 1;
        expect(ms).toBeGreaterThan(0);
        return () => {};
      }
    });
    const handle = client.startTurn({ runId: 'r1', threadId: 't1', messages: [] }, recorder());
    await expect(handle.done).resolves.toBe('finished');
    expect(armed).toBeGreaterThanOrEqual(2, '多帧流=看门狗多次武装多次重置');
  });
});
