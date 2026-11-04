import { describe, expect, it } from 'vitest';
import { latestPendingPlan, planTail } from './planDock';
import type { AssistantTurn } from './useAssistant';

const turn = (plan: AssistantTurn['plan']) => ({ plan }) as AssistantTurn;

describe('latestPendingPlan（输入框停靠区只认最新一张待确认卡）', () => {
  it('从后往前取最新 pending；已确认/已取消/已失效都跳过', () => {
    const turns = [
      turn({ planId: 'old', steps: [], status: 'pending' }),
      turn({ planId: 'done', steps: [], status: 'confirmed' }),
      turn({ planId: 'new', steps: [], status: 'pending' })
    ];
    expect(latestPendingPlan(turns)?.plan.planId).toBe('new');
  });

  it('最新卡已处理时返回 null，绝不回退到更早的 pending 卡（Q5-A：旧卡不可复活）', () => {
    const turns = [turn({ planId: 'old', steps: [], status: 'pending' }), turn({ planId: 'new', steps: [], status: 'cancelled' })];
    expect(latestPendingPlan(turns)).toBeNull();
  });

  it('status 缺省按 pending 处理', () => {
    const turns = [turn({ planId: 'legacy', steps: [] })];
    expect(latestPendingPlan(turns)?.plan.planId).toBe('legacy');
  });

  it('没有 pending 卡返回 null', () => {
    expect(latestPendingPlan([])).toBeNull();
    expect(latestPendingPlan([turn(null), turn({ planId: 'x', steps: [], status: 'expired' })])).toBeNull();
  });
});

describe('planTail（取消只作废这张卡的尾号凭据）', () => {
  it('取 planId 后四位并转大写', () => {
    expect(planTail('plan-20261102-ab7f2a')).toBe('7F2A');
  });

  it('短 id 原样大写；空值返回空串', () => {
    expect(planTail('ab12')).toBe('AB12');
    expect(planTail('a1')).toBe('A1');
    expect(planTail(undefined)).toBe('');
    expect(planTail('')).toBe('');
  });
});
