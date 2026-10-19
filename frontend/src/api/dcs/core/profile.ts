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

// ---------------------------------------------------------------- 评分模型 / RFM

export interface ProfileScoreModel {
  modelId: string;
  tenantId: string;
  source: string;
  modelCode: string;
  modelName: string;
  modelType: string;
  modelVersion: string;
  /** JSON 字符串(ProfileModelDefinition) */
  config: string;
  scoreMax: number | string;
  enabled: string;
  builtin: string;
  remark?: string;
  revision: number;
}

export interface ProfileModelRun {
  runId: string;
  source: string;
  modelId: string;
  modelCode: string;
  modelVersion: string;
  triggerType: string;
  status: string;
  totalSubjects: number;
  doneSubjects: number;
  errorMsg?: string;
  startedAt?: string;
  finishedAt?: string;
}

export interface ProfileModelDimensionEntry {
  code: string;
  name: string;
  value?: number | string | null;
  score?: number | string;
  maxScore?: number | string;
  normalizedScore?: number | string;
  weight?: number;
  weightedScore?: number | string;
  method?: string;
  edges?: number[];
  rank?: number;
}

export interface ProfileModelSubjectRow {
  subjectType: string;
  subjectId: string;
  displayName?: string;
  eventCount: number;
  lastEventTime?: string;
  modelScore?: number | string;
  normalizedScore?: number | string;
  level?: string;
  /** JSON 字符串: modelScore/normalizedScore/level/dimensions */
  detail?: string;
  modelVersion?: string;
  calcTime?: string;
}

export interface ProfileModelLevelStat {
  level: string;
  count: number;
  rate: number | string;
}

export interface ProfileModelDimensionStat {
  code: string;
  name?: string;
  count: number;
  avgScore: number | string;
  avgValue: number | string;
  distribution: { score: string; count: number }[];
}

export interface ProfileModelOverview {
  modelId: string;
  modelCode: string;
  modelName: string;
  modelType: string;
  modelVersion: string;
  enabled: string;
  scoredCount: number;
  avgModelScore?: number | string;
  avgNormalizedScore?: number | string;
  lastCalcTime?: string;
  levels: ProfileModelLevelStat[];
  dimensions: ProfileModelDimensionStat[];
  topSubjects: ProfileModelSubjectRow[];
}

export interface ProfileModelSubjectDetail {
  modelId: string;
  modelCode: string;
  modelVersion: string;
  subject: ProfileSubjectRow | null;
  subjectProperties: Record<string, any>;
  latest: ProfileModelSnapshot | null;
  history: ProfileModelSnapshot[];
}

export interface ProfileModelSnapshot {
  modelSnapshotId: string;
  modelCode: string;
  modelVersion: string;
  subjectType: string;
  subjectId: string;
  modelScore?: number | string;
  normalizedScore?: number | string;
  level?: string;
  detail?: string;
  calcTime?: string;
  prevSnapshotId?: string;
}

export interface ProfileModelTrendPoint {
  day: string;
  subjects: number;
  avgScore?: number | string;
}

export interface ProfileModelPreset {
  code: string;
  name: string;
  description: string;
  config: Record<string, any>;
}

export const listModels = (source: string): Promise<ProfileScoreModel[]> =>
  r<ProfileScoreModel[]>({ url: `${BASE}/model/list`, method: 'get', params: { source } });

export const getModelPreset = (): Promise<ProfileModelPreset> => r<ProfileModelPreset>({ url: `${BASE}/model/preset`, method: 'get' });

export const getModel = (modelId: string): Promise<ProfileScoreModel> => r<ProfileScoreModel>({ url: `${BASE}/model/${modelId}`, method: 'get' });

export const createModel = (data: Record<string, any>): Promise<ProfileScoreModel> =>
  r<ProfileScoreModel>({ url: `${BASE}/model`, method: 'post', data });

export const updateModel = (data: Record<string, any>): Promise<ProfileScoreModel> =>
  r<ProfileScoreModel>({ url: `${BASE}/model/edit`, method: 'post', data });

export const deleteModel = (modelId: string): Promise<boolean> => r<boolean>({ url: `${BASE}/model/${modelId}/delete`, method: 'post' });

export const runModel = (modelId: string): Promise<ProfileModelRun> => r<ProfileModelRun>({ url: `${BASE}/model/${modelId}/run`, method: 'post' });

export const getModelOverview = (modelId: string): Promise<ProfileModelOverview> =>
  r<ProfileModelOverview>({ url: `${BASE}/model/${modelId}/overview`, method: 'get' });

export const pageModelSubjects = (
  modelId: string,
  params: { pageNum?: number; pageSize?: number; level?: string; keyword?: string }
): Promise<TableDataInfo<ProfileModelSubjectRow>> =>
  table<ProfileModelSubjectRow>({ url: `${BASE}/model/${modelId}/subjects`, method: 'get', params });

export const getModelSubject = (modelId: string, subjectId: string, subjectType = 'user'): Promise<ProfileModelSubjectDetail> =>
  r<ProfileModelSubjectDetail>({
    url: `${BASE}/model/${modelId}/subject/${subjectId}`,
    method: 'get',
    params: { subjectType }
  });

export const getModelTrend = (modelId: string, days = 30): Promise<ProfileModelTrendPoint[]> =>
  r<ProfileModelTrendPoint[]>({ url: `${BASE}/model/${modelId}/trend`, method: 'get', params: { days } });

// ---------------------------------------------------------------- 标签分群

export interface ProfileSegment {
  segmentId: string;
  tenantId: string;
  source: string;
  segmentCode: string;
  segmentName: string;
  segmentType: string;
  status: string;
  /** JSON 字符串(ProfileSegmentRule) */
  ruleConfig: string;
  description?: string;
  memberCount: number;
  lastRunId?: string;
  lastEvalTime?: string;
  builtin: string;
  revision: number;
}

export interface ProfileSegmentRun {
  runId: string;
  source: string;
  segmentId: string;
  triggerType: string;
  status: string;
  totalCandidates: number;
  memberCount: number;
  errorMsg?: string;
  startedAt?: string;
  finishedAt?: string;
}

export interface ProfileSegmentMemberRow {
  memberId: string;
  runId: string;
  subjectType: string;
  subjectId: string;
  displayName?: string;
  reason?: string;
  eventCount: number;
  lastEventTime?: string;
  totalScore?: number | string;
  createTime?: string;
}

export interface ProfileSegmentPreview {
  totalCandidates: number;
  matched: number;
  rate: number | string;
  summary: string;
  sample: {
    subjectType: string;
    subjectId: string;
    displayName?: string;
    totalScore?: number | string;
    eventCount: number;
    lastEventTime?: string;
  }[];
}

export const listSegments = (source: string): Promise<ProfileSegment[]> =>
  r<ProfileSegment[]>({ url: `${BASE}/segment/list`, method: 'get', params: { source } });

export const getSegment = (segmentId: string): Promise<ProfileSegment> => r<ProfileSegment>({ url: `${BASE}/segment/${segmentId}`, method: 'get' });

export const createSegment = (data: Record<string, any>): Promise<ProfileSegment> =>
  r<ProfileSegment>({ url: `${BASE}/segment`, method: 'post', data });

export const updateSegment = (data: Record<string, any>): Promise<ProfileSegment> =>
  r<ProfileSegment>({ url: `${BASE}/segment/edit`, method: 'post', data });

export const deleteSegment = (segmentId: string): Promise<boolean> => r<boolean>({ url: `${BASE}/segment/${segmentId}/delete`, method: 'post' });

export const previewSegment = (data: Record<string, any>): Promise<ProfileSegmentPreview> =>
  r<ProfileSegmentPreview>({ url: `${BASE}/segment/preview`, method: 'post', data });

export const evaluateSegment = (segmentId: string): Promise<ProfileSegmentRun> =>
  r<ProfileSegmentRun>({ url: `${BASE}/segment/${segmentId}/evaluate`, method: 'post' });

export const importSegmentMembers = (segmentId: string, data: Record<string, any>): Promise<Record<string, any>> =>
  r<Record<string, any>>({ url: `${BASE}/segment/${segmentId}/import`, method: 'post', data });

export const pageSegmentMembers = (
  segmentId: string,
  params: { pageNum?: number; pageSize?: number; keyword?: string }
): Promise<TableDataInfo<ProfileSegmentMemberRow>> =>
  table<ProfileSegmentMemberRow>({ url: `${BASE}/segment/${segmentId}/members`, method: 'get', params });

export const listSegmentRuns = (segmentId: string): Promise<ProfileSegmentRun[]> =>
  r<ProfileSegmentRun[]>({ url: `${BASE}/segment/${segmentId}/runs`, method: 'get' });

// ---------------------------------------------------------------- 低分复核 / 引擎配置

export interface ProfileReview {
  reviewId: string;
  tenantId: string;
  source: string;
  subjectType: string;
  subjectId: string;
  displayName?: string;
  snapshotId?: string;
  totalScore?: number | string;
  riskLevel?: string;
  triggerReason?: string;
  status: string;
  conclusion?: string;
  reviewNote?: string;
  reviewedBy?: number;
  reviewedAt?: string;
  createTime?: string;
}

export interface ProfileEngineConfig {
  configId: string;
  tenantId: string;
  source: string;
  autoRecalc: string;
  reviewEnabled: string;
  reviewThreshold: number | string;
  debounceMs: number;
  remark?: string;
}

export const pageReviews = (
  params: {
    source?: string;
    status?: string;
    riskLevel?: string;
    keyword?: string;
    pageNum?: number;
    pageSize?: number;
  } = {}
): Promise<TableDataInfo<ProfileReview>> => table<ProfileReview>({ url: `${BASE}/review/page`, method: 'get', params });

export const getReview = (reviewId: string): Promise<ProfileReview> => r<ProfileReview>({ url: `${BASE}/review/${reviewId}`, method: 'get' });

export const confirmReview = (reviewId: string, data: { note?: string; riskLevel?: string }): Promise<ProfileReview> =>
  r<ProfileReview>({ url: `${BASE}/review/${reviewId}/confirm`, method: 'post', data });

export const dismissReview = (reviewId: string, data: { note?: string; riskLevel?: string }): Promise<ProfileReview> =>
  r<ProfileReview>({ url: `${BASE}/review/${reviewId}/dismiss`, method: 'post', data });

export const scanReviews = (source: string): Promise<{ scanned: number; created: number; pending: number }> =>
  r<{ scanned: number; created: number; pending: number }>({ url: `${BASE}/review/scan`, method: 'post', params: { source } });

export const getEngineConfig = (source: string): Promise<ProfileEngineConfig> =>
  r<ProfileEngineConfig>({ url: `${BASE}/review/config`, method: 'get', params: { source } });

export const updateEngineConfig = (data: Record<string, any>): Promise<ProfileEngineConfig> =>
  r<ProfileEngineConfig>({ url: `${BASE}/review/config/edit`, method: 'post', data });

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
