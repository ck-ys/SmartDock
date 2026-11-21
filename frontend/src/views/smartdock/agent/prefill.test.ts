import { describe, expect, it } from 'vitest';
import { buildSkillDraft, guideSteps, shouldGhostOnHover, skillDesc, triggerPhrases } from './prefill';

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

describe('设置页技能详情（2026-11-05 A3/题2 丙）', () => {
  it('「你可以这样说」取描述里的引号段，按 / 拆开去重；没有引号段就空数组', () => {
    expect(triggerPhrases('用户说「写周报/帮我写本周周报/周报怎么写」时，按三步问法帮用户成稿')).toEqual([
      '写周报',
      '帮我写本周周报',
      '周报怎么写'
    ]);
    expect(triggerPhrases('用户说「写周报 / 帮我写本周周报」时，成稿')).toEqual(['写周报', '帮我写本周周报']);
    expect(triggerPhrases('写不伤人的催办提醒')).toEqual([]);
    expect(triggerPhrases('')).toEqual([]);
  });

  it('「它会怎么带你做」只取正文第一条编号清单（真文件里的流程），遇到下一节标题就停', () => {
    const weekly = [
      '你正在使用「写周报」技能。',
      '',
      '流程（按三步问，一次只问一步，别一口气全问）：',
      '1. 先问：本周主要做了哪些事？',
      '2. 再问：过程中遇到什么问题或卡点？',
      '3. 最后问：下周打算做什么？',
      '',
      '成稿格式（三段）：',
      '- 本周进展：按条列'
    ].join('\n');
    expect(guideSteps(weekly)).toEqual(['先问：本周主要做了哪些事？', '再问：过程中遇到什么问题或卡点？', '最后问：下周打算做什么？']);
  });

  it('清单里的缩进子条目不算新步骤、也不提前结束', () => {
    const dataAnalysis = [
      '流程：',
      '1. 先问口径：看什么指标。',
      '2. 取数：调用问数据工具拿真数。',
      '3. 成稿，格式固定：',
      '   - 第一句=结论',
      '   - 然后=依据',
      '呈现规则：',
      '- 有 3 个以上可比项时配一张统计图'
    ].join('\n');
    expect(guideSteps(dataAnalysis)).toEqual(['先问口径：看什么指标。', '取数：调用问数据工具拿真数。', '成稿，格式固定：']);
  });

  it('纯散文正文没有编号清单：返回空数组（页面整段不渲染，不编步骤）', () => {
    expect(guideSteps('本技能产出的是页级提纲。\n不需要编号。')).toEqual([]);
    expect(guideSteps('')).toEqual([]);
  });

  it('缩进编号是子条目，不拍平成顶层步骤（三审 P2：注释说只认顶格，实现要一致）', () => {
    const body = ['1. 先问本周做了什么。', '   1. 子条目甲', '   2. 子条目乙', '2. 再问卡点。'].join('\n');
    expect(guideSteps(body)).toEqual(['先问本周做了什么。', '再问卡点。']);
  });
});
