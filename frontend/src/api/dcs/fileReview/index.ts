import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import {
  FileReviewApproveResult,
  FileReviewConfig,
  FileReviewConfigSaveBody,
  FileReviewQuery,
  FileReviewRow,
  GuardLogRow,
  GuardStats
} from './types';

const BASE_URL = '/dcs/fileReview';

/** 审核单分页列表（status 必传：PENDING=待审页签 / APPROVED|REJECTED=已审页签） */
export const listFileReview = (query: FileReviewQuery): AxiosPromise<FileReviewRow[]> =>
  request({ url: `${BASE_URL}/list`, method: 'get', params: query });

/** 待审单数（页签徽标 / 侧边栏徽标共用数据源） */
export const getFileReviewPendingCount = (): AxiosPromise<number> => request({ url: `${BASE_URL}/pendingCount`, method: 'get' });

/** 批量通过（逐条 CAS 容错，结果分桶 success/conflict） */
export const approveFileReview = (reviewIds: (string | number)[]): AxiosPromise<FileReviewApproveResult> =>
  request({ url: `${BASE_URL}/approve`, method: 'post', data: reviewIds });

/** 驳回单条（理由必填 2-500 字，随通知发给上传人） */
export const rejectFileReview = (reviewId: string | number, reason: string): AxiosPromise<void> =>
  request({ url: `${BASE_URL}/reject`, method: 'post', data: { reviewId, reason } });

/** 配置读取（设置页：开关/类别 + 待审数/审核员数警告数据） */
export const getFileReviewConfig = (): AxiosPromise<FileReviewConfig> => request({ url: `${BASE_URL}/config`, method: 'get' });

/** 配置保存（幂等 upsert；关闭只影响新上传，存量待审单保留可审） */
export const saveFileReviewConfig = (data: FileReviewConfigSaveBody): AxiosPromise<void> =>
  request({ url: `${BASE_URL}/config`, method: 'post', data });

/** 安全检测近 7 天命中概览（设置弹窗顶部概览条，Q4=A 改版） */
export const getGuardStats = (): AxiosPromise<GuardStats> => request({ url: `${BASE_URL}/guard/stats`, method: 'get' });

/** 安全检测命中记录分页（「检测记录」页签，时间倒序） */
export const listGuardLogs = (query: PageQuery): AxiosPromise<GuardLogRow[]> =>
  request({ url: `${BASE_URL}/guard/logs`, method: 'get', params: query });
