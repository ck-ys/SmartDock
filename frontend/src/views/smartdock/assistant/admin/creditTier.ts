/**
 * 额度三级管控批（2026-11-26 / Q14=1 + Q21=1）：企业页「三个档位按钮」的数字现算。
 *
 * <p><b>为什么抽成独立文件</b>：这段算术原先埋在 admin/index.vue 里，改错一位数
 * 界面上只是「建议值偏一点」，谁都看不出来；抽出来才能按板上要求用 0 人 / 1 人 / 20 人
 * 三个规模钉死（4 人 → 36 / 71 / 142，20 人 → 7 / 14 / 28）。</p>
 *
 * <p>板上钉死的两条口径：</p>
 * <ol>
 *   <li>在册人数取<b>全量 count</b>，不读随 keyword / 状态筛选变的分页 total
 *       ——否则管理员一搜索，建议值就跳；</li>
 *   <li>取整：<b>标准档先四舍五入</b>，省心 / 火力 = 标准档 × 0.5 / ×2 <b>再</b>四舍五入。
 *       顺序不能换：先算 0.5 倍再取整，和先取整再算 0.5 倍会差 1。</li>
 * </ol>
 */

export type CreditTierKey = 'easy' | 'standard' | 'turbo';

export interface CreditTier {
  key: CreditTierKey;
  label: string;
  value: number;
}

/**
 * 三个档位的建议值。周池或人数缺一（≤0）就返回空数组——按钮整组不出现，
 * 除零没有意义，也没法给建议。
 */
export function computeCreditTiers(weeklyPool: number | null | undefined, headcount: number | null | undefined): CreditTier[] {
  const pool = Number(weeklyPool ?? 0);
  const people = Number(headcount ?? 0);
  if (!Number.isFinite(pool) || !Number.isFinite(people)) return [];
  if (pool <= 0 || people <= 0) return [];
  const standard = Math.round(pool / 7 / people);
  // 周池太小导致标准档算成 0 时也不给建议（0 积分/天在界面上等于「关停」，不是「省心」）
  if (standard <= 0) return [];
  return [
    // 下限 1：0.5 倍四舍五入到 0 时给 1（「省心」不等于「不许用」）
    { key: 'easy', label: '省心', value: Math.max(1, Math.round(standard * 0.5)) },
    { key: 'standard', label: '标准', value: standard },
    { key: 'turbo', label: '火力', value: Math.round(standard * 2) }
  ];
}

/**
 * 算式提示：把「这个数怎么来的」直接写出来，管理员不用自己算，也不用怀疑是编的。
 * 返回空串 = 前置条件不全（界面不显示这段）。
 */
export function creditTierFormulaText(
  weeklyPool: number | null | undefined,
  headcount: number | null | undefined,
  fmt: (n: number) => string = (n) => n.toLocaleString('zh-CN')
): string {
  const pool = Number(weeklyPool ?? 0);
  const people = Number(headcount ?? 0);
  if (!Number.isFinite(pool) || !Number.isFinite(people)) return '';
  if (pool <= 0 || people <= 0) return '';
  const standard = Math.round(pool / 7 / people);
  if (standard <= 0) return '';
  const weekly = standard * 7 * people;
  const over = weekly > pool;
  return (
    `${fmt(pool)} ÷ 7 天 ÷ ${people} 人 ≈ ${fmt(standard)}（四舍五入）；三档 = 标准档 × 0.5 / 1 / 2 再四舍五入。` +
    `标准档按满员算一周 ${fmt(weekly)}，${over ? '已超周池' : '不超过周池'} ${fmt(pool)}。` +
    `已单独设过的人不会自动改，要改再点一次。`
  );
}
