export interface DcsAuditLogVO {
  logId: string;
  missionId?: string;
  missionName?: string;
  traceId?: string;
  detailId?: string;
  topicId?: string;
  // 当前接口的历史小用户 ID 仍可能以 JSON number 返回；业务雪花主键统一使用 string。
  subjectUserId?: string | number;
  submissionVersion?: number;
  reviewerUserId?: string | number;
  contextJson?: string;
  action: string;
  actionLabel?: string;
  fromStatus?: string;
  toStatus?: string;
  operatorId?: string | number;
  operator?: string;
  // #4(2026-09-10 业务反馈): 被操作人=行级数据主体优先, 否则责任节点当前责任人(service 填充)
  targetUserId?: string | number;
  targetUserName?: string;
  remark?: string;
  createTime?: string;
}

export interface DcsAuditLogQuery extends PageQuery {
  missionId?: string;
  missionName?: string;
  operatorId?: string | number;
  operator?: string;
  action?: string;
  /** 2026-09-17 八项反馈Q3: 被操作人/备注模糊搜索 */
  targetName?: string;
  remark?: string;
  params?: Record<string, unknown>;
}
