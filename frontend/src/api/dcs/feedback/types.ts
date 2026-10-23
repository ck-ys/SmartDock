/** 反馈类型：缺陷 / 体验 / 建议 / 其它 */
export type FeedbackType = 'BUG' | 'EXPERIENCE' | 'SUGGEST' | 'OTHER';

/** 反馈状态：待处理 / 处理中 / 已解决 / 已关闭 */
export type FeedbackStatus = 'PENDING' | 'PROCESSING' | 'RESOLVED' | 'CLOSED';

export interface FeedbackRow {
  feedbackId: string | number;
  title: string;
  content: string;
  feedbackType: FeedbackType;
  status: FeedbackStatus;
  ossIds?: string;
  ossIdList?: string[];
  contact?: string;
  contextJson?: string;
  handlerName?: string;
  handleNote?: string;
  handleTime?: string;
  createBy?: number;
  createByName?: string;
  createTime?: string;
}

export interface FeedbackQuery extends PageQuery {
  status?: string;
  feedbackType?: string;
  title?: string;
}

export interface FeedbackSubmit {
  title: string;
  content: string;
  feedbackType: FeedbackType;
  ossIds?: string;
  contact?: string;
  contextJson?: string;
}

export interface FeedbackHandle {
  feedbackId: string | number;
  status: Exclude<FeedbackStatus, 'PENDING'>;
  handleNote: string;
}
