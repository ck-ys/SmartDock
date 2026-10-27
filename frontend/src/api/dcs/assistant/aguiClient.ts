/**
 * AGUI SSE 流客户端（批E Task E1，spec §10.1 SSE 通道分工裁决）。
 *
 * <p>对话通道=本文件（POST + fetch 流式读，鉴权头显式携带）；通知推送仍走
 * utils/sse.ts（EventSource）——两通道并存不互斥。URL 统一
 * {@code VITE_APP_BASE_API + '/agui/run/dcs-assistant'}：复用 nginx /prod-api/
 * 与 Vite /dev-api 代理，禁裸 /agui（context-path=/ 下会命中 SPA 兜底回 index.html）。</p>
 *
 * <p>纪律（spec §10.1）：</p>
 * <ul>
 *   <li>401 双形态识别：HTTP 401（路径过滤器）与 HTTP 200+body.code 401（RuoYi
 *       惯例 JSON）都判失效并回调 onAuthFailure（登出跳转由调用方决定）；</li>
 *   <li>断线独立指数退避 1s/2s/4s…上限 60s + 随机抖动，重试上限后回调
 *       onDisconnected（UI 明示「连接已断，点击重试」）；RUN_FINISHED/ERROR
 *       终态后不再重试；</li>
 *   <li>停止钮=AbortController（浏览器原生中断，P95&lt;500ms）；</li>
 *   <li>mid-stream 断线重试=同 input 重发（后端 runId 首绑幂等，user 消息不双落；
 *       回合计数按新尝试计——悬挂 run 由后端 15 分钟兜底退回，登记口径）。</li>
 * </ul>
 */
import { AGUI_EVENT, type AguiEventFrame, type AguiHandlers, type AguiRunInput } from './types';
import { getToken } from '@/utils/auth';

export interface AguiClientOptions {
  /** 完整端点；默认 import.meta.env.VITE_APP_BASE_API + '/agui/run/dcs-assistant' */
  url?: string;
  /** 鉴权失效回调（401 双形态） */
  onAuthFailure?: (detail: string) => void;
  /** 退避重试耗尽回调 */
  onDisconnected?: (attempt: number) => void;
  /** 每次重试前回调（UI 可显示「正在重连(第 N 次)…」）；reason 区分断网/停滞话术（goal 加固 G1） */
  onRetry?: (attempt: number, delayMs: number, reason?: 'network' | 'stall') => void;
  /** 断线重试次数上限（默认 5：≈1+2+4+8+16s 内五连败才放弃） */
  maxRetries?: number;
  /** 退避上限毫秒（默认 60_000，spec 口径） */
  maxBackoffMs?: number;
  /** 测试注替时钟/随机（生产缺省用 setTimeout/Math.random） */
  schedule?: (fn: () => void, ms: number) => void;
  /** goal 加固 G1：无帧看门狗计时器注替（返回取消函数；生产缺省 setTimeout/clearTimeout 对） */
  setStallTimer?: (fn: () => void, ms: number) => () => void;
  /** goal 加固 G1（稳流① Q1 调整）：停滞超时毫秒（连接活着但 0 字节）——默认 600s：
   *  后端已挂 20s 真事件心跳（dcs-heartbeat CUSTOM 帧），帧停 600s=链路真断而非合法静默；
   *  超窗不自动重发（重发撞 409 防双计费），裁决交 AguiHandlers.onStall（无回调才退旧重试口径） */
  stallTimeoutMs?: number;
  jitter?: () => number;
}

/** SSE 帧切分：按空行分帧，帧内取 data: 行（多行 data 拼接，SSE 规范） */
export function parseSseFrames(buffer: string): { frames: string[]; rest: string } {
  const frames: string[] = [];
  let rest = buffer;
  // 兼容 \n\n 与 \r\n\r\n 分帧（starter 输出 \n\n，浏览器规范化后兜底）
  const sep = rest.includes('\r\n\r\n') && !rest.includes('\n\n') ? '\r\n\r\n' : '\n\n';
  let idx = rest.indexOf(sep);
  while (idx >= 0) {
    frames.push(rest.slice(0, idx));
    rest = rest.slice(idx + sep.length);
    idx = rest.indexOf(sep);
  }
  return { frames, rest };
}

/** 单帧 → data 文本（无 data: 行返回 null：注释行/事件行忽略） */
export function frameToData(frame: string): string | null {
  const datas: string[] = [];
  for (const line of frame.split(/\r?\n/)) {
    if (line.startsWith('data:')) {
      datas.push(line.slice(5).replace(/^ /, ''));
    }
  }
  return datas.length === 0 ? null : datas.join('\n');
}

/** 单帧 data 文本 → 事件对象（非 JSON 返回 null；原型冒烟里错误帧也是 data: JSON） */
export function parseEventFrame(frame: string): AguiEventFrame | null {
  const data = frameToData(frame);
  if (data === null) {
    return null;
  }
  try {
    return JSON.parse(data) as AguiEventFrame;
  } catch {
    return null;
  }
}

function dispatch(ev: AguiEventFrame, h: AguiHandlers) {
  h.onEvent?.(ev);
  switch (ev.type) {
    case AGUI_EVENT.TEXT_MESSAGE_START:
      h.onTextStart?.(ev.messageId ?? '', ev.role);
      break;
    case AGUI_EVENT.TEXT_MESSAGE_CONTENT:
      h.onTextDelta?.(ev.messageId ?? '', ev.delta ?? '');
      break;
    case AGUI_EVENT.TEXT_MESSAGE_END:
      h.onTextEnd?.(ev.messageId ?? '');
      break;
    case AGUI_EVENT.REASONING_MESSAGE_START:
      h.onReasoningStart?.(ev.messageId ?? '');
      break;
    case AGUI_EVENT.REASONING_MESSAGE_CONTENT:
      h.onReasoningDelta?.(ev.messageId ?? '', ev.delta ?? '');
      break;
    case AGUI_EVENT.REASONING_MESSAGE_END:
      h.onReasoningEnd?.(ev.messageId ?? '');
      break;
    case AGUI_EVENT.TOOL_CALL_START:
      h.onToolStart?.(ev.toolCallId ?? '', ev.toolCallName ?? '');
      break;
    case AGUI_EVENT.TOOL_CALL_ARGS:
      h.onToolArgs?.(ev.toolCallId ?? '', ev.delta ?? '');
      break;
    case AGUI_EVENT.TOOL_CALL_END:
      h.onToolEnd?.(ev.toolCallId ?? '');
      break;
    case AGUI_EVENT.TOOL_CALL_RESULT:
      h.onToolResult?.(ev.toolCallId ?? '', ev.content ?? '');
      break;
    case AGUI_EVENT.STEP_STARTED:
      h.onStepStarted?.(ev.stepName as string | undefined);
      break;
    case AGUI_EVENT.STEP_FINISHED:
      h.onStepFinished?.(ev.stepName as string | undefined);
      break;
    case AGUI_EVENT.RUN_FINISHED:
      h.onFinished?.(ev.outcome);
      break;
    case AGUI_EVENT.RUN_ERROR:
      h.onRunError?.(ev.message ?? '服务开小差了，请稍后再试', ev.code);
      break;
    case AGUI_EVENT.CUSTOM:
      h.onCustom?.(ev.name ?? '', ev.value);
      break;
    case AGUI_EVENT.RAW:
      // PD-5(2026-10-07 试点走查): SDK 层 catch 的一切异常(准入闸 403/429/503、agent 缺失等)
      // 以 RAW 帧承载({type:"RAW",event:{error:"话术"}})并紧跟 RUN_FINISHED 收尾(12机限流
      // 实测帧), 而非标准 RUN_ERROR——不接住则错误被 FINISHED 盖成「已回答」空壳回合
      if (ev.event && typeof ev.event === 'object' && 'error' in (ev.event as Record<string, unknown>)) {
        const em = (ev.event as Record<string, unknown>).error;
        if (typeof em === 'string' && em) {
          h.onRunError?.(em);
        }
      }
      break;
    default:
      break;
  }
}

export interface AguiTurnHandle {
  /** 停止（幂等：重复调用无害） */
  stop: () => void;
  /** 本轮终态 promise（RUN_FINISHED/ERROR/放弃/手动停止 都 resolve，永不 reject） */
  done: Promise<'finished' | 'error' | 'stopped' | 'disconnected'>;
}

/** attach（题5 续看）一次重连的结果；未命中缓冲（JSON 错误体零帧）也收口 'error'，调用方静默回退轮询兜底 */
export type AguiAttachResult = 'finished' | 'error' | 'stopped';
export interface AguiAttachHandle {
  /** 停止（幂等） */
  stop: () => void;
  /** 终态 promise（永不 reject） */
  done: Promise<AguiAttachResult>;
}

export function createAguiClient(options: AguiClientOptions = {}) {
  const url = options.url ?? `${import.meta.env.VITE_APP_BASE_API}/agui/run/dcs-assistant`;
  const maxRetries = options.maxRetries ?? 5;
  const maxBackoffMs = options.maxBackoffMs ?? 60_000;
  const schedule = options.schedule ?? ((fn, ms) => setTimeout(fn, ms));
  const jitter = options.jitter ?? (() => Math.random());
  const setStallTimer =
    options.setStallTimer ??
    ((fn: () => void, ms: number) => {
      const id = setTimeout(fn, ms);
      return () => clearTimeout(id);
    });
  const stallTimeoutMs = options.stallTimeoutMs ?? 600_000;

  /** 停滞哨兵：看门狗超窗以 resolve（非 reject）出循环——停滞≠断线，裁决权在 onStall */
  const STALLED = { stalled: true } as const;

  async function readStream(response: Response, h: AguiHandlers, signal: AbortSignal): Promise<'finished' | 'error' | 'stopped'> {
    const reader = (response.body as ReadableStream<Uint8Array>).getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';
    let terminal: 'finished' | 'error' | null = null;
    // 包一层捕获终态：dispatch 到 RUN_FINISHED/ERROR 时置位
    const gate: AguiHandlers = {
      ...h,
      onFinished: (o) => {
        terminal = 'finished';
        h.onFinished?.(o);
      },
      onRunError: (m, c) => {
        terminal = 'error';
        h.onRunError?.(m, c);
      }
    };
    // 稳流① Q1：pending read 跨停滞窗口复用——用户裁决「继续等」后与同一 promise 续竞速
    // （不二次调 read()，避免依赖 reader 的并发排队语义）
    let pendingRead: Promise<ReadableStreamReadResult<Uint8Array>> | null = null;
    for (;;) {
      if (!pendingRead) {
        pendingRead = reader.read();
      }
      // abort 竞速：真实 fetch 会联动取消 body 流，但 mock/降级环境不保证——统一竞速兜底
      const aborted = new Promise<never>((_, reject) => {
        // 信号可能早于注册已 abort（stop 在 fetch resolve 前调用）——事件不会补发，必须预检
        if (signal.aborted) {
          reject(new DOMException('aborted', 'AbortError'));
          return;
        }
        signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
      });
      // goal 加固 G1（稳流① Q1 调整）：无帧看门狗——停滞（连接活着但 0 字节）时
      // reader.read() 会无限悬挂；每次读循环武装计时器，收到任意字节（含后端 20s
      // dcs-heartbeat 心跳帧）由下一轮重建即「重置」。超窗不自动重发：服务端可能仍在
      // 生成，重发撞 409 防双计费——有 onStall 回调交用户裁决（继续等/停止），无回调
      // （测试/降级环境）退旧口径 reject 走退避重试
      let stallCancel: (() => void) | null = null;
      const stalled = new Promise<typeof STALLED>((resolve) => {
        stallCancel = setStallTimer(() => resolve(STALLED), stallTimeoutMs);
      });
      let value: Uint8Array | undefined;
      let done: boolean;
      try {
        const r = await Promise.race([pendingRead, aborted, stalled]);
        stallCancel?.();
        if (r === STALLED) {
          // 稳流① Q1（拍板 A②）：停滞裁决——keep-waiting 原流续等（重挂看门狗），
          // stop 按手动停止收口；无 onStall 兜底旧 reject 口径
          if (!h.onStall) {
            try {
              await reader.cancel();
            } catch {
              /* 对端已关流 */
            }
            throw new DOMException('stalled', 'AbortError');
          }
          let choice: 'keep-waiting' | 'stop';
          try {
            choice = await h.onStall();
          } catch {
            choice = 'stop';
          }
          if (choice === 'keep-waiting') {
            continue;
          }
          try {
            await reader.cancel();
          } catch {
            /* 对端已关流 */
          }
          return 'stopped';
        }
        if (!r || typeof r !== 'object' || !('done' in r)) {
          // G5 三审回修（dspro P2）：race 只应产出读结果/哨兵——哨兵路径已在上方全部
          // return/continue/throw，落到这里=未来新增哨兵的静默空转，显式抛错暴露
          throw new DOMException('unexpected race result', 'AbortError');
        }
        value = r.value;
        done = r.done;
        pendingRead = null;
      } catch (e) {
        stallCancel?.();
        try {
          await reader.cancel();
        } catch {
          /* 对端已关流 */
        }
        throw e instanceof DOMException ? e : new DOMException('aborted', 'AbortError');
      }
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      const { frames, rest } = parseSseFrames(buffer);
      buffer = rest;
      for (const f of frames) {
        const ev = parseEventFrame(f);
        if (ev) {
          dispatch(ev, gate);
          if (terminal) {
            try {
              await reader.cancel();
            } catch {
              /* 对端已关流 */
            }
            return terminal;
          }
        }
      }
    }
    // 流自然结束但无终态帧：RuoYi 全局异常=HTTP 200 + 单体 JSON（无 data: 前缀，批G 登记）——
    // 尾部残留若为业务 JSON 直取话术（超限/余额不足等前置拦截都走此形态）
    const tail = buffer.trim();
    if (tail.startsWith('{')) {
      try {
        const body = JSON.parse(tail) as { code?: number; msg?: string };
        if (body.code && body.code !== 200) {
          if (body.code === 401) {
            options.onAuthFailure?.('body code 401');
          } else {
            h.onRunError?.(body.msg ?? '请求失败', String(body.code));
          }
          return 'error';
        }
      } catch {
        /* 非完整 JSON 忽略 */
      }
    }
    // 真·半截流，按断线语义交给上层重试
    return terminal ?? 'error';
  }

  async function requestOnce(input: AguiRunInput, h: AguiHandlers, signal: AbortSignal): Promise<'finished' | 'error' | 'stopped' | 'retry' | 'retry-stall'> {
    let response: Response;
    try {
      response = await fetch(url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Accept: 'text/event-stream',
          Authorization: 'Bearer ' + getToken(),
          clientid: import.meta.env.VITE_APP_CLIENT_ID as string
        },
        body: JSON.stringify(input),
        signal
      });
    } catch (e) {
      if (signal.aborted) {
        return 'finished'; // 手动停止不算错误
      }
      return 'retry';
    }
    if (response.status === 401) {
      options.onAuthFailure?.('HTTP 401');
      return 'error';
    }
    // agentscope starter 的 SSE 响应 content-type=application/json（E2E 网络面板实证）——
    // 不按头做硬门槛：HTTP 200 一律按 SSE 流读；非流/错误 JSON 由帧解析容错（非 data: 帧忽略）
    if (!response.ok) {
      const text = await response.text().catch(() => '');
      try {
        const body = JSON.parse(text);
        if (body.code === 401) {
          options.onAuthFailure?.('body code 401');
          return 'error';
        }
        h.onRunError?.(body.msg ?? '请求失败', String(body.code ?? ''));
      } catch {
        h.onRunError?.(`请求失败（HTTP ${response.status}）`);
      }
      return 'error';
    }
    try {
      return await readStream(response, h, signal);
    } catch (e) {
      if (signal.aborted) {
        return 'finished';
      }
      // goal 加固 G1：看门狗超时且无 onStall 裁决（非用户停止）带 stall 标记重试——
      // onRetry 话术区分「连接停滞」；有 onStall 的生产路径停滞已在 readStream 内收口，不到这
      return e instanceof DOMException && e.message === 'stalled' ? 'retry-stall' : 'retry';
    }
  }

  /**
   * 发起一轮对话（含断线指数退避重试）。
   * 返回句柄：stop() 幂等停止；done Promise 在终态/停止/放弃后 resolve。
   */
  function startTurn(input: AguiRunInput, handlers: AguiHandlers): AguiTurnHandle {
    const controller = new AbortController();
    let stopped = false;
    let attempt = 0;
    let settle: (r: 'finished' | 'error' | 'stopped' | 'disconnected') => void;
    const done = new Promise<'finished' | 'error' | 'stopped' | 'disconnected'>((resolve) => {
      settle = resolve;
    });

    const run = async () => {
      for (;;) {
        if (controller.signal.aborted) {
          settle!('stopped');
          return;
        }
        const r = await requestOnce(input, handlers, controller.signal);
        if (r !== 'retry' && r !== 'retry-stall') {
          settle!(r === 'finished' && stopped ? 'stopped' : r);
          return;
        }
        attempt += 1;
        if (attempt > maxRetries) {
          options.onDisconnected?.(attempt - 1);
          settle!('disconnected');
          return;
        }
        const base = Math.min(1_000 * 2 ** (attempt - 1), maxBackoffMs);
        const delay = Math.round(base * (0.75 + 0.5 * jitter()));
        options.onRetry?.(attempt, delay, r === 'retry-stall' ? 'stall' : 'network');
        // 003：退避睡眠与停止信号竞速——此前纯 schedule 睡满，重试窗口点停止最长干等
        // 一次退避（序列可达 60s），违反文件头 P95<500ms 停止契约；打断后由循环顶检收口
        const wakeOnAbort = new Promise<never>((_, reject) => {
          if (controller.signal.aborted) {
            reject(new DOMException('aborted', 'AbortError'));
          } else {
            controller.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true });
          }
        });
        try {
          await Promise.race([new Promise<void>((wake) => schedule(wake, delay)), wakeOnAbort]);
        } catch {
          // 停止打断睡眠：循环顶检 signal.aborted → settle('stopped')
        }
      }
    };
    run();

    return {
      stop: () => {
        stopped = true;
        controller.abort();
      },
      done
    };
  }

  /**
   * 题5 续看：把页面刷新/断线时正在生成的一轮重新挂上（POST attach 端点）。
   * 与 startTurn 的差异：只读不重发（重放由服务端缓冲提供；重连兜底归外层轮询），
   * 读流即收口——未命中缓冲（本轮已结束/服务重启过/非本人会话）服务端回 JSON 错误体，
   * 零 data: 帧 → 'error' 收口（不触发 onRunError，降级话术由轮询 watcher 的既有路径给出）。
   */
  function attach(threadId: string, handlers: AguiHandlers): AguiAttachHandle {
    const controller = new AbortController();
    let settle: (r: AguiAttachResult) => void;
    const done = new Promise<AguiAttachResult>((resolve) => {
      settle = resolve;
    });
    (async () => {
      if (controller.signal.aborted) {
        settle!('stopped');
        return;
      }
      let response: Response;
      try {
        response = await fetch(`${import.meta.env.VITE_APP_BASE_API}/dcs/assistant/conversation/attach?threadId=${encodeURIComponent(threadId)}`, {
          method: 'POST',
          headers: {
            Accept: 'text/event-stream',
            Authorization: 'Bearer ' + getToken(),
            clientid: import.meta.env.VITE_APP_CLIENT_ID as string
          },
          signal: controller.signal
        });
      } catch {
        settle!(controller.signal.aborted ? 'stopped' : 'error');
        return;
      }
      if (response.status === 401) {
        options.onAuthFailure?.('HTTP 401');
        settle!('error');
        return;
      }
      if (!response.ok) {
        settle!('error');
        return;
      }
      // 本栈 SSE 的 content-type 头不可靠（主端点 /agui/run 一贯回 application/json 头+
      // 流正文，attach 实测还出现 json+event-stream 双值头）——与 requestOnce 同口径：
      // HTTP 200 一律按 SSE 流读。未命中缓冲的 JSON 错误体（本轮已结束/重启过）没有
      // data: 帧，流读到尾零终态 → readStream 收口 'error'，调用方静默交还轮询兜底。
      // 深审 P1-B 回修：readStream 尾部 JSON 分支会调 onRunError——未命中时不得把
      // 「本轮已结束」人话错标成本回合失败（污染在途占位），attach 侧剥掉该回调
      try {
        const r = await readStream(response, { ...handlers, onRunError: undefined }, controller.signal);
        settle!(r);
      } catch {
        settle!(controller.signal.aborted ? 'stopped' : 'error');
      }
    })();
    return {
      stop: () => controller.abort(),
      done
    };
  }

  return { startTurn, attach };
}
