import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { AuditLogQuery, AuditLogVO } from './types';

const BASE_URL = '/audit-log';

export const listAuditLog = (query: AuditLogQuery) => request({ url: `${BASE_URL}/list`, method: 'get', params: query });

export const getAuditLog = (logId: string): AxiosPromise<AuditLogVO> => request({ url: `${BASE_URL}/${logId}`, method: 'get' });

export const listMissionAuditTimeline = (missionId: string): AxiosPromise<AuditLogVO[]> =>
  request({ url: `${BASE_URL}/mission/${missionId}`, method: 'get' });
