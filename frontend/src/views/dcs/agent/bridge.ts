/**
 * 工作台 ↔ 设置页 的一次性交接（2026-11-05 拍板 A3 / 题2 丙）。
 *
 * 为什么不用网址参数：题 2 丙 定了「同一页内不需要网址参数」——参数会留在地址栏、
 * 刷新会再填一遍、分享链接还会把别人的草稿带过去。这里只放内存（模块级单例）：
 * 刷新即忘、跨标签页不串；取用即清空（take*）。
 *
 * - pendingSkill：设置页点「试一句」写入；/agent 挂载时取走并填进输入框（5 分钟过期：
 *   导航失败或被权限拦下时，不把一句旧话留到很久以后再冒出来）。
 * - workspaceSnapshot：离开工作台去设置页时记下当前会话与草稿；回来时接上（30 分钟过期，
 *   避免隔很久再进工作台被一个旧快照拉到旧会话；没快照时 /agent 另有单例兜底）。
 */

export interface SkillTry {
  id: string;
  name: string;
  desc: string;
}

export interface WorkspaceSnapshot {
  threadId: string;
  draft: string;
  at: number;
}

const TTL_MS = 30 * 60_000;
/** 一句话的交接活不过 5 分钟：它只在「点完立刻回工作台」这一跳里有意义 */
const PENDING_TTL_MS = 5 * 60_000;

let pendingSkill: SkillTry | null = null;
let pendingAt = 0;
let snapshot: WorkspaceSnapshot | null = null;

export function setPendingSkill(s: SkillTry): void {
  pendingSkill = s;
  pendingAt = Date.now();
}

/** 取走即清空：同一次「试一句」只会填一遍 */
export function takePendingSkill(): SkillTry | null {
  const s = pendingSkill;
  pendingSkill = null;
  if (!s || Date.now() - pendingAt > PENDING_TTL_MS) {
    return null;
  }
  return s;
}

export function setWorkspaceSnapshot(s: Omit<WorkspaceSnapshot, 'at'>): void {
  snapshot = { ...s, at: Date.now() };
}

/** 取走即清空；超过 30 分钟当作没有（不把很久以前的旧会话硬拉回来） */
export function takeWorkspaceSnapshot(): WorkspaceSnapshot | null {
  const s = snapshot;
  snapshot = null;
  if (!s || Date.now() - s.at > TTL_MS) {
    return null;
  }
  return s;
}
