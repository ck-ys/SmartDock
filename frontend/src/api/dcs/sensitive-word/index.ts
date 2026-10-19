import request from '@/utils/request';
import type { AxiosPromise } from 'axios';
import type { SensitiveWordForm, SensitiveWordQuery, SensitiveWordVO } from './types';

const BASE = '/dcs/sensitive-word';

export const listSensitiveWord = (query?: SensitiveWordQuery): AxiosPromise<SensitiveWordVO[]> =>
  request({ url: `${BASE}/list`, method: 'get', params: query });

export const getSensitiveWord = (id: number | string): AxiosPromise<SensitiveWordVO> => request({ url: `${BASE}/${id}`, method: 'get' });

export const addSensitiveWord = (data: SensitiveWordForm): AxiosPromise<number | string> => request({ url: BASE, method: 'post', data });

export const updateSensitiveWord = (data: SensitiveWordForm): AxiosPromise<void> => request({ url: `${BASE}/edit`, method: 'post', data });

export const delSensitiveWord = (ids: number | string | Array<number | string>): AxiosPromise<void> =>
  request({ url: `${BASE}/${ids}`, method: 'post' });

/** 词库全量统计(2026-08-23 统计卡口径修复: 不再只算当前页) */
export const getSensitiveWordStats = (): AxiosPromise<{
  total: number;
  enabled: number;
  actionMask: number;
  actionBlock: number;
  actionReview: number;
}> => request({ url: `${BASE}/stats`, method: 'get' });

export interface SensitiveWordBatchForm {
  words: string[];
  category?: string;
  action: string;
  enabled?: string;
  remark?: string;
  /** 生效场景 CSV(2026-09-22 场景化): CHAT/FORM/FILE 逗号组合, 缺省 CHAT */
  scene?: string;
}

/** 批量导入(2026-08-23): 单请求单事务, 批内/库内重复词跳过 */
export const batchImportSensitiveWords = (data: SensitiveWordBatchForm): AxiosPromise<string> =>
  request({ url: `${BASE}/batch`, method: 'post', data });

export interface SensitiveWordTestResult {
  hits: Array<{ word: string; action: string; start: number; end: number }>;
  blocked: boolean;
  reviewRequired: boolean;
}

/** 命中自测(2026-08-24): 真实过滤规则只读匹配, 不产生风险事件不记分; scene(2026-09-22)=CHAT/FORM/FILE, 空=全部词 */
export const testSensitiveWordMatch = (text: string, scene?: string): AxiosPromise<SensitiveWordTestResult> =>
  request({ url: `${BASE}/test`, method: 'post', data: { text, scene: scene || undefined } });
