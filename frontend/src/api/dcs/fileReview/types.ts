/**
 * 文件审核中心类型定义（12b）
 *
 * 雪花主键统一 string（后端 Long 经 JSON 序列化，历史小 ID 可能是 number，兼容联合类型）。
 */

export interface FileReviewRow {
  reviewId: string | number;
  ossId: string | number;
  fileName: string;
  /** IMAGE/VIDEO/AUDIO/DOCUMENT/TEXT/ARCHIVE/OTHER */
  fileCategory: string;
  fileSize: number;
  /** MISSION_ATTACHMENT/DYNAMIC_FIELD/AVATAR/CHAT/RICH_TEXT/OTHER（仅展示埋点） */
  bizSource: string;
  missionId?: string | number;
  uploaderId: string | number;
  uploaderName: string;
  /** PENDING/APPROVED/REJECTED（终态） */
  status: string;
  reviewerId?: string | number;
  reviewerName?: string;
  reviewTime?: string;
  rejectReason?: string;
  /** file-guard 风险标签（宏/PDF JS/SVG 脚本命中说明）；空=常规审核单 */
  guardReason?: string;
  createTime: string;
}

export interface FileReviewQuery extends PageQuery {
  /** 必传：页签决定（PENDING=待审 / APPROVED+REJECTED=已审） */
  status: string;
  category?: string;
  bizSource?: string;
  uploaderName?: string;
  params?: Record<string, unknown>;
}

export interface FileReviewApproveResult {
  success: (string | number)[];
  conflict: (string | number)[];
}

export interface FileReviewConfig {
  /** '1'=启用 / '0'=关闭 */
  enabled: string;
  categories: string;
  pendingCount: number;
  reviewerCount: number;
  /** file-guard 检测模式：OFF/OBSERVE/ENFORCE（展示层缺省 OBSERVE） */
  guardMode: string;
  /** file-guard 白名单逗号串（空=七类并集默认） */
  guardWhitelist: string;
  /** file-guard 深扫大小上限 MB */
  guardDeepScanMaxMb: number;
}

export interface FileReviewConfigSaveBody {
  enabled: boolean;
  categories: string[];
  /** OFF/OBSERVE/ENFORCE；后端缺省 OBSERVE */
  guardMode?: string;
  /** 小写扩展名逗号串；空=默认白名单 */
  guardWhitelist?: string;
  /** 1-500；后端缺省 50 */
  guardDeepScanMaxMb?: number;
}

/** 安全检测近 7 天命中概览（设置弹窗顶部概览条，Q4=A） */
export interface GuardStats {
  /** 近 7 天命中总数 */
  total: number;
  /** 其中已拒传（ENFORCE DENY） */
  denied: number;
  /** 其中强制进审（ENFORCE REVIEW） */
  reviewed: number;
  /** 其中观察放行（OBSERVE 命中留痕） */
  observed: number;
}

/** 安全检测命中记录行（「检测记录」页签，Q4=A） */
export interface GuardLogRow {
  logId: string;
  userId?: string;
  userName?: string;
  fileName?: string;
  fileSize?: number;
  ruleCode?: string;
  detail?: string;
  /** 命中时模式：OFF/OBSERVE/ENFORCE */
  mode?: string;
  /** DENIED 已拒传 / REVIEWED 强制进审 / OBSERVED 观察放行 */
  disposition?: string;
  createTime?: string;
}
