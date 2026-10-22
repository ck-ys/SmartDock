import request from '@/utils/request';
import { AxiosPromise } from 'axios';

/**
 * 微核心任务 API（批5，契约对齐 com.smartdock.dcs.DcsTaskController/DcsStatsController）。
 */
const BASE = '/dcs/task';

export interface DcsTaskFieldDef {
  fieldCode: string;
  fieldName: string;
  fieldType: 'text' | 'number' | 'date' | 'select' | 'multi_select';
  required: '1' | '0';
  options?: string[];
}

export interface DcsTaskRow {
  id: string;
  title: string;
  description?: string;
  status: 'DRAFT' | 'COLLECTING' | 'CLOSED';
  /** 仅 /dcs/task/my 出参: 本人最新提交态(PENDING_AUDIT/APPROVED/REJECTED/null) */
  mySubmissionStatus?: 'PENDING_AUDIT' | 'APPROVED' | 'REJECTED';
  auditMode: 'PUBLISHER' | 'ASSIGNED';
  assigneeUserIds?: number[];
  assigneeNames?: string[];
  deadline?: string;
  publisherUserId: number;
  publishTime?: string;
  createTime?: string;
}

export interface DcsTaskDetail {
  task: DcsTaskRow;
  fields: DcsTaskFieldDef[];
  mySubmission: {
    id: string;
    version: number;
    status: 'PENDING_AUDIT' | 'APPROVED' | 'REJECTED';
    submittedAt?: string;
  } | null;
}

export const pageTasks = (params?: Record<string, unknown>): AxiosPromise<DcsTaskRow[]> =>
  request({ url: `${BASE}/page`, method: 'get', params });

export const myTasks = (params?: Record<string, unknown>): AxiosPromise<DcsTaskRow[]> =>
  request({ url: `${BASE}/my`, method: 'get', params });

// 后端 DcsTaskField.options 是 JSON 文本列, 出参是字符串('["a","b"]')——统一在 API 层
// 归一成数组, 详情表单/编辑弹窗等消费方不再各自判型
const parseOptions = (o: unknown): string[] => {
  if (Array.isArray(o)) return o.map(String);
  if (typeof o === 'string' && o.trim()) {
    try { const p = JSON.parse(o); if (Array.isArray(p)) return p.map(String); } catch { /* 非法当空 */ }
  }
  return [];
};

// R 信封在拦截器原样返回({code,msg,data}), 这里解包让调用方拿到业务体
export const getTask = async (id: string | number): Promise<DcsTaskDetail> => {
  const res: any = await request({ url: `${BASE}/${id}`, method: 'get' });
  const det = res.data as DcsTaskDetail;
  if (det?.fields) det.fields = det.fields.map((f) => ({ ...f, options: parseOptions(f.options) }));
  return det;
};

export const createTask = (data: Partial<DcsTaskRow> & { fields: DcsTaskFieldDef[] }) =>
  request({ url: BASE, method: 'post', data });

export const updateTask = (data: Partial<DcsTaskRow> & { fields?: DcsTaskFieldDef[] }) =>
  request({ url: BASE, method: 'put', data });

export const publishTask = (id: string | number) =>
  request({ url: `${BASE}/${id}/publish`, method: 'post' });

export const closeTask = (id: string | number) =>
  request({ url: `${BASE}/${id}/close`, method: 'post' });

export interface DcsStatsOverview {
  publisher: { draft: number; collecting: number; closed: number };
  member: { todo: number; submitted: number; pendingAudit: number; approved: number; rejected: number; unreadNotices: number };
}

export const statsOverview = async (): Promise<DcsStatsOverview> => {
  const res: any = await request({ url: '/dcs/stats/overview', method: 'get' });
  return res.data as DcsStatsOverview;
};
