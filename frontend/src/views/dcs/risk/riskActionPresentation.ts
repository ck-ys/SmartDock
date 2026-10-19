import { parseDcsServerTime } from './riskDashboardPresentation';

export const ACTION_LABELS: Record<string, string> = {
  ENHANCED_VERIFICATION: '增强验证',
  TEMP_FREEZE: '临时冻结'
};

export const ACTION_DESCRIPTIONS: Record<string, string> = {
  ENHANCED_VERIFICATION: '敏感操作继续前需要完成验证码验证，30 分钟后自动到期。',
  TEMP_FREEZE: '暂时阻止聊天发送等高风险操作，60 分钟后自动到期。'
};

export const ACTION_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '生效中',
  EXPIRED: '已到期',
  RELEASED: '已解除'
};

export const REVIEW_STATUS_LABELS: Record<string, string> = {
  PENDING: '待复核',
  CONFIRMED: '确认风险',
  DISMISSED: '排除风险'
};

export const formatRemaining = (expireTime?: string, now = Date.now()): string => {
  const expires = parseDcsServerTime(expireTime);
  if (!expires) return '-';
  const seconds = Math.max(0, Math.ceil((expires.getTime() - now) / 1000));
  if (seconds === 0) return '等待状态刷新';
  const minutes = Math.floor(seconds / 60);
  const remainSeconds = seconds % 60;
  return minutes > 0 ? `${minutes}分${remainSeconds}秒` : `${remainSeconds}秒`;
};
