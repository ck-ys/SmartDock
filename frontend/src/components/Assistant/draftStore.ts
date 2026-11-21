/**
 * 会话草稿仓（对齐 ZCode composerDraftStore 的口径）：
 * - key = smartdock-agent-drafts:v1:<userId>:<scope>，scope 在草稿态固定为 draft，
 *   拿到真实 threadId 后迁到该 id；
 * - 只存非空白，删空即删键；
 * - userId 进 key，换账号读不到对方草稿。
 */
const PREFIX = 'smartdock-agent-drafts:v1';

function keyOf(userId: string | number, scope: string): string {
  return `${PREFIX}:${userId}:${scope}`;
}

export function readDraft(userId: string | number, scope: string): string {
  try {
    return localStorage.getItem(keyOf(userId, scope)) ?? '';
  } catch {
    return '';
  }
}

export function writeDraft(userId: string | number, scope: string, text: string): void {
  try {
    const key = keyOf(userId, scope);
    if (text.trim()) {
      localStorage.setItem(key, text);
    } else {
      localStorage.removeItem(key);
    }
  } catch {
    /* 存储不可用（隐私模式/配额满）忽略，草稿退化为内存态 */
  }
}

export function removeDraft(userId: string | number, scope: string): void {
  try {
    localStorage.removeItem(keyOf(userId, scope));
  } catch {
    /* 忽略 */
  }
}

/** 草稿态 → 真实会话：把 draft 的内容搬到 threadId；目标已有内容则以目标为准，旧键必删。 */
export function migrateDraft(userId: string | number, from: string, to: string): void {
  if (!from || !to || from === to) {
    return;
  }
  try {
    const fromKey = keyOf(userId, from);
    const toKey = keyOf(userId, to);
    const text = localStorage.getItem(fromKey);
    if (text && !localStorage.getItem(toKey)) {
      localStorage.setItem(toKey, text);
    }
    if (text !== null) {
      localStorage.removeItem(fromKey);
    }
  } catch {
    /* 忽略 */
  }
}
