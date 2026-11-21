export interface AnnouncementRow {
  noticeId: string | number;
  noticeTitle: string;
  /** 1=通知 2=公告（RuoYi 字典口径） */
  noticeType: string;
  noticeContent?: string;
  isTop?: string;
  publishTime?: string;
  expireTime?: string;
  createTime?: string;
  readFlag?: string;
}

export interface AnnouncementQuery extends PageQuery {
  /** 0=只看未读 1=只看已读 空=全部 */
  readFlag?: string;
  /** 保留扩展位；当前后端只按发布时间/置顶排序 */
  keyword?: string;
}
