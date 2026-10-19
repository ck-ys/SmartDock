import request from '@/utils/request';

/**
 * 画像引擎 API：主体列表/总览/排行/详情 + 维度治理 + 权重规则治理。
 *
 * 约定：R 信封由 request 拦截器原样返回，`r()` 解包业务体；
 * 分页接口返回 TableDataInfo（rows/total 在顶层），`table()` 直接返回。
 */
const BASE = '/profile';

const r = async <T>(config: Record<string, any>): Promise<T> => {
  const res: any = await request(config);
  return res.data as T;
};

const table = async <T>(config: Record<string, any>): Promise<TableDataInfo<T>> => {
  return (await request(config)) as unknown as TableDataInfo<T>;
};

export interface ProfileSubjectRow {
  id: string;
  tenantId: string;
  source: string;
  subjectType: string;
  subjectId: string;
  displayName?: string;
  firstEventTime?: string;
  lastEventTime?: string;
  eventCount: number;
  properties?: string;
  totalScore?: number | string;
  dimensionDetail?: string | Record<string, ProfileDimensionDetail>;
  ruleVersion?: string;
  calcTime?: string;
}

/** 快照维度明细值：dimension_detail 是 JSONB，接口层按字符串返回 */
export interface ProfileDimensionDetail {
  score?: number | string;
  normalizedScore?: number | string;
  weight?: number;
  weightedScore?: number | string;
  scoreMax?: number | string;
  dimName?: string;
  calcMode?: string;
  sampleCount?: number;
  metric?: number | string;
  skipped?: boolean;
  tags?: Record<string, any>;
}

export interface ProfileSnapshot {
  snapshotId: string;
  source: string;
  subjectType: string;
  subjectId: string;
  totalScore?: number | string;
  dimensionDetail?: string | Record<string, ProfileDimensionDetail>;
  ruleVersion?: string;
  calcTime?: string;
  prevSnapshotId?: string;
}

export interface ProfileOverview {
  subjectCount: number;
  scoredCount: number;
  avgScore?: number | string;
  lowRisk: number;
  mediumRisk: number;
  highRisk: number;
  lastCalcTime?: string;
}

export interface ProfileRankingRow {
  rank: number;
  subjectType: string;
  subjectId: string;
  displayName?: string;
  totalScore?: number | string;
  dimensionScore?: number | string;
  eventCount: number;
  lastEventTime?: string;
  calcTime?: string;
}

export interface ProfileDimension {
  dimensionId: string;
  tenantId: string;
  source: string;
  dimCode: string;
  dimName: string;
  calcMode: string;
  /** JSON 字符串 */
  calcConfig: string;
  scoreMax: number | string;
  enabled: string;
  builtin: string;
  sortOrder: number;
  remark?: string;
}

export interface ProfileDimensionCoverage {
  dimensionId: string;
  dimCode: string;
  dimName: string;
  enabled: string;
  builtin: string;
  coveredSubjects: number;
  subjectCount: number;
  scoredSubjects: number;
  coverageRate: number | string;
  lastCalcTime?: string;
}

export interface ProfileDimensionCapabilities {
  calcModes: string[];
  operators: string[];
  scoreTypes: string[];
  directions: string[];
  missingPolicies: string[];
  windowTypes: string[];
  propKeyPattern: string;
  scoreMaxRange: { min: number; max: number };
  strategyBeans: { source: string; dimCode: string; bean: string }[];
}

export interface ProfileDimensionPreview {
  dimCode: string;
  dimName: string;
  scoreMax: number | string;
  skipped: boolean;
  score?: number | string;
  normalizedScore?: number | string;
  metric?: number | string;
  sampleCount?: number;
  tags?: Record<string, any>;
}

export interface ProfileRule {
  ruleId: string;
  source: string;
  ruleVersion: string;
  ruleName?: string;
  status: string;
  /** JSON 字符串 */
  weights: string | Record<string, number>;
  remark?: string;
  publishedAt?: string;
  publishedBy?: number;
  baseRuleVersion?: string;
  revision: number;
  enabled: string;
}

export interface ProfileRuleTemplate {
  code: string;
  name: string;
  description: string;
  weights: Record<string, number>;
  weightSum: number;
}

export interface ProfileRuleValidation {
  valid: boolean;
  errors: string[];
  warnings: string[];
  ruleId: string;
  ruleVersion: string;
  status: string;
  dimensionCodes: string[];
  weights: Record<string, number>;
  weightSum: number;
  revision: number;
}

export interface ProfileRuleDiff {
  baseRuleId?: string;
  baseVersion?: string;
  draftRuleId: string;
  draftVersion: string;
  changes: Record<string, { before?: number; after?: number; delta?: number }>;
  added: string[];
  removed: string[];
}

export interface ProfileRuleImpact {
  draftRuleId: string;
  publishedVersion?: string;
  draftVersion: string;
  subjectCount: number;
  scoredSubjectCount: number;
  changedDimensions: string[];
  estimatedRecalcSubjects: number;
  requiresRecalc: boolean;
}

export interface ProfileDimensionRow {
  dimCode: string;
  dimName: string;
  scoreMax: number | string;
  sortOrder: number;
  enabled: string;
  score?: number | string;
  normalizedScore?: number | string;
  weight?: number;
  weightedScore?: number | string;
  metric?: number | string;
  sampleCount?: number;
  tags?: Record<string, any>;
  skipped?: boolean;
  missing?: boolean;
  level?: string;
}

export interface ProfileTrendPoint {
  snapshotId: string;
  totalScore?: number | string;
  ruleVersion?: string;
  calcTime?: string;
}

export interface ProfileDetail {
  subject: ProfileSubjectRow | null;
  subjectProperties?: Record<string, any>;
  latest: ProfileSnapshot | null;
  history: ProfileSnapshot[];
  dimensions: ProfileDimensionRow[];
  rule: ProfileRule | null;
  rank?: number | null;
  risk?: { level: string; label: string; color: string };
  trend: ProfileTrendPoint[];
  dataQuality?: {
    dimensionCount: number;
    coveredCount: number;
    missingCount: number;
    skippedCount: number;
    coverageRate: number | string;
  };
}

// ---------------------------------------------------------------- 查询面

export const pageSubjects = (params?: Partial<ProfileSubjectQuery> & Record<string, unknown>) =>
  table<ProfileSubjectRow>({ url: `${BASE}/subject/page`, method: 'get', params });

export const getOverview = (source: string): Promise<ProfileOverview> =>
  r<ProfileOverview>({ url: `${BASE}/overview`, method: 'get', params: { source } });

export const getRanking = (params: { source: string; subjectType?: string; dimension?: string; limit?: number }): Promise<ProfileRankingRow[]> =>
  r<ProfileRankingRow[]>({ url: `${BASE}/ranking`, method: 'get', params });

export const getScore = (source: string, subjectId: string, subjectType = 'user', tail = 8): Promise<ProfileDetail> =>
  r<ProfileDetail>({ url: `${BASE}/score/${source}/${subjectId}`, method: 'get', params: { subjectType, tail } });

// ---------------------------------------------------------------- 维度治理

export const listDimensions = (source: string): Promise<ProfileDimension[]> =>
  r<ProfileDimension[]>({ url: `${BASE}/dimension/list`, method: 'get', params: { source } });

export const getDimensionCapabilities = (): Promise<ProfileDimensionCapabilities> =>
  r<ProfileDimensionCapabilities>({ url: `${BASE}/dimension/capabilities`, method: 'get' });

export const getDimensionCoverage = (source: string): Promise<ProfileDimensionCoverage[]> =>
  r<ProfileDimensionCoverage[]>({ url: `${BASE}/dimension/coverage`, method: 'get', params: { source } });

export const getDimension = (dimensionId: string): Promise<ProfileDimension> =>
  r<ProfileDimension>({ url: `${BASE}/dimension/${dimensionId}`, method: 'get' });

export const createDimension = (data: Record<string, any>): Promise<ProfileDimension> =>
  r<ProfileDimension>({ url: `${BASE}/dimension`, method: 'post', data });

export const updateDimension = (data: Record<string, any>): Promise<ProfileDimension> =>
  r<ProfileDimension>({ url: `${BASE}/dimension/edit`, method: 'post', data });

export const disableDimension = (dimensionId: string): Promise<boolean> =>
  r<boolean>({ url: `${BASE}/dimension/${dimensionId}/disable`, method: 'post' });

export const previewDimension = (data: Record<string, any>): Promise<ProfileDimensionPreview> =>
  r<ProfileDimensionPreview>({ url: `${BASE}/dimension/preview`, method: 'post', data });

// ---------------------------------------------------------------- 权重规则

export const getCurrentRule = (source: string): Promise<ProfileRule | null> =>
  r<ProfileRule | null>({ url: `${BASE}/rule/current`, method: 'get', params: { source } });

export const getRuleHistory = (source: string): Promise<ProfileRule[]> =>
  r<ProfileRule[]>({ url: `${BASE}/rule/history`, method: 'get', params: { source } });

export const getRuleTemplates = (source: string): Promise<ProfileRuleTemplate[]> =>
  r<ProfileRuleTemplate[]>({ url: `${BASE}/rule/templates`, method: 'get', params: { source } });

export const getRule = (ruleId: string): Promise<ProfileRule> => r<ProfileRule>({ url: `${BASE}/rule/${ruleId}`, method: 'get' });

export const createRuleDraft = (data: { source: string; sourceRuleId?: string; ruleName?: string; remark?: string }): Promise<ProfileRule> =>
  r<ProfileRule>({ url: `${BASE}/rule/draft`, method: 'post', data });

export const updateRuleDraft = (data: Record<string, any>): Promise<ProfileRule> =>
  r<ProfileRule>({ url: `${BASE}/rule/draft/edit`, method: 'post', data });

export const validateRule = (ruleId: string): Promise<ProfileRuleValidation> =>
  r<ProfileRuleValidation>({ url: `${BASE}/rule/${ruleId}/validate`, method: 'get' });

export const diffRule = (ruleId: string): Promise<ProfileRuleDiff> => r<ProfileRuleDiff>({ url: `${BASE}/rule/${ruleId}/diff`, method: 'get' });

export const impactRule = (ruleId: string): Promise<ProfileRuleImpact> =>
  r<ProfileRuleImpact>({ url: `${BASE}/rule/${ruleId}/impact`, method: 'get' });

export const publishRule = (ruleId: string, data: { version: string; note?: string; revision?: number }): Promise<ProfileRule> =>
  r<ProfileRule>({ url: `${BASE}/rule/${ruleId}/publish`, method: 'post', data });

export const archiveRule = (ruleId: string): Promise<ProfileRule> => r<ProfileRule>({ url: `${BASE}/rule/${ruleId}/archive`, method: 'post' });

export const deleteRuleDraft = (ruleId: string): Promise<boolean> => r<boolean>({ url: `${BASE}/rule/${ruleId}/delete`, method: 'post' });

// ---------------------------------------------------------------- 内部类型

export interface ProfileSubjectQuery {
  source?: string;
  subjectType?: string;
  keyword?: string;
  minScore?: number;
  maxScore?: number;
  sortBy?: string;
  sortOrder?: string;
  pageNum?: number;
  pageSize?: number;
}
