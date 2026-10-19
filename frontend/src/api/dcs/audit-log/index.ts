import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { DcsAuditLogQuery, DcsAuditLogVO } from './types';

const BASE_URL = '/dcs/audit-log';

export const listDcsAuditLog = (query: DcsAuditLogQuery) => request({ url: `${BASE_URL}/list`, method: 'get', params: query });

export const getDcsAuditLog = (logId: string): AxiosPromise<DcsAuditLogVO> => request({ url: `${BASE_URL}/${logId}`, method: 'get' });

export const listMissionAuditTimeline = (missionId: string): AxiosPromise<DcsAuditLogVO[]> =>
  request({ url: `${BASE_URL}/mission/${missionId}`, method: 'get' });
