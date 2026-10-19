/**
 * 联网搜索会话级开关存取（业务批3-④ #17，2026-10-12）。
 *
 * 单一真源=localStorage `dcs:assistant:webOn:{userId}:{threadId}`（'1'=开，无键=关，
 * 默认关=grill Q6 定稿）——➕面板开关行/底栏 🌐 一步钮（R1）与 useAssistant.runTurn
 * 出口注入（thinkTier/modelPin 同款「内部路径不丢参」模式）三处共用，避免状态分叉。
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

/** 读开关（键缺失/值非 '1'/storage 异常一律=关，保守默认） */
export function readWebOn(userId: string | number, threadId: string, storage: KVLike): boolean {
  if (!threadId) {
    return false;
  }
  try {
    return storage.getItem(webOnKey(userId, threadId)) === '1';
  } catch {
    return false;
  }
}

/** 写开关（on=false 即删键，不留脏 '0' 值） */
export function writeWebOn(userId: string | number, threadId: string, on: boolean, storage: KVLike): void {
  if (!threadId) {
    return;
  }
  try {
    const key = webOnKey(userId, threadId);
    if (on) {
      storage.setItem(key, '1');
    } else {
      storage.removeItem(key);
    }
  } catch {
    /* 存储不可用忽略（内存 ref 仍是即时状态源，仅持久化失败） */
  }
}
