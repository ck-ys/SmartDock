import request from '@/utils/request';
import { AxiosPromise } from 'axios';

/**
 * 画像引擎查询 API（批5，契约对齐 ruoyi-profile-engine ProfileQueryController）。
 */
const BASE = '/profile';

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
}

export interface ProfileSnapshot {
  id: string;
  source: string;
  subjectId: string;
  totalScore?: number;
  dimensions?: { dimensionCode: string; dimensionName?: string; score: number; level?: string }[];
  calcTime?: string;
  prevId?: string;
}

export const pageSubjects = (params?: { source?: string } & Record<string, unknown>): AxiosPromise<TableDataInfo<ProfileSubjectRow>> =>
  request({ url: `${BASE}/subject/page`, method: 'get', params });

// R 信封在拦截器原样返回({code,msg,data}), 这里解包让调用方拿到业务体
export const getScore = async (source: string, subjectId: string, subjectType = 'user'): Promise<{ latest: ProfileSnapshot | null; history: ProfileSnapshot[] }> => {
  const res: any = await request({ url: `${BASE}/score/${source}/${subjectId}`, method: 'get', params: { subjectType } });
  return res.data as { latest: ProfileSnapshot | null; history: ProfileSnapshot[] };
};
