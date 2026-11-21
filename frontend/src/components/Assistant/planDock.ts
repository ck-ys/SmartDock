import type { AssistantTurn } from './useAssistant';

export type AssistantPlan = NonNullable<AssistantTurn['plan']>;

/**
 * 计划卡数据源：只认“最新一张仍在等待处理”的计划卡。
 *
 * 后端同一会话同时只保留一张待确认计划（新的 submit_plan 会覆盖旧的）；前端历史里
 * 可能还挂着更早的卡，旧卡由 {@link useAssistant} 在新计划到达时标成 expired。
 * 这里从后往前找，避免把历史卡当成可操作目标。
 */
export function latestPendingPlan(turns: AssistantTurn[]): { turn: AssistantTurn; plan: AssistantPlan } | null {
  for (let i = turns.length - 1; i >= 0; i -= 1) {
    const plan = turns[i].plan;
    if (plan) {
      // 只认“最新一张计划卡”；它已经确认/取消/失效时直接返回 null，
      // 绝不回退到更早的 pending 卡（Q5-A：旧卡不可复活）。
      return !plan.status || plan.status === 'pending' ? { turn: turns[i], plan } : null;
    }
  }
  return null;
}

/** 卡号尾号：给人看的“只作废这一张”凭据；没有编号返回空串 */
export function planTail(planId?: string): string {
  if (!planId) {
    return '';
  }
  return planId.slice(-4).toUpperCase();
}
