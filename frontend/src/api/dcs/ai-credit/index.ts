import request from '@/utils/request';
import { AxiosPromise } from 'axios';

const BASE = '/dcs/ai-credit';

/** 积分档位（SUBSCRIBE 订阅 / BOOST 加油包） */
export interface CreditPlanVO {
  id: number;
  planCode: string;
  kind: 'SUBSCRIBE' | 'BOOST';
  planName: string;
  priceYuan: number;
  weeklyCredits: number;
  windowCredits: number;
  windowHours: number;
  /** 业务批7 Q1：订阅档每日联网搜索次数（0=跟随租户默认） */
  websearchLimitPerUserDaily: number;
  durationWeeks: number;
  /** 业务反馈批1015b Q12：整周期总积分额度（NULL=每周×周数推导；填写值为准） */
  totalCredits?: number | null;
  description: string;
  enabled: string;
}

/** 三本账快照（周池 / 5h 窗口 / 加油包余额） */
export interface CreditSummaryVO {
  subscribed: boolean;
  planCode: string | null;
  planName: string | null;
  endTime: string | null;
  weeklyUsed: number;
  weeklyCap: number;
  windowUsed: number;
  windowCap: number;
  windowHours: number;
  tankBalance: number;
  weekStart: string;
  /** 业务批8 Q3：流水汇总条——本周/本月累计消耗（DEDUCT 周池+加油包合计，未订阅也给历史值） */
  weekSpent: number;
  monthSpent: number;
  /** 业务反馈批1015b Q12：整周期总额口径（先软后硬——只展示/预警不参与扣费闸） */
  totalQuota: number;
  termUsed: number;
}

export interface CreditSubscriptionVO {
  id: number;
  tenantId: string;
  planId: number;
  planCode: string;
  planName: string;
  startTime: string;
  endTime: string;
  status: string;
}

export interface CreditLedgerVO {
  id: number;
  tenantId: string;
  bizType: 'SUBSCRIBE' | 'BOOST' | 'DEDUCT' | 'ADJUST';
  credits: number;
  weeklyCredits: number;
  tankCredits: number;
  scene: string;
  remark: string;
  createBy: number;
  /** 业务批8 Q6：消耗人昵称（服务侧回填） */
  operatorName?: string;
  createTime: string;
}

export interface CreditPlansData {
  plans: CreditPlanVO[];
  summary: CreditSummaryVO;
  activeSubscription: CreditSubscriptionVO | null;
  /** 业务批8 Q7 丁：当前消费方式 WALLET=钱包按量 / CREDIT=积分订阅 */
  consumeMode?: 'WALLET' | 'CREDIT';
  /** 业务批8 Q4：各档位当前生效订阅租户数（planCode → count，改价影响提示） */
  planActiveCounts?: Record<string, number>;
}

export interface MyCreditData {
  summary: CreditSummaryVO;
  recent: CreditLedgerVO[];
}

/** 套餐编辑/新增 BO（业务批8 Q4：任何参数可改；id 空=新增） */
export interface CreditPlanSaveBO {
  id?: number;
  planCode: string;
  kind: 'SUBSCRIBE' | 'BOOST';
  planName: string;
  priceYuan: number;
  weeklyCredits: number;
  windowCredits?: number;
  windowHours?: number;
  durationWeeks?: number;
  websearchLimitPerUserDaily?: number;
  description?: string;
}

export const getCreditPlans = (): AxiosPromise<CreditPlansData> => request({ url: `${BASE}/plans`, method: 'get' });

/** 业务批8 Q4：grant=true 赠送开通（不扣钱包），仅超管 */
export const subscribeCreditPlan = (planCode: string, grant = false) =>
  request({ url: `${BASE}/subscribe`, method: 'post', params: { planCode, grant } });

export const boostCreditPlan = (planCode: string, grant = false) => request({ url: `${BASE}/boost`, method: 'post', params: { planCode, grant } });

/** 业务批8 Q7 丁：消费方式开关 */
export const getBillingMode = (): AxiosPromise<{ mode: 'WALLET' | 'CREDIT'; subscribed: boolean }> =>
  request({ url: `${BASE}/billing-mode`, method: 'get' });

export const setBillingMode = (mode: 'WALLET' | 'CREDIT') => request({ url: `${BASE}/billing-mode`, method: 'post', params: { mode } });

/** 业务批8 Q4：套餐新增/编辑（任何参数可改，仅超管权限） */
export const saveCreditPlan = (data: CreditPlanSaveBO) => request({ url: `${BASE}/plan/save`, method: 'post', data });

export const changeCreditPlanStatus = (id: number, enabled: '0' | '1') =>
  request({ url: `${BASE}/plan/change-status`, method: 'post', params: { id, enabled } });

/** 业务批7 Q4：流水真分页（bizType/时间范围可选；返回 TableDataInfo 形态 {rows,total}）
 *  业务批8 Q6 双视角：onlyMine=只看本人消耗；userId=按人筛选（全景视角）；行带 operatorName */
export const listCreditLedger = (params?: {
  bizType?: string;
  beginTime?: string;
  endTime?: string;
  onlyMine?: boolean;
  userId?: number;
  pageNum?: number;
  pageSize?: number;
}): AxiosPromise<{ rows: CreditLedgerVO[]; total: number }> => request({ url: `${BASE}/ledger`, method: 'get', params });

/** 业务批8 Q6：按人筛选下拉——近 90 天有流水的操作人 */
export const listLedgerUsers = (): AxiosPromise<Array<{ userId: number; nickName: string }>> =>
  request({ url: `${BASE}/ledger/users`, method: 'get' });

/** 业务批8 Q2：模型积分倍率表（当前租户在用 CHAT 模型） */
export interface ModelRateVO {
  configId: number;
  provider: string;
  modelName: string;
  multiplier: number;
  free: boolean;
  /** 预估积分/轮（免费档=0） */
  perRound: number;
  isDefault: boolean;
}

export const getModelRates = (): AxiosPromise<ModelRateVO[]> => request({ url: `${BASE}/model-rates`, method: 'get' });

export const getMyCreditSummary = (): AxiosPromise<MyCreditData> => request({ url: `${BASE}/my-summary`, method: 'get' });
