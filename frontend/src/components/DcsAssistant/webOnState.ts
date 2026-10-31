/**
 * 联网搜索会话级开关存取（题10 案1，2026-10-31：默认可用=模型自主判断，可停用）。
 *
 * 单一真源=localStorage `dcs:assistant:webOn:{userId}:{threadId}`：
 * 无键=自动（模型按问题自己判断搜不搜），'0'=用户在本会话显式停用。
 * ➕面板开关行 / 底栏 🌐 一步钮 / useAssistant.runTurn 出口注入三处共用，避免状态分叉。
 *
 * 抽纯函数模块=node 环境 vitest 可测（无 DOM，storage 由调用方注入）。
 */

/** storage 抽象：window.localStorage 或测试桩 */
export interface KVLike {
  getItem(k: string): string | null;
  setItem(k: string, v: string): void;
  removeItem(k: string): void;
}

/** 会话分键（与 modelPin 同款 userId+threadId 维度，各会话互不干扰） */
export function webOnKey(userId: string | number, threadId: string): string {
  return `dcs:assistant:webOn:${userId}:${threadId}`;
}

/**
 * 读开关：只有显式 '0' 才算停用，无键/其它值=自动（默认可用）。
 * storage 异常=保守关——读不到用户是否停用过，宁可不把内容发出去。
 */
export function readWebOn(userId: string | number, threadId: string, storage: KVLike): boolean {
  if (!threadId) {
    return true;
  }
  try {
    return storage.getItem(webOnKey(userId, threadId)) !== '0';
  } catch {
    return false;
  }
}

/** 写开关：开=删键（无键即默认自动，不留 '1' 残值）；关=写 '0' 显式停用 */
export function writeWebOn(userId: string | number, threadId: string, on: boolean, storage: KVLike): void {
  if (!threadId) {
    return;
  }
  try {
    const key = webOnKey(userId, threadId);
    if (on) {
      storage.removeItem(key);
    } else {
      storage.setItem(key, '0');
    }
  } catch {
    /* 存储不可用忽略（内存 ref 仍是即时状态源，仅持久化失败） */
  }
}
