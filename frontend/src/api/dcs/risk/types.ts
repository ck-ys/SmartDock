/** 风险事件 */
export interface RiskEventVO {
  eventId: number | string;
  userName?: string;
  nickName?: string;
  userId?: number | string;
  ip?: string;
  userAgent?: string;
  eventType: string;
  source: string;
  refId?: number | string;
  detail?: string;
  riskScore: number;
  createdTime: string;
  /** 命中词(风控2.0 内容机检事件带, 列表态已打码展示) */
  hitWord?: string;
}

/** 风控规则 */
export interface RiskRuleVO {
  ruleId: number | string;
  eventType: string;
  ruleScope: 'EVENT' | 'GLOBAL';
  weight: number;
  enabled: '0' | '1';
  thresholdL1: number;
  thresholdL2: number;
  thresholdL3: number;
  thresholdL4: number;
  windowMinutes: number;
  description?: string;
}

/** 白名单 */
export interface RiskWhitelistVO {
  whitelistId?: number | string;
  listType: 'IP' | 'USER' | 'DOMAIN';
  value: string;
  reason?: string;
  enabled?: '0' | '1';
  createTime?: string;
}

/** 大盘数据 */
export interface DashboardVO {
  totalEvents: number;
  activeActions?: number;
  pendingProfileReviews?: number;
  eventTypeStats: Array<{ eventType: string; cnt: number; totalScore: number }>;
  levelStats: Array<{ lvl: string; cnt: number }>;
  recentEvents: RiskEventVO[];
  /** B 档新增：策略命中排行 TOP10（按事件类型聚合，业务调优参考） */
  topRules?: Array<{ eventType: string; cnt: number }>;
  /** 风控2.0 批1(2026-09-22): 今日内容机检分档(命中阻断词数/命中复核词数) */
  contentTodayStats?: { blocked: number; review: number };
  /** 风控2.0 批1: 近7天事件来源分布(全部事件类型) */
  sourceStats7d?: Array<{ source: string; cnt: number }>;
  /** 风控2.0 批1: 近7天 TOP 命中词(key 为 word); U03(2026-09-23) 增 fpCnt=同窗该词被判误报次数 */
  topHitWords7d?: Array<{ word: string; cnt: number; fpCnt?: number }>;
  /** 风控2.0 批1: 近7天文件审核驳回数(批2接线前恒 0) */
  fileRejected7d?: number;
  /** 风控2.0 批2: 误报反馈累计(明细表 machine_check='FP' 行数聚合, 非事件口径) */
  falseFeedbackCount?: number;
  /** 风控2.0 批1: 防线状态(灰度开关直读, 大盘右侧防线卡数据源) */
  defenseStatus?: DefenseStatus;
}

/** 防线状态(2026-09-22): 内容机检灰度开关 + 平行防线在岗标记 */
export interface DefenseStatus {
  /** 内容机检总开关(默认关=不检测不记事件) */
  contentModerationEnabled: boolean;
  /** 阻断拦截开关(恒关=观察模式: 只记事件不拦提交) */
  contentBlockEnabled: boolean;
  fileguardActive: boolean;
  fileReviewActive: boolean;
  chatPaused: boolean;
}

export interface PageQuery {
  pageNum: number;
  pageSize: number;
}

export interface RiskEventQuery extends PageQuery {
  eventType?: string;
  userId?: number | string;
  ip?: string;
  /** B 档新增：时间区间筛选（毫秒时间戳，el-date-picker datatimerange 传 Date.getTime()） */
  startTime?: number | string;
  endTime?: number | string;
  /** 风险分下限(≥)，滤掉低分噪音（2026-08-23） */
  minRiskScore?: number;
  /** true=按风险分降序优先，默认按时间降序（2026-08-23） */
  orderByScore?: boolean;
}

export type RiskRuleForm = Omit<RiskRuleVO, 'ruleId'> & { ruleId?: number | string };

export interface RiskWhitelistQuery extends PageQuery {
  listType?: RiskWhitelistVO['listType'];
}

export interface RiskActionQuery extends PageQuery {
  status?: '' | 'ACTIVE' | 'EXPIRED' | 'RELEASED';
  actionType?: '' | 'ENHANCED_VERIFICATION' | 'TEMP_FREEZE';
  userId?: number | string;
}

export interface RiskActionVO {
  actionId: number | string;
  userId: number | string;
  userName?: string;
  nickName?: string;
  ipMasked?: string;
  riskLevel: number;
  riskScore: number;
  sourceTrack?: string;
  actionType: 'ENHANCED_VERIFICATION' | 'TEMP_FREEZE';
  triggerReason: string;
  status: 'ACTIVE' | 'EXPIRED' | 'RELEASED';
  startTime: string;
  expireTime: string;
  releasedBy?: number | string;
  releasedTime?: string;
  releaseReason?: string;
  reviewStatus: 'PENDING' | 'CONFIRMED' | 'DISMISSED';
  reviewedBy?: number | string;
  reviewedTime?: string;
  reviewConclusion?: 'CONFIRMED' | 'DISMISSED';
  reviewReason?: string;
  enforcementEnabled?: boolean;
}

export interface ProfileReviewQuery extends PageQuery {
  status?: '' | 'PENDING' | 'CONFIRMED' | 'DISMISSED' | 'SUPERSEDED';
  userId?: number | string;
}

/** 画像复核聚合视图行(2026-08-23 复核页主视图): 同人全部 PENDING 快照聚合为一行 */
export interface ProfileReviewAggregateVO {
  userId: number | string;
  userName?: string;
  nickName?: string;
  latestReviewId: number | string;
  scoreId: number | string;
  ruleVersion?: string;
  profileRiskLevel: 'HIGH';
  triggerReason: string;
  /** 该用户 PENDING 快照总数(积压数, 迁移后恒为 1) */
  pendingCount: number;
  firstTime?: string;
  lastTime?: string;
}

export interface ProfileReviewVO {
  reviewId: number | string;
  userId: number | string;
  userName?: string;
  nickName?: string;
  scoreId: number | string;
  ruleVersion?: string;
  profileRiskLevel: 'HIGH';
  status: 'PENDING' | 'CONFIRMED' | 'DISMISSED' | 'SUPERSEDED';
  triggerReason: string;
  conclusion?: 'CONFIRMED' | 'DISMISSED';
  reviewReason?: string;
  reviewedBy?: number | string;
  reviewedTime?: string;
  createTime?: string;
}

export interface RiskReviewCommand {
  conclusion?: 'CONFIRMED' | 'DISMISSED';
  reason: string;
}

/** 验证码生成结果 */
export interface CaptchaResult {
  token: string;
  imageBase64?: string; // data:image/png;base64,...
  level: number;
  needOtp: boolean;
  deliveryHint?: string; // L4 仅返回脱敏邮箱
}

/** 验证码绑定上下文 */
export interface CaptchaActionContext {
  scene: string;
  action: string;
}

/** 图片验证码校验请求 */
export interface CaptchaVerifyRequest extends CaptchaActionContext {
  token: string;
  answer: string;
}

/** OTP 校验请求 */
export interface CaptchaOtpVerifyRequest extends CaptchaActionContext {
  token: string;
  otpCode: string;
}

/** 验证结果；完整通过后才返回一次性通行令牌 */
export interface CaptchaVerificationResult {
  verified: boolean;
  needOtp: boolean;
  captchaPassToken?: string;
}

/** 事件类型标签映射 */
export const EVENT_TYPE_LABELS: Record<string, string> = {
  SENSITIVE_WORD: '敏感词命中',
  REPORTED: '被举报',
  LOGIN_FAIL: '登录失败',
  HIGH_FREQ_API: '高频接口',
  UPLOAD_ABUSE: '异常上传',
  TASK_CONTENT_RISK: '任务内容风险',
  FILE_REJECTED: '文件审核驳回'
};

/** 事件类型颜色 */
export const EVENT_TYPE_COLORS: Record<string, 'primary' | 'success' | 'warning' | 'info' | 'danger'> = {
  SENSITIVE_WORD: 'warning',
  REPORTED: 'danger',
  LOGIN_FAIL: 'warning',
  HIGH_FREQ_API: 'primary',
  UPLOAD_ABUSE: 'info',
  TASK_CONTENT_RISK: 'warning',
  FILE_REJECTED: 'danger'
};

/** 事件来源标签映射(2026-09-22 风控2.0): 未映射的 source 原样展示 */
export const EVENT_SOURCE_LABELS: Record<string, string> = {
  CHAT: '聊天',
  FORM: '表单填写',
  ONLINE: '在线批量',
  EXCEL: 'Excel导入',
  LOGIN: '登录',
  FILE: '文件审核'
};

/** 风险等级标签 */
export const LEVEL_LABELS: Record<number, string> = {
  0: 'L0 正常',
  1: 'L1 低风险',
  2: 'L2 中风险',
  3: 'L3 高风险',
  4: 'L4 极高危'
};

/** 风险等级颜色 */
export const LEVEL_COLORS: Record<number, string> = {
  0: 'success',
  1: 'info',
  2: 'warning',
  3: 'danger',
  4: 'danger'
};

/** 风险等级 Badge 类型 */
export const LEVEL_BADGE_TYPES: Record<number, 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
  0: 'success',
  1: 'info',
  2: 'warning',
  3: 'danger',
  4: 'danger'
};
