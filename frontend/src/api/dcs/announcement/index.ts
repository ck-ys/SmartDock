import request from '@/utils/request';
import { AxiosPromise } from 'axios';
import type { AnnouncementRow, AnnouncementQuery } from './types';

const BASE = '/dcs/announcement';

/** 已发布公告卡片流（登录用户） */
export const listAnnouncements = (params?: AnnouncementQuery): AxiosPromise<AnnouncementRow[]> =>
  request({ url: `${BASE}/list`, method: 'get', params });

/** 公告详情 */
export const getAnnouncement = (noticeId: string | number): AxiosPromise<AnnouncementRow> =>
  request({ url: `${BASE}/${noticeId}`, method: 'get' });

/** 标记已读 */
export const markAnnouncementRead = (noticeId: string | number) =>
  request({ url: `${BASE}/${noticeId}/read`, method: 'post' });

/** 未读公告数（铃铛角标） */
export const announcementUnreadCount = (): AxiosPromise<number> =>
  request({ url: `${BASE}/unread-count`, method: 'get' });
