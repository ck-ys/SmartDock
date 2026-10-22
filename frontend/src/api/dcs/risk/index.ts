import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import type {
  CaptchaActionContext,
  CaptchaOtpVerifyRequest,
  CaptchaResult,
  CaptchaVerificationResult,
  CaptchaVerifyRequest,
  DashboardVO,
  PageQuery,
  ProfileReviewAggregateVO,
  ProfileReviewQuery,
  ProfileReviewVO,
  RiskActionQuery,
  RiskActionVO,
  RiskEventQuery,
  RiskEventVO,
  RiskReviewCommand,
  RiskRuleForm,
  RiskRuleVO,
  RiskWhitelistQuery,
  RiskWhitelistVO
} from './types';

// 重新导出类型和常量, 方便页面统一从 index.ts 导入
export * from './types';

/** 风控大盘汇总(Req 7.1: timeRange=24h/7d/30d/all) */
export function getDashboard(timeRange?: string): AxiosPromise<DashboardVO> {
  return request({
    url: '/dcs/risk/dashboard',
    method: 'get',
    params: timeRange ? { timeRange } : undefined
  });
}

/** 事件流水(分页) */
export function listEvents(params: RiskEventQuery): AxiosPromise<RiskEventVO[]> {
  return request({
    url: '/dcs/risk/events',
    method: 'get',
    params
  });
}

/** 规则列表 */
export function listRules(): AxiosPromise<RiskRuleVO[]> {
  return request({
    url: '/dcs/risk/rules',
    method: 'get'
  });
}

/** 更新规则 */
export function updateRule(data: RiskRuleForm): AxiosPromise<void> {
  return request({
    url: '/dcs/risk/rules/update',
    method: 'post',
    data
  });
}

/** Req 7.2: 新增规则 */
export function addRule(data: RiskRuleForm): AxiosPromise<void> {
  return request({
    url: '/dcs/risk/rules',
    method: 'post',
    data
  });
}

/** Req 7.2: 删除规则 */
export function deleteRule(ruleId: number | string) {
  return request({
    url: `/dcs/risk/rules/${ruleId}`,
    method: 'post'
  });
}

/** 白名单分页 */
export function listWhitelist(params: RiskWhitelistQuery): AxiosPromise<RiskWhitelistVO[]> {
  return request({
    url: '/dcs/risk/whitelist',
    method: 'get',
    params
  });
}

/** 新增白名单 */
export function addWhitelist(data: RiskWhitelistVO): AxiosPromise<void> {
  return request({
    url: '/dcs/risk/whitelist',
    method: 'post',
    data
  });
}

/** 删除白名单 */
export function deleteWhitelist(id: number | string) {
  return request({
    url: `/dcs/risk/whitelist/${id}/delete`,
    method: 'post'
  });
}

/**
 * 更新白名单（B 档新增）。
 *
 * 轻量更新：只改 reason / enabled，不改 listType/value（创建后语义不变）。
 * 前端 el-switch 切换 enabled 时调用此接口，比删后重建体验更好。
 */
export function updateWhitelist(id: number | string, data: { reason?: string; enabled?: '0' | '1' }): AxiosPromise<void> {
  return request({
    url: `/dcs/risk/whitelist/${id}`,
    method: 'post',
    data
  });
}

/**
 * 查询单条风险事件详情（B 档新增）。
 *
 * 用于事件流水表格操作列"查看详情"弹窗，展示完整字段（detail/sourceTrack 等）。
 */
export function getEventDetail(eventId: number | string): AxiosPromise<RiskEventVO> {
  return request({
    url: `/dcs/risk/events/${eventId}`,
    method: 'get'
  });
}

/**
 * 单字段切换风控规则启用状态（B 档新增）。
 *
 * 设计意图：避免前端切换开关时被迫回填全部阈值字段（updateRule 需要全量 RiskRuleForm）。
 * 直接 PATCH 单字段，零回填。
 */
export function toggleRuleEnabled(ruleId: number | string, enabled: '0' | '1'): AxiosPromise<void> {
  return request({
    url: `/dcs/risk/rules/${ruleId}/enabled`,
    method: 'post',
    params: { enabled }
  });
}

/**
 * 批量解除安全处置（B 档新增）。
 *
 * 后端循环单条 release，部分失败聚合消息返回（不回滚已成功的）。
 */
export function batchReleaseRiskActions(actionIds: Array<number | string>, reason: string): AxiosPromise<string> {
  return request({
    url: '/dcs/risk/actions/batch-release',
    method: 'post',
    data: { ids: actionIds, reason }
  });
}

/**
 * 批量复核安全处置（B 档新增）。语义同 batchReleaseRiskActions。
 */
export function batchReviewRiskActions(
  actionIds: Array<number | string>,
  conclusion: 'CONFIRMED' | 'DISMISSED',
  reason: string
): AxiosPromise<string> {
  return request({
    url: '/dcs/risk/actions/batch-review',
    method: 'post',
    data: { ids: actionIds, conclusion, reason }
  });
}

export function listRiskActions(params: RiskActionQuery): AxiosPromise<RiskActionVO[]> {
  return request({ url: '/dcs/risk/actions', method: 'get', params });
}

export function getRiskAction(actionId: number | string): AxiosPromise<RiskActionVO> {
  return request({ url: `/dcs/risk/actions/${actionId}`, method: 'get' });
}

export function releaseRiskAction(actionId: number | string, data: RiskReviewCommand): AxiosPromise<void> {
  return request({ url: `/dcs/risk/actions/${actionId}/release`, method: 'post', data });
}

export function reviewRiskAction(actionId: number | string, data: RiskReviewCommand): AxiosPromise<void> {
  return request({ url: `/dcs/risk/actions/${actionId}/review`, method: 'post', data });
}

export function listProfileReviews(params: ProfileReviewQuery): AxiosPromise<ProfileReviewVO[]> {
  return request({ url: '/dcs/risk/profile-reviews', method: 'get', params });
}

export function reviewProfileRisk(reviewId: number | string, data: RiskReviewCommand): AxiosPromise<void> {
  return request({ url: `/dcs/risk/profile-reviews/${reviewId}/review`, method: 'post', data });
}

/** 聚合分页: 每用户一行待复核画像(同人最新快照+积压数), 复核页主视图数据源(2026-08-23) */
export function listProfileReviewAggregate(params?: PageQuery & { userId?: number | string }): AxiosPromise<ProfileReviewAggregateVO[]> {
  return request({ url: '/dcs/risk/profile-reviews/aggregate', method: 'get', params });
}

/** 按用户一次性复核其全部待复核画像(2026-08-23 积压治理) */
export function reviewProfileRiskByUser(userId: number | string, data: RiskReviewCommand): AxiosPromise<string> {
  return request({ url: '/dcs/risk/profile-reviews/review-user', method: 'post', data: { userId, ...data } });
}

/** 评估当前用户风险 */
export function assessMe(scene = 'SEND_MSG') {
  return request({
    url: '/dcs/captcha/need',
    method: 'get',
    params: { scene }
  });
}

/** 评估指定用户风险(管理员) */
export function assessUser(userId: number | string) {
  return request({
    url: `/dcs/risk/assess/user/${userId}`,
    method: 'get'
  });
}

/** 生成验证码(按当前风险等级) */
export function generateCaptcha(data: CaptchaActionContext): AxiosPromise<CaptchaResult> {
  return request({
    url: '/dcs/captcha/generate',
    method: 'post',
    data
  });
}

/** 指定难度生成验证码 */
export function generateCaptchaByLevel(level: number, data: CaptchaActionContext): AxiosPromise<CaptchaResult> {
  return request({
    url: `/dcs/captcha/generate/${level}`,
    method: 'post',
    data
  });
}

/** 校验验证码 */
export function verifyCaptcha(data: CaptchaVerifyRequest): AxiosPromise<CaptchaVerificationResult> {
  return request({
    url: '/dcs/captcha/verify',
    method: 'post',
    data
  });
}

/** 校验 L4 OTP */
export function verifyOtp(data: CaptchaOtpVerifyRequest): AxiosPromise<CaptchaVerificationResult> {
  return request({
    url: '/dcs/captcha/verify-otp',
    method: 'post',
    data
  });
}

/** 内容机检开关档位(收尾批 Q4, 2026-09-23) */
export interface ContentConfigState {
  contentModerationEnabled: boolean;
  contentBlockEnabled: boolean;
}

/** 查询内容机检开关当前档位(运行时内存态) */
export function getContentConfigState(): AxiosPromise<ContentConfigState> {
  return request({ url: '/dcs/risk/content-config/state', method: 'get' });
}

/** 修改内容机检开关(仅传要改的字段, null 不动; 落 sys_oper_log) */
export function updateContentConfig(data: Partial<ContentConfigState>): AxiosPromise<ContentConfigState> {
  return request({ url: '/dcs/risk/content-config/update', method: 'post', data });
}
