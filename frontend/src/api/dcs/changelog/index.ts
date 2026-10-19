import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import { ChangelogQuery, ChangelogRow, ChangelogSaveBody } from './types';

const BASE_URL = '/dcs/changelog';

/** 更新日志分页（全部管理员可见；后端 itemList 已解析结构化） */
export const listChangelog = (query: ChangelogQuery): AxiosPromise<ChangelogRow[]> =>
  request({ url: `${BASE_URL}/list`, method: 'get', params: query });

/** 更新日志详情 */
export const getChangelog = (logId: string | number): AxiosPromise<ChangelogRow> => request({ url: `${BASE_URL}/${logId}`, method: 'get' });

/** 新增版本（仅超管） */
export const addChangelog = (data: ChangelogSaveBody): AxiosPromise<string | number> => request({ url: BASE_URL, method: 'post', data });

/** 修改版本（仅超管；status 0→1 即发布，自动通知全部租户管理员） */
export const updateChangelog = (data: ChangelogSaveBody): AxiosPromise<void> => request({ url: `${BASE_URL}/edit`, method: 'post', data });

/** 删除版本（仅超管，逻辑删除） */
export const deleteChangelog = (logIds: (string | number)[]): AxiosPromise<void> =>
  request({ url: `${BASE_URL}/${logIds.join(',')}`, method: 'post' });
