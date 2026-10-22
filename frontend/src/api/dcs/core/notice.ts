import request from '@/utils/request';
import { AxiosPromise } from 'axios';

/**
 * 微核心通知 API（批5，契约对齐 DcsNoticeController：/my + /{id}/read）。
 */
const BASE = '/dcs/notice';

export interface DcsNoticeRow {
  id: string;
  noticeType: string;
  title: string;
  content?: string;
  taskId?: string;
  readFlag: '0' | '1';
  createTime?: string;
}

export const myNotices = (params?: { readFlag?: string } & Record<string, unknown>): AxiosPromise<DcsNoticeRow[]> =>
  request({ url: `${BASE}/my`, method: 'get', params });

export const markNoticeRead = (id: string | number) =>
  request({ url: `${BASE}/${id}/read`, method: 'post' });
