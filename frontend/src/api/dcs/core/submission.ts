import request from '@/utils/request';
import { AxiosPromise } from 'axios';

/**
 * 微核心提交/审核 API（批5，契约对齐 DcsSubmissionController/DcsAuditController）。
 */

export interface DcsSubmissionRow {
  id: string;
  taskId: string;
  taskTitle?: string;
  submitterUserId: number;
  version: number;
  status: 'PENDING_AUDIT' | 'APPROVED' | 'REJECTED';
  data?: Record<string, unknown>;
  submittedAt?: string;
}

export const submitData = (taskId: string | number, data: Record<string, unknown>) =>
  request({ url: '/dcs/submission/submit', method: 'post', data: { taskId, data } });

export const mySubmissions = (params?: { taskId?: string | number } & Record<string, unknown>): AxiosPromise<TableDataInfo<DcsSubmissionRow>> =>
  request({ url: '/dcs/submission/my', method: 'get', params });

export interface DcsAuditTrailRow {
  id: string;
  submissionId: string;
  auditorUserId: number;
  decision: 'PASS' | 'REJECT';
  comment?: string;
  createTime?: string;
}

export const pendingAudits = (params?: Record<string, unknown>): AxiosPromise<TableDataInfo<DcsSubmissionRow>> =>
  request({ url: '/dcs/audit/pending', method: 'get', params });

export const decideAudit = (submissionId: string | number, decision: 'PASS' | 'REJECT', comment?: string) =>
  request({ url: '/dcs/audit/decide', method: 'post', data: { submissionId, decision, comment } });

// R 信封在拦截器原样返回({code,msg,data}), 这里解包让调用方拿到轨迹数组(全审 P1)
export const auditTrail = async (submissionId: string | number): Promise<DcsAuditTrailRow[]> => {
  const res: any = await request({ url: '/dcs/audit/trail', method: 'get', params: { submissionId } });
  return (res.data as DcsAuditTrailRow[]) || [];
};
