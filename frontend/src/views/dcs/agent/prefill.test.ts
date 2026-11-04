import { describe, expect, it } from 'vitest';
import { buildSkillDraft, shouldGhostOnHover, skillDesc } from './prefill';

describe('技能卡悬停预填·灰字幽灵版（2026-11-04 用户反馈改 WorkBuddy 观感）', () => {
  it('描述里的触发词前缀被去掉：卡片和预填句都不带「用户说…时」', () => {
    expect(skillDesc('用户说「写周报/帮我写周报」时，按三步问法帮用户成稿（进展/问题/下周计划）')).toBe('按三步问法帮用户成稿（进展/问题/下周计划）');
    expect(skillDesc('  写不伤人的催办提醒  ')).toBe('写不伤人的催办提醒');
    expect(skillDesc('')).toBe('');
  });

  it('整句 = 前缀 + 技能描述（题二甲：用技能库自己的描述）', () => {
    expect(buildSkillDraft('写周报', '用户说「写周报」时，按三步问法帮用户成稿')).toBe('用「写周报」帮我：按三步问法帮用户成稿');
    expect(buildSkillDraft('写周报', '')).toBe('用「写周报」帮我：');
  });

  it('灰字幽灵只在空框显示：有用户打的字就不动', () => {
    expect(shouldGhostOnHover('')).toBe(true);
    expect(shouldGhostOnHover('   ')).toBe(true);
    expect(shouldGhostOnHover('帮我把上周的报销单核对一下')).toBe(false);
  });
});
