import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import type { FeedbackHandle, FeedbackQuery, FeedbackRow, FeedbackSubmit } from './types';

const BASE = '/dcs/feedback';

/** 提交反馈（全员） */
export const submitFeedback = (data: FeedbackSubmit) => request({ url: BASE, method: 'post', data });

/** 我的反馈 */
export const myFeedbacks = (params?: FeedbackQuery): AxiosPromise<FeedbackRow[]> =>
  request({ url: `${BASE}/my`, method: 'get', params });

/** 我的反馈四态计数 */
export const myFeedbackSummary = (): AxiosPromise<Record<string, number>> =>
  request({ url: `${BASE}/my/summary`, method: 'get' });

/** 管理端列表 */
export const listFeedback = (params?: FeedbackQuery): AxiosPromise<FeedbackRow[]> =>
  request({ url: `${BASE}/list`, method: 'get', params });

/** 管理端四态计数 */
export const feedbackStatusSummary = (): AxiosPromise<Record<string, number>> =>
  request({ url: `${BASE}/status-summary`, method: 'get' });

/** 详情 */
export const getFeedback = (feedbackId: string | number): AxiosPromise<FeedbackRow> =>
  request({ url: `${BASE}/${feedbackId}`, method: 'get' });

/** 处理（四态流转 + 通知提交人） */
export const handleFeedback = (data: FeedbackHandle) => request({ url: `${BASE}/handle`, method: 'post', data });
