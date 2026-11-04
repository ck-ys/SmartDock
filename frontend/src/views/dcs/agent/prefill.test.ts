import { describe, expect, it } from 'vitest';
import { buildSkillDraft, shouldClearOnLeave, shouldPrefillOnHover, skillDesc } from './prefill';

describe('技能卡悬停预填（拍板 2026-11-04 题一/二/四甲）', () => {
  it('描述里的触发词前缀被去掉：卡片和预填句都不带「用户说…时」', () => {
    expect(skillDesc('用户说「写周报/帮我写周报」时，按三步问法帮用户成稿（进展/问题/下周计划）')).toBe('按三步问法帮用户成稿（进展/问题/下周计划）');
    expect(skillDesc('  写不伤人的催办提醒  ')).toBe('写不伤人的催办提醒');
    expect(skillDesc('')).toBe('');
  });

  it('整句 = 前缀 + 技能描述（题二甲：用技能库自己的描述）', () => {
    expect(buildSkillDraft('写周报', '用户说「写周报」时，按三步问法帮用户成稿')).toBe('用「写周报」帮我：按三步问法帮用户成稿');
    expect(buildSkillDraft('写周报', '')).toBe('用「写周报」帮我：');
  });

  it('框是空的 → 悬停填；框里有用户打的字 → 一个字不动', () => {
    expect(shouldPrefillOnHover('', null)).toBe(true);
    expect(shouldPrefillOnHover('   ', null)).toBe(true);
    expect(shouldPrefillOnHover('帮我把上周的报销单核对一下', null)).toBe(false);
  });

  it('框里还是我们上次填的那句 → 换卡可以直接换（不算用户的字）', () => {
    const ours = '用「写周报」帮我：按三步问法帮用户成稿';
    expect(shouldPrefillOnHover(ours, ours)).toBe(true);
    expect(shouldPrefillOnHover('帮我把上周的报销单核对一下', ours)).toBe(false);
  });

  it('移开时只撤回「没改过的那句」；改过一个字就归用户', () => {
    const ours = '用「写周报」帮我：按三步问法帮用户成稿';
    expect(shouldClearOnLeave(ours, ours)).toBe(true);
    expect(shouldClearOnLeave(` ${ours}`, ours)).toBe(false);
    expect(shouldClearOnLeave(ours, null)).toBe(false);
    expect(shouldClearOnLeave('', ours)).toBe(false);
  });
});
