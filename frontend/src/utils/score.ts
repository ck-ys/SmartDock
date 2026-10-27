/**
 * 画像域分数/百分比展示口径（2026-10-27 微调批 P2 拍板A）：
 * 分数统一一位小数、百分比统一两位小数，各页不再自建副本。
 * 详情页 KPI 与大数字同排时可用 formatPercent(v, 1) 对齐一位口径。
 */
export const formatScore = (value?: number | string | null, digits = 1): string => {
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  return Number.isFinite(n) ? n.toFixed(digits) : String(value);
};

/** 百分比数值（不带 % 号，模板自行拼接），默认两位小数。 */
export const formatPercent = (value?: number | string | null, digits = 2): string => {
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  return Number.isFinite(n) ? n.toFixed(digits) : String(value);
};
