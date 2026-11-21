import { parseTime } from '@/utils/ruoyi';

type RiskStat = {
  cnt?: number | string | null;
  totalScore?: number | string | null;
};

const DCS_BUSINESS_TIME_OFFSET = '+08:00';
const UNZONED_DATE_TIME = /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d+)?$/;

const toNumber = (value: number | string | null | undefined): number => {
  const parsed = Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
};

export const sumEventCount = (stats: RiskStat[] = []): number => stats.reduce((sum, item) => sum + toNumber(item.cnt), 0);

export const sumRiskScore = (stats: RiskStat[] = []): number => stats.reduce((sum, item) => sum + toNumber(item.totalScore), 0);

export const parseDcsServerTime = (value?: string): Date | null => {
  if (!value) return null;
  const normalized = UNZONED_DATE_TIME.test(value) ? `${value.replace(' ', 'T')}${DCS_BUSINESS_TIME_OFFSET}` : value;
  const parsed = new Date(normalized);
  return Number.isNaN(parsed.getTime()) ? null : parsed;
};

export const formatRiskRelativeTime = (value?: string, now = Date.now()): string => {
  const parsed = parseDcsServerTime(value);
  if (!parsed) return '';
  const diffSeconds = Math.max(0, Math.floor((now - parsed.getTime()) / 1000));
  if (diffSeconds < 10) return '刚刚';
  if (diffSeconds < 60) return `${diffSeconds}秒前`;
  if (diffSeconds < 3600) return `${Math.floor(diffSeconds / 60)}分钟前`;
  if (diffSeconds < 86400) return `${Math.floor(diffSeconds / 3600)}小时前`;
  return parseTime(parsed, '{y}-{m}-{d}');
};
