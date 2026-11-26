import { describe, expect, it } from 'vitest';
import { computeCreditTiers, creditTierFormulaText } from './creditTier';

/**
 * 额度三级管控批（2026-11-26 / Q14=1 + Q21=1）档位现算单测
 * （板 07「③ 档位现算（周池 ÷ 7 ÷ 在册人数，0 人置灰、1 人、20 人三个规模）」）。
 *
 * 板上的两个算例是硬要求：4 人 → 36 / 71 / 142，20 人 → 7 / 14 / 28。
 * 取整顺序不能换：标准档先四舍五入，再拿它 ×0.5 / ×2 四舍五入。
 */
describe('企业页三个档位现算', () => {
  it('4 人：36 / 71 / 142（板上算例，周池 2000）', () => {
    // 2000 ÷ 7 ÷ 4 = 71.43 → 标准档 71；省心 = 71×0.5 = 35.5 → 36（HALF_UP）；火力 = 71×2 = 142
    const tiers = computeCreditTiers(2000, 4);
    expect(tiers.map((t) => t.value)).toEqual([36, 71, 142]);
  });

  it('20 人：7 / 14 / 28（板上算例，周池 2000）', () => {
    // 2000 ÷ 7 ÷ 20 = 14.29 → 标准档 14；省心 = 14×0.5 = 7；火力 = 14×2 = 28
    const tiers = computeCreditTiers(2000, 20);
    expect(tiers.map((t) => t.value)).toEqual([7, 14, 28]);
  });

  it('1 人：标准档就是周池除以七', () => {
    const tiers = computeCreditTiers(700, 1);
    expect(tiers.map((t) => t.value)).toEqual([50, 100, 200]);
  });

  it('0 人：不给建议（除零没有意义）', () => {
    expect(computeCreditTiers(1000, 0)).toEqual([]);
  });

  it('周池为 0 或没读到时不给建议', () => {
    expect(computeCreditTiers(0, 4)).toEqual([]);
    expect(computeCreditTiers(null, 4)).toEqual([]);
    expect(computeCreditTiers(undefined, undefined)).toEqual([]);
  });

  it('周池太小算成 0 时也不给建议（0 分/天等于关停，不是省心）', () => {
    // 3 ÷ 7 ÷ 20 = 0.02 → 0
    expect(computeCreditTiers(3, 20)).toEqual([]);
  });

  it('省心档下限是 1 分，不是 0 分', () => {
    // 标准档 = 1 时，0.5 倍四舍五入到 0——给 1，因为「省心」不等于「不许用」
    const tiers = computeCreditTiers(7, 1);
    expect(tiers[0].value).toBe(1);
    expect(tiers[1].value).toBe(1);
    expect(tiers[2].value).toBe(2);
  });

  it('三个档位的键与标签固定', () => {
    const tiers = computeCreditTiers(700, 1);
    expect(tiers.map((t) => t.key)).toEqual(['easy', 'standard', 'turbo']);
    expect(tiers.map((t) => t.label)).toEqual(['省心', '标准', '火力']);
  });
});

describe('档位算式提示', () => {
  it('写出除法过程与取整规则', () => {
    const text = creditTierFormulaText(1000, 4);
    expect(text).toContain('1,000 ÷ 7 天 ÷ 4 人');
    expect(text).toContain('≈ 36');
    expect(text).toContain('四舍五入');
  });

  it('满员一周不超过周池时说「不超过」', () => {
    // 标准 36 × 7 × 4 = 1008 > 1000 → 已超（四舍五入会超一点点）
    expect(creditTierFormulaText(1000, 4)).toContain('已超周池');
    // 700 ÷ 7 ÷ 1 = 100，100×7×1 = 700 = 池 → 不超过
    expect(creditTierFormulaText(700, 1)).toContain('不超过周池');
  });

  it('前置条件不全时返回空串（界面不显示这段）', () => {
    expect(creditTierFormulaText(0, 4)).toBe('');
    expect(creditTierFormulaText(1000, 0)).toBe('');
  });

  it('提示里带「已单独设过的人不会自动改」这句提醒', () => {
    expect(creditTierFormulaText(700, 1)).toContain('已单独设过的人不会自动改');
  });
});
