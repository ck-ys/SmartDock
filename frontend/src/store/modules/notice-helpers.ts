type PersistedNoticeLike = {
  noticeId: number | string;
  title?: string;
  content?: string;
  createTime?: string;
  readStatus?: string;
};

type RealtimeNoticeLike = {
  title?: string;
  read?: boolean;
  message: any;
  time: string;
};

export type NoticeLike = PersistedNoticeLike | RealtimeNoticeLike;

export const isPersistedNotice = (notice: NoticeLike): notice is PersistedNoticeLike => Object.prototype.hasOwnProperty.call(notice, 'noticeId');

export const isNoticeUnread = (notice: NoticeLike): boolean => (isPersistedNotice(notice) ? notice.readStatus !== '1' : !notice.read);

export const markNoticeItemRead = (notice: NoticeLike): boolean => {
  if (!isNoticeUnread(notice)) {
    return false;
  }
  if (isPersistedNotice(notice)) {
    notice.readStatus = '1';
  } else {
    notice.read = true;
  }
  return true;
};

export const getNoticeKey = (notice: NoticeLike) => (isPersistedNotice(notice) ? notice.noticeId : `${notice.time}-${notice.message}`);

export const getNoticeTitle = (notice: NoticeLike) => (isPersistedNotice(notice) ? notice.title : notice.title || '系统消息');

export const getNoticeContent = (notice: NoticeLike) => (isPersistedNotice(notice) ? notice.content : notice.message);

export const getNoticeTime = (notice: NoticeLike) => (isPersistedNotice(notice) ? notice.createTime : notice.time);
