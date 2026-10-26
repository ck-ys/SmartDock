import request from '@/utils/request';

/**
 * 分析中心 API：流量概览/趋势/下钻、访问明细、漏斗、留存、自定义分析、
 * CSV 导入与重建汇总。
 *
 * 约定同 profile.ts：R 信封由 request 拦截器返回，`r()` 解包业务体；
 * 分页接口的 rows/total 在顶层，`table()` 原样返回。
 */
const BASE = '/analytics';

const r = async <T>(config: Record<string, any>): Promise<T> => {
  const res: any = await request(config);
  return res.data as T;
};

const table = async <T>(config: Record<string, any>): Promise<TableDataInfo<T>> => {
  return (await request(config)) as unknown as TableDataInfo<T>;
};

export interface AnalyticsOverviewNumbers {
  pv: number;
  uv: number;
  visits: number;
  events: number;
  newVisitors: number;
  returningVisitors: number;
  bounces: number;
  durationSeconds: number;
  bounceRate: number | string;
  avgDuration: number | string;
  pagesPerVisit: number | string;
  newRate: number | string;
}

export interface AnalyticsOverview {
  source: string;
  days: number;
  from: string;
  to: string;
  current: AnalyticsOverviewNumbers;
  previous: AnalyticsOverviewNumbers;
  delta: Record<string, number | string>;
}

export interface AnalyticsTrendPoint {
  statDate: string;
  pv: number;
  uv: number;
  visits: number;
  events: number;
  newVisitors: number;
  returningVisitors: number;
  bounces: number;
  durationSeconds: number;
  bounceRate: number | string;
  avgDuration: number | string;
}

export interface AnalyticsBreakdownRow {
  label: string;
  pv: number;
  uv: number;
  visits: number;
  events?: number;
  share: number | string;
}

export interface AnalyticsBreakdown {
  source: string;
  type: string;
  days: number;
  from: string;
  to: string;
  rows: AnalyticsBreakdownRow[];
}

export interface AnalyticsSession {
  sessionId: string;
  tenantId: string;
  source: string;
  sessionKey?: string;
  visitorId: string;
  subjectType?: string;
  subjectId?: string;
  anonymousId?: string;
  isNewVisitor?: string;
  startedAt: string;
  endedAt?: string;
  lastEventAt?: string;
  durationSeconds?: number;
  pageViews?: number;
  eventCount?: number;
  isBounce?: string;
  entryPage?: string;
  exitPage?: string;
  referrer?: string;
  sourceChannel?: string;
  campaign?: string;
  deviceType?: string;
  os?: string;
  browser?: string;
  country?: string;
  province?: string;
  city?: string;
  searchTerm?: string;
}

export interface AnalyticsSessionQuery {
  source: string;
  days?: number;
  fromDate?: string;
  toDate?: string;
  visitorId?: string;
  subjectId?: string;
  channel?: string;
  deviceType?: string;
  browser?: string;
  country?: string;
  bounce?: string;
  keyword?: string;
  pageNum?: number;
  pageSize?: number;
}

export interface AnalyticsTimelineItem {
  eventId: string;
  eventName: string;
  occurredTime: string;
  pageUrl?: string;
  title?: string;
  referrer?: string;
  props?: Record<string, any>;
  context?: Record<string, any>;
}

export interface AnalyticsSessionDetail {
  session: AnalyticsSession;
  timeline: AnalyticsTimelineItem[];
  pages: { pageUrl: string; count: number }[];
  /** 批11: 无 profile:pii:behavior 权限时为 true, 时间线被拦 */
  behaviorBlocked?: boolean;
}

export interface AnalyticsFunnelStepDef {
  eventName: string;
  name?: string;
}

export interface AnalyticsFunnel {
  funnelId: string;
  tenantId: string;
  source: string;
  funnelCode: string;
  funnelName: string;
  /** steps 是 JSONB，接口按字符串返回 */
  steps: string | AnalyticsFunnelStepDef[];
  windowDays: number;
  enabled: string;
  builtin: string;
  remark?: string;
  createTime?: string;
}

export interface AnalyticsFunnelStepResult extends AnalyticsFunnelStepDef {
  count: number;
  rate: number | string;
  stepRate: number | string;
  avgSeconds: number | string;
}

export interface AnalyticsFunnelBreakdownGroup {
  label: string;
  visitors: number;
  steps: { eventName: string; name?: string; count: number; rate: number | string }[];
  overallRate: number | string;
}

export interface AnalyticsFunnelResult {
  funnelId?: string;
  source: string;
  days: number;
  windowDays: number;
  visitors: number;
  steps: AnalyticsFunnelStepResult[];
  overallRate: number | string;
  /** R2-3 拆解维度 channel/device/os/browser, 空=整体口径 */
  breakdown?: string;
  breakdownGroups?: AnalyticsFunnelBreakdownGroup[];
}

export interface AnalyticsFunnelSaveBo {
  funnelId?: string;
  source?: string;
  funnelCode?: string;
  funnelName?: string;
  steps?: AnalyticsFunnelStepDef[];
  windowDays?: number;
  enabled?: string;
  remark?: string;
}

export interface AnalyticsRetentionCell {
  day: number;
  visitors: number;
  rate: number | string;
}

export interface AnalyticsRetentionCohort {
  cohort: string;
  total: number;
  cells: AnalyticsRetentionCell[];
}

export interface AnalyticsRetention {
  source: string;
  days: number;
  window: number;
  /** R2-3 粒度 day/week/month(自然周=ISO 周一, 自然月) */
  granularity?: string;
  /** R2-3 口径: false=恰好第N期, true=该期或之后 */
  cumulative?: boolean;
  cohorts: AnalyticsRetentionCohort[];
}

export interface AnalyticsCustomBo {
  source: string;
  days?: number;
  dimension?: string;
  metric?: string;
  limit?: number;
}

export interface AnalyticsCustomPoint {
  label: string;
  value: number;
}

export interface AnalyticsCustomResult {
  dimension: string;
  metric: string;
  days: number;
  points: AnalyticsCustomPoint[];
}

export interface AnalyticsImportResult {
  received: number;
  written: number;
  skipped: number;
}

export interface AnalyticsRebuildResult {
  source: string;
  days: number;
  sessions: number;
  dailyRows: number;
  from: string;
  to: string;
}

export const getAnalyticsOverview = (source: string, days = 30) =>
  r<AnalyticsOverview>({ url: `${BASE}/overview`, method: 'get', params: { source, days } });

export const getAnalyticsTrend = (source: string, days = 30) =>
  r<AnalyticsTrendPoint[]>({ url: `${BASE}/trend`, method: 'get', params: { source, days } });

export const getAnalyticsBreakdown = (source: string, days = 30, type = 'channel', limit = 20) =>
  r<AnalyticsBreakdown>({ url: `${BASE}/breakdown`, method: 'get', params: { source, days, type, limit } });

export const pageAnalyticsSessions = (query: AnalyticsSessionQuery) =>
  table<AnalyticsSession>({ url: `${BASE}/sessions`, method: 'get', params: query });

export const getAnalyticsSession = (sessionId: string) => r<AnalyticsSessionDetail>({ url: `${BASE}/session/${sessionId}`, method: 'get' });

export const listAnalyticsFunnels = (source: string) => r<AnalyticsFunnel[]>({ url: `${BASE}/funnels`, method: 'get', params: { source } });

export const saveAnalyticsFunnel = (data: AnalyticsFunnelSaveBo) => r<AnalyticsFunnel>({ url: `${BASE}/funnel/save`, method: 'post', data });

export const deleteAnalyticsFunnel = (funnelId: string) => r<boolean>({ url: `${BASE}/funnel/${funnelId}/delete`, method: 'post' });

export const previewAnalyticsFunnel = (data: AnalyticsFunnelSaveBo & { funnelId?: string; days?: number; breakdown?: string }) =>
  r<AnalyticsFunnelResult>({ url: `${BASE}/funnel/preview`, method: 'post', data });

export const getAnalyticsRetention = (source: string, days = 30, window = 7, granularity = 'day', cumulative = false) =>
  r<AnalyticsRetention>({ url: `${BASE}/retention`, method: 'get', params: { source, days, window, granularity, cumulative } });

export const runAnalyticsCustom = (data: AnalyticsCustomBo) => r<AnalyticsCustomResult>({ url: `${BASE}/custom`, method: 'post', data });

// ---------------------------------------------------------------- R2-4 我的视图

export interface AnalyticsCustomReport {
  reportId: string;
  reportName: string;
  source: string;
  dimension: string;
  metric: string;
  days: number;
  limitRows: number;
  createTime?: string;
}

export const listAnalyticsReports = () =>
  r<AnalyticsCustomReport[]>({ url: `${BASE}/report/list`, method: 'get' });

export const saveAnalyticsReport = (data: Partial<AnalyticsCustomReport>) =>
  r<string>({ url: `${BASE}/report/save`, method: 'post', data });

export const removeAnalyticsReport = (reportId: string) =>
  r<void>({ url: `${BASE}/report/${reportId}/remove`, method: 'post' });

export const importAnalyticsCsv = (source: string, csv: string) =>
  r<AnalyticsImportResult>({ url: `${BASE}/import`, method: 'post', data: { source, csv } });

export const rebuildAnalytics = (source: string, days = 30) =>
  r<AnalyticsRebuildResult>({ url: `${BASE}/rebuild`, method: 'post', params: { source, days } });

// ---------------------------------------------------------------- 批8 分析深度

export interface AnalyticsPathNode {
  name: string;
  value: number;
}

export interface AnalyticsPathLink {
  source: string;
  target: string;
  value: number;
}

export interface AnalyticsPaths {
  source: string;
  days: number;
  start: string | null;
  visitors: number;
  nodes: AnalyticsPathNode[];
  links: AnalyticsPathLink[];
  entries: { page: string; visitors: number }[];
}

export interface AnalyticsFunnelLossStep {
  eventName: string;
  count: number;
  lost: number;
  next?: { label: string; count: number }[];
}

export interface AnalyticsFunnelLoss {
  funnelId?: string;
  source: string;
  days: number;
  windowDays: number;
  steps: AnalyticsFunnelLossStep[];
}

export interface AnalyticsAttributionRow {
  channel: string;
  first: number;
  last: number;
  linear: number;
  visitors: number;
}

export interface AnalyticsAttribution {
  source: string;
  days: number;
  conversionEvent: string;
  conversions: number;
  channels: number;
  rows: AnalyticsAttributionRow[];
}

export interface AnalyticsActivity {
  source: string;
  days: number;
  dayList: string[];
  dau: number[];
  wau: number[];
  mau: number[];
  sticky: (number | null)[];
  latest: { dau: number | null; wau: number | null; mau: number | null; sticky: number | null };
  newVsReturning: { statDate?: string; newVisitors: number; returningVisitors: number };
}

export interface AnalyticsRealtimeMinute {
  minute: string;
  events: number;
  pv: number;
  visitors: number;
}

export interface AnalyticsRealtime {
  source: string;
  now: string;
  windowMinutes: number;
  summary: { visitors: number; pv: number; events: number; newVisitors: number };
  minutes: AnalyticsRealtimeMinute[];
}

export interface AnalyticsCrossSourceRow {
  source: string;
  events: number;
  visitors: number;
  lastAt: string;
}

export interface AnalyticsCrossSource {
  days: number;
  sources: AnalyticsCrossSourceRow[];
}

export const getAnalyticsPaths = (source: string, days = 30, start?: string) =>
  r<AnalyticsPaths>({ url: `${BASE}/paths`, method: 'get', params: { source, days, start } });

export const getAnalyticsFunnelLoss = (data: { funnelId?: string; days?: number }) =>
  r<AnalyticsFunnelLoss>({ url: `${BASE}/funnel/loss`, method: 'post', data });

export const getAnalyticsAttribution = (source: string, days = 30, event?: string) =>
  r<AnalyticsAttribution>({ url: `${BASE}/attribution`, method: 'get', params: { source, days, event } });

export const getAnalyticsActivity = (source: string, days = 30) =>
  r<AnalyticsActivity>({ url: `${BASE}/activity`, method: 'get', params: { source, days } });

export const getAnalyticsRealtime = (source: string) =>
  r<AnalyticsRealtime>({ url: `${BASE}/realtime`, method: 'get', params: { source } });

export const getAnalyticsCrossSource = (days = 30) =>
  r<AnalyticsCrossSource>({ url: `${BASE}/cross-source`, method: 'get', params: { days } });
