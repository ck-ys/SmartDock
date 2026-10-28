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

/** 画像主视图行(单源重构 M1·题0-甲): user 视图=sys_user 全员(没事件也列出), 其余身份类型=主体登记跨源聚合 */
export interface ProfileMainViewRow {
  subjectType: string;
  subjectId: string;
  /** 登录名(user 视图) */
  userName?: string | null;
  /** 账号状态(user 视图: '0'正常/'1'停用) */
  userStatus?: string | null;
  /** 部门名(user 视图) */
  deptName?: string | null;
  displayName?: string | null;
  eventCount: number | string | null;
  firstEventTime?: string | null;
  lastEventTime?: string | null;
  /** 无列级分数权限时服务端置空 */
  totalScore?: number | string | null;
  calcTime?: string | null;
  /** 最新快照是「未配置打分口径」占位(50 分只是占位不是评价) */
  noDimensions?: boolean | null;
  /** 有待处理的低分复核(「待联系」动作锚点) */
  pendingReview?: boolean | null;
}

/** 画像页主列表(与数据源无关): subjectType 缺省 user */
export const pageSubjectMainView = (
  params?: { subjectType?: string; keyword?: string; pageNum?: number; pageSize?: number } & Record<string, unknown>
): Promise<TableDataInfo<ProfileMainViewRow>> =>
  table<ProfileMainViewRow>({ url: `${BASE}/subject/main-view`, method: 'get', params });

export const getOverview = (source: string, subjectType?: string): Promise<ProfileOverview> =>
  r<ProfileOverview>({ url: `${BASE}/overview`, method: 'get', params: { source, subjectType } });

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

// ---------------------------------------------------------------- 全量重算(经典评分链)

export interface ProfileRecalcBatch {
  batchId: string;
  source: string;
  /** RUNNING / DONE / FAILED */
  status: string;
  totalSubjects: number;
  doneSubjects: number;
  errorMsg?: string;
}

/**
 * 触发经典评分链全量重算: 发布新权重后让历史主体按新权重出新快照,
 * 生效于用户画像列表/详情页(RFM 模型页是另一条模型快照链, 不受影响)。
 */
export const triggerRecalc = (source: string): Promise<ProfileRecalcBatch> =>
  r<ProfileRecalcBatch>({ url: `${BASE}/recalc/${source}`, method: 'post' });

/** 失败批次从游标断点续跑 */
export const resumeRecalcBatch = (batchId: string): Promise<ProfileRecalcBatch> =>
  r<ProfileRecalcBatch>({ url: `${BASE}/recalc/${batchId}/resume`, method: 'post' });

export const getRecalcBatch = (batchId: string): Promise<ProfileRecalcBatch> =>
  r<ProfileRecalcBatch>({ url: `${BASE}/recalc/${batchId}`, method: 'get' });

// ---------------------------------------------------------------- R2-2 引擎管理页: 灌数 / 协议 / Schema (12 端点收口)

/** POST /profile/event/publish 批量灌数回执 */
export const publishProfileEvents = (events: unknown[]): Promise<Record<string, unknown>> =>
  r<Record<string, unknown>>({ url: `${BASE}/event/publish`, method: 'post', data: events });

export interface ProfileProtocol {
  protocolId?: string;
  source?: string;
  version?: string;
  status?: string;
  manifest?: string | Record<string, unknown>;
  publishedBy?: number | string;
  publishedAt?: string;
  [k: string]: unknown;
}

export const getProtocolCurrent = (source: string): Promise<ProfileProtocol> =>
  r<ProfileProtocol>({ url: `${BASE}/protocol/${source}`, method: 'get' });

export const getProtocolExport = (source: string): Promise<Record<string, unknown>> =>
  r<Record<string, unknown>>({ url: `${BASE}/protocol/${source}/export`, method: 'get' });

export const validateProtocol = (manifest: Record<string, unknown>): Promise<{ valid: boolean; errors: string[] }> =>
  r<{ valid: boolean; errors: string[] }>({ url: `${BASE}/protocol/any/validate`, method: 'post', data: manifest });

export const publishProtocol = (source: string, manifest: Record<string, unknown>, version?: string): Promise<ProfileProtocol> =>
  r<ProfileProtocol>({ url: `${BASE}/protocol/${source}/publish`, method: 'post', params: { version }, data: manifest });

export interface ProfileSchemaRegistry {
  schemaId?: string;
  source?: string;
  eventName?: string;
  schemaVersion?: string | number;
  propsSchema?: string | Record<string, unknown>;
  contextSchema?: string | Record<string, unknown>;
  traitsSchema?: string | Record<string, unknown>;
  status?: string;
  updatedBy?: number | string;
  updateTime?: string;
  [k: string]: unknown;
}

export const listEventSchemas = (source: string): Promise<ProfileSchemaRegistry[]> =>
  r<ProfileSchemaRegistry[]>({ url: `${BASE}/schema/${source}`, method: 'get' });

export const registerEventSchema = (source: string, bo: Record<string, unknown>): Promise<ProfileSchemaRegistry> =>
  r<ProfileSchemaRegistry>({ url: `${BASE}/schema/${source}`, method: 'post', data: bo });

export const validateEventSchema = (source: string, bo: Record<string, unknown>): Promise<{ valid: boolean; errors: string[] }> =>
  r<{ valid: boolean; errors: string[] }>({ url: `${BASE}/schema/${source}/validate`, method: 'post', data: bo });

// ---------------------------------------------------------------- 评分模型 / RFM

export interface ProfileScoreModel {
  modelId: string;
  tenantId: string;
  source: string;
  modelCode: string;
  modelName: string;
  modelType: string;
  modelVersion: string;
  /** DRAFT 草稿 / PUBLISHED 已发布 / ARCHIVED 已归档 */
  status?: string;
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

/** 配置版本存档（发布纪律：每次发布落一份，回滚从存档写回） */
export interface ProfileModelConfigSnapshot {
  configSnapshotId: string;
  tenantId: string;
  source: string;
  modelId: string;
  modelCode: string;
  modelVersion: string;
  /** JSON 字符串(ProfileModelDefinition) */
  config: string;
  scoreMax: number | string;
  publishedBy?: number;
  createTime?: string;
}

/** 版本对比/试算共用结构：升降分布 + 等级迁移 + Top 变化主体 */
export interface ProfileModelCompareResult {
  deltaStat: { up: number; down: number; flat: number; onlyInBase: number; onlyInCand: number };
  migration: { path: string; count: number }[];
  topMovers: {
    subjectType: string;
    subjectId: string;
    displayName?: string;
    baseScore?: number | string | null;
    candScore?: number | string | null;
    delta?: number | string;
    baseLevel?: string;
    candLevel?: string;
  }[];
  compared: number;
  /** 版本对比时为 runA/runB 摘要；试算时为 modelId/modelStatus/currentVersion */
  runA?: Record<string, any>;
  runB?: Record<string, any>;
  modelId?: string;
  modelStatus?: string;
  currentVersion?: string;
  psi: { psi: number | string; verdict: 'STABLE' | 'MODERATE' | 'SHIFTED' };
  distribution: {
    level: string;
    baseCount: number;
    candCount: number;
    delta: number;
    baseRate: number | string;
    candRate: number | string;
    smallSample: boolean;
  }[];
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

// 发布纪律（批9）：草稿试算 / 发布 / 回滚 / 配置历史 / 版本对比
export const listModelRuns = (modelId: string): Promise<ProfileModelRun[]> =>
  r<ProfileModelRun[]>({ url: `${BASE}/model/${modelId}/runs`, method: 'get' });

export const compareModelRuns = (modelId: string, runA: string, runB: string): Promise<ProfileModelCompareResult> =>
  r<ProfileModelCompareResult>({ url: `${BASE}/model/${modelId}/compare`, method: 'get', params: { runA, runB } });

export const dryRunModel = (modelId: string): Promise<ProfileModelCompareResult> =>
  r<ProfileModelCompareResult>({ url: `${BASE}/model/${modelId}/dry-run`, method: 'post' });

export const publishModel = (modelId: string, data: { version?: string; note?: string }): Promise<Record<string, any>> =>
  r<Record<string, any>>({ url: `${BASE}/model/${modelId}/publish`, method: 'post', data });

export const rollbackModel = (modelId: string, version: string): Promise<Record<string, any>> =>
  r<Record<string, any>>({ url: `${BASE}/model/${modelId}/rollback`, method: 'post', params: { version } });

export const listModelConfigSnapshots = (modelId: string): Promise<ProfileModelConfigSnapshot[]> =>
  r<ProfileModelConfigSnapshot[]>({ url: `${BASE}/model/${modelId}/config-snapshots`, method: 'get' });

// ---------------------------------------------------------------- 标签分群

export interface ProfileSegment {
  segmentId: string;
  tenantId: string;
  source: string;
  segmentCode: string;
  segmentName: string;
  segmentType: string;
  status: string;
  /** 动态分群刷新方式: OFF 手动 / DAILY 每天 / HOURLY 每小时 */
  refreshMode?: string;
  /** JSON 字符串(ProfileSegmentRule) */
  ruleConfig: string;
  description?: string;
  memberCount: number;
  lastRunId?: string;
  lastEvalTime?: string;
  /** 最近一次 run 状态(DONE/FAILED/SKIPPED; 批4A 超限跳过信号) */
  lastRunStatus?: string;
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

// ---------------------------------------------------------------- 触达 / 导出（批9）

/** 触达批次：发送时冻结收件人快照，逐人明细可追溯 */
export interface ProfileReachBatch {
  reachId: string;
  tenantId: string;
  source: string;
  segmentId: string;
  segmentName?: string;
  /** inapp 站内信 / export 导出 */
  channel: string;
  title?: string;
  content?: string;
  masked?: string;
  totalCount?: number;
  successCount?: number;
  failCount?: number;
  retryCount?: number;
  status?: string;
  errorMsg?: string;
  createTime?: string;
}

export interface ProfileReachItem {
  itemId: string;
  reachId: string;
  subjectType: string;
  subjectId: string;
  userId?: number | string;
  userName?: string;
  dedupKey?: string;
  /** PENDING / SUCCESS / FAILED */
  status: string;
  errorMsg?: string;
  sentTime?: string;
  createTime?: string;
}

export interface ProfileReachPreview {
  segmentId: string;
  segmentName: string;
  memberCount: number;
  reachableCount: number;
  skippedNonUser: number;
  lastRunId?: string;
  channels: { channel: string; name: string; describe: string }[];
}

export const reachPreview = (segmentId: string): Promise<ProfileReachPreview> =>
  r<ProfileReachPreview>({ url: `${BASE}/segment/${segmentId}/reach/preview`, method: 'get' });

export const reachSend = (segmentId: string, data: { title: string; content: string }): Promise<ProfileReachBatch> =>
  r<ProfileReachBatch>({ url: `${BASE}/segment/${segmentId}/reach/send`, method: 'post', data });

export const reachRetry = (reachId: string): Promise<ProfileReachBatch> =>
  r<ProfileReachBatch>({ url: `${BASE}/segment/reach/${reachId}/retry`, method: 'post' });

export const reachRecords = (
  segmentId: string,
  params: { channel?: string; pageNum?: number; pageSize?: number } = {}
): Promise<TableDataInfo<ProfileReachBatch>> =>
  table<ProfileReachBatch>({ url: `${BASE}/segment/${segmentId}/reach/records`, method: 'get', params });

export const reachItems = (reachId: string, limit = 200): Promise<ProfileReachItem[]> =>
  r<ProfileReachItem[]>({ url: `${BASE}/segment/reach/${reachId}/items`, method: 'get', params: { limit } });

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
  /** 超时天数（现算：入队时间 + 源 SLA；>0 即超时） */
  overdueDays?: number;
  slaDays?: number;
}

export interface ProfileEngineConfig {
  configId: string;
  tenantId: string;
  source: string;
  autoRecalc: string;
  reviewEnabled: string;
  reviewThreshold: number | string;
  debounceMs: number;
  /** 复核 SLA（天）：超时未处理每天提醒管理员，1-365 */
  reviewSlaDays?: number;
  /** 单源重构 M1 C 片: 自动记录本租户用户行为(登录+核心操作)总开关, 1=开(默认)/0=停 */
  selfMonitor?: string;
  /** 批10 Q4: 事件保留天数（1-36500；空=继承全局默认 730） */
  eventRetentionDays?: number | null;
  /** 批10 Q4: 过期去向 1=先归档再删（默认）/0=直接删 */
  eventArchive?: string;
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
  /** 风险档筛选: HIGH / MEDIUM / LOW（阈值由 /profile/risk-bands 单源下发） */
  risk?: string;
  sortBy?: string;
  sortOrder?: string;
  pageNum?: number;
  pageSize?: number;
}

/** 风险分档阈值（后端单一来源，前端不再硬编码 75/60） */
export interface ProfileRiskBands {
  mediumMin: number;
  highBelow: number;
  lowMin: number;
}

/** 单主体画像依据摘要（"查看依据"抽屉） */
export interface ProfileSubjectSummary {
  found: boolean;
  subjectType?: string;
  subjectId?: string;
  displayName?: string;
  source?: string;
  eventCount?: number;
  lastEventTime?: string;
  scored?: boolean;
  /** 权限不含 profile:pii:score 时后端只给此键不给分数(批11 列级权限) */
  scoreHidden?: boolean;
  /** 最新快照是「未配置打分口径」占位(M1 D 片: scored=false 时携带, 前端显示「未配置打分规则」) */
  unconfigured?: boolean;
  totalScore?: number | string;
  ruleVersion?: string;
  calcTime?: string;
  dimensionDetail?: Record<string, ProfileDimensionDetail>;
}

export const getRiskBands = (): Promise<ProfileRiskBands> => r<ProfileRiskBands>({ url: `${BASE}/risk-bands`, method: 'get' });

/** 批2 列源: 画像系(有主体)/分析系(有事件)两份源清单(行键为 snake 原名) */
export interface ProfileSourceSummary {
  /** 被停用的源数(清单空但 hidden>0 = 全被停了, 不是没数据) */
  subjectHidden?: number;
  eventHidden?: number;
  subjectSources: { source: string; subjects: number | string; display_name?: string | null }[];
  eventSources: { source: string; events: number | string; display_name?: string | null }[];
}

export const listProfileSources = (): Promise<ProfileSourceSummary> => r<ProfileSourceSummary>({ url: `${BASE}/source/list`, method: 'get' });

// ---------------------------------------------------------------- 第二批: 来源改名/停用

/** 来源管理行: 显示名/启停为配置覆盖层(未配置=默认启用/空显示名), 计数现算 */
export interface ProfileSourceConfigRow {
  source: string;
  displayName?: string | null;
  enabled: string;
  subjects?: number | string;
  events?: number | string;
}

export const listSourceConfigs = (): Promise<ProfileSourceConfigRow[]> =>
  r<ProfileSourceConfigRow[]>({ url: `${BASE}/source-config/list`, method: 'get' });

export const saveSourceConfig = (data: { source: string; displayName?: string; enabled?: string }): Promise<void> =>
  r<void>({ url: `${BASE}/source-config/save`, method: 'post', data });

export const getSubjectSummary = (subjectId: string, subjectType = 'user', source?: string): Promise<ProfileSubjectSummary> =>
  r<ProfileSubjectSummary>({ url: `${BASE}/subject/summary`, method: 'get', params: { subjectId, subjectType, source: source || undefined } });

// ---------------------------------------------------------------- 批10: 回放 / 健康 / 身份合并

/** 回放任务（断点=currentDay，回放前后对账数字在 statsBefore/statsAfter） */
export interface ProfileReplayTask {
  replayId: string;
  tenantId: string;
  source: string;
  fromDay: string;
  toDay: string;
  chains: string;
  status: 'RUNNING' | 'PAUSED' | 'DONE' | 'FAILED';
  currentDay?: string;
  daysTotal: number;
  daysDone: number;
  statsBefore?: string;
  statsAfter?: string;
  errorMsg?: string;
  createTime?: string;
}

export const listReplayTasks = (source?: string): Promise<ProfileReplayTask[]> =>
  r<ProfileReplayTask[]>({ url: '/analytics/replay/list', method: 'get', params: { source } });

export const getReplayTask = (replayId: string): Promise<ProfileReplayTask> =>
  r<ProfileReplayTask>({ url: `/analytics/replay/${replayId}`, method: 'get' });

export const startReplay = (data: { source: string; fromDate: string; toDate: string; chains: string[] }): Promise<ProfileReplayTask> =>
  r<ProfileReplayTask>({ url: '/analytics/replay', method: 'post', data });

export const resumeReplay = (replayId: string): Promise<ProfileReplayTask> =>
  r<ProfileReplayTask>({ url: `/analytics/replay/${replayId}/resume`, method: 'post' });

export const pauseReplay = (replayId: string): Promise<ProfileReplayTask> =>
  r<ProfileReplayTask>({ url: `/analytics/replay/${replayId}/pause`, method: 'post' });

/** 引擎健康四指标（批10 Q5） */
export interface ProfileHealth {
  source: string;
  windowHours: number;
  eventLatency: { p50?: number; p95?: number; times?: number };
  rebuildDuration: { p50?: number; p95?: number; times?: number };
  rebuildRecent: { durationMs?: number; sessions?: number; kind?: string; finishedAt?: string }[];
  tasks: { total: number; failed: number; failRatePct: number };
  backlog: { events: number; lastRebuildAt?: string };
  thresholds: { failRatePct: number; backlog: number; latencyP95Ms: number; rebuildP95Ms: number };
  alerts: { metric: string; value: string; message: string }[];
}

export const getEngineHealth = (source: string): Promise<ProfileHealth> =>
  r<ProfileHealth>({ url: '/analytics/health', method: 'get', params: { source } });

/** 身份合并记录（批10 Q6） */
export interface ProfileIdentityMerge {
  mergeId: string;
  tenantId: string;
  source: string;
  subjectType: string;
  fromSubjectId: string;
  toSubjectId: string;
  reason?: string;
  actor?: string;
  occurredAt?: string;
  reversedAt?: string | null;
  reversedBy?: string | null;
}

export const listIdentityMerges = (source?: string, subject?: string): Promise<ProfileIdentityMerge[]> =>
  r<ProfileIdentityMerge[]>({ url: `${BASE}/identity/merges`, method: 'get', params: { source, subject } });

export const unmergeIdentity = (mergeId: string): Promise<{ eventsReassigned: number; derivedReplayed: boolean }> =>
  r<{ eventsReassigned: number; derivedReplayed: boolean }>({ url: `${BASE}/identity/unmerge/${mergeId}`, method: 'post' });

// ==================== 批11 合规中心：PII 清单 / 主体删除向导 / 导出审计 ====================

export interface ProfilePiiField {
  fieldKey: string;
  maskRule: string;
  remark?: string;
  enabled: string;
}

export interface ProfileDeletionPreview {
  events: number;
  archives: number;
  sessions: number;
  links: number;
  snapshots: number;
  members: number;
  reviews: number;
  subjects: number;
  visitorKeys: string[];
  minOccurred: string | null;
  maxOccurred: string | null;
  rebuildDays: number;
  deletable: boolean;
  warning?: string;
}

export interface ProfileDeletionLogRow {
  logId: string;
  source: string;
  subjectType: string;
  subjectId: string;
  status: string;
  eventsDeleted: number;
  archivesDeleted: number;
  sessionsDeleted: number;
  daysRebuilt: number;
  errorMsg?: string;
  executedAt: string;
  createBy?: number;
  preview?: string;
}

export interface ProfileExportLogRow {
  logId: string;
  source: string;
  segmentId: string;
  segmentName?: string;
  rowCount: number;
  masked: string;
  fileHash?: string;
  exportBy?: number;
  exportAt: string;
}

export const listPiiFields = (): Promise<ProfilePiiField[]> => r<ProfilePiiField[]>({ url: `${BASE}/pii/fields`, method: 'get' });

export const savePiiField = (data: ProfilePiiField): Promise<ProfilePiiField> =>
  r<ProfilePiiField>({ url: `${BASE}/pii/fields`, method: 'post', data });

export const deletePiiField = (fieldKey: string): Promise<void> =>
  r<void>({ url: `${BASE}/pii/fields/${encodeURIComponent(fieldKey)}`, method: 'delete' });

export const previewDeletion = (source: string, subjectType: string, subjectId: string): Promise<ProfileDeletionPreview> =>
  r<ProfileDeletionPreview>({ url: `${BASE}/deletion/preview`, method: 'get', params: { source, subjectType, subjectId } });

export const executeDeletion = (data: {
  source: string;
  subjectType: string;
  subjectId: string;
  confirmText: string;
}): Promise<Record<string, any>> => r<Record<string, any>>({ url: `${BASE}/deletion/execute`, method: 'post', data });

export const listDeletionLogs = (): Promise<ProfileDeletionLogRow[]> => r<ProfileDeletionLogRow[]>({ url: `${BASE}/deletion/logs`, method: 'get' });

export const listExportLogs = (): Promise<ProfileExportLogRow[]> => r<ProfileExportLogRow[]>({ url: `${BASE}/export/logs`, method: 'get' });

// ---- F 片: 场景模板库(推荐场景=内置 7 套 / 我的模板=本租户导入) ----

export interface ScenarioWeightPreview {
  dimCode: string;
  dimName: string;
  weight: number | null;
}

export interface ScenarioTemplateSummary {
  kind: 'builtin' | 'saved';
  templateId: string | null; // 雪花ID超JS安全整数, Jackson序列化为字符串
  code: string;
  name: string;
  scenario: string | null;
  description: string | null;
  recommended: boolean;
  dimensionCount: number;
  segmentCount: number;
  funnelCount: number;
  weightPreview: ScenarioWeightPreview[];
}

export interface ScenarioCatalog {
  builtin: ScenarioTemplateSummary[];
  saved: ScenarioTemplateSummary[];
}

export interface ScenarioApplyReceipt {
  templateName: string;
  dimensionsCreated: string[];
  dimensionsSkipped: string[];
  draftRuleId: string; // 同上, Long序列化为字符串
  draftNote: string;
  segmentsCreated: string[];
  segmentsSkipped: string[];
  funnelsCreated: string[];
  funnelsSkipped: string[];
}

export const getScenarioTemplates = (): Promise<ScenarioCatalog> =>
  r<ScenarioCatalog>({ url: `${BASE}/scenario/templates`, method: 'get' });

export const applyScenarioTemplate = (data: {
  code?: string;
  templateId?: string;
  subjectType?: string;
}): Promise<ScenarioApplyReceipt> => r<ScenarioApplyReceipt>({ url: `${BASE}/scenario/apply`, method: 'post', data });

export const importScenarioTemplate = (data: unknown): Promise<Record<string, any>> =>
  r<Record<string, any>>({ url: `${BASE}/scenario/import`, method: 'post', data });

export const exportScenarioTemplate = (params: { code?: string; templateId?: string }): Promise<Record<string, any>> =>
  r<Record<string, any>>({ url: `${BASE}/scenario/export`, method: 'get', params });

export const deleteScenarioTemplate = (templateId: string): Promise<boolean> =>
  r<boolean>({ url: `${BASE}/scenario/${templateId}`, method: 'delete' });
