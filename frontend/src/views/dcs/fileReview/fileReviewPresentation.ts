/**
 * 文件审核中心展示层口径（12b）——状态/类别/来源的中文词与 EP 标准色映射。
 *
 * 状态色自成一套（与任务状态色板区分）：PENDING=warning 待审核 / APPROVED=success
 * 已通过 / REJECTED=danger 已驳回；只允许 EP 四档标准 type，禁止自定义背景色。
 */

/** 审核状态 → {label, tagType} */
export const FILE_REVIEW_STATUS_META: Record<string, { label: string; tagType: 'success' | 'warning' | 'danger' | 'info' }> = {
  PENDING: { label: '待审核', tagType: 'warning' },
  APPROVED: { label: '已通过', tagType: 'success' },
  REJECTED: { label: '已驳回', tagType: 'danger' }
};

/** 文件类别 → {label, icon, colorVar}（icon 色走 EP 四档标准色，缩略图块用 light-9 底） */
export interface FileReviewCategoryMeta {
  label: string;
  icon: 'Picture' | 'VideoPlay' | 'Headset' | 'Document' | 'Tickets' | 'Files' | 'Box';
  /** EP 标准色变量名（primary/success/warning/danger/info） */
  tone: 'primary' | 'success' | 'warning' | 'danger' | 'info';
}

export const FILE_REVIEW_CATEGORY_META: Record<string, FileReviewCategoryMeta> = {
  IMAGE: { label: '图片', icon: 'Picture', tone: 'primary' },
  VIDEO: { label: '视频', icon: 'VideoPlay', tone: 'success' },
  AUDIO: { label: '音频', icon: 'Headset', tone: 'warning' },
  DOCUMENT: { label: '文档', icon: 'Document', tone: 'danger' },
  TEXT: { label: '文本', icon: 'Tickets', tone: 'info' },
  ARCHIVE: { label: '压缩包', icon: 'Files', tone: 'info' },
  OTHER: { label: '其他', icon: 'Box', tone: 'info' }
};

/** 设置页类别多选清单（固定七类顺序） */
export const FILE_REVIEW_CATEGORY_OPTIONS = Object.entries(FILE_REVIEW_CATEGORY_META).map(([value, meta]) => ({
  value,
  label: meta.label
}));

/** 上传来源 → 中文（仅展示埋点，不作免审依据） */
export const FILE_REVIEW_SOURCE_LABEL: Record<string, string> = {
  MISSION_ATTACHMENT: '任务附件',
  DYNAMIC_FIELD: '表单字段',
  AVATAR: '头像',
  CHAT: '聊天',
  RICH_TEXT: '富文本',
  OTHER: '其他'
};

/** 驳回理由预设四条快选（Q6-A：点击直接填入 textarea） */
export const FILE_REVIEW_PRESET_REASONS = ['内容不清晰，无法辨认', '包含敏感信息，不适宜公开', '文件损坏无法打开', '与任务要求无关'];

/** 文件大小格式化：字节 → B/KB/MB/GB（保留 1 位小数，<1KB 显示整数 B） */
export const formatFileSize = (size?: number | null): string => {
  if (size === null || size === undefined) return '—';
  if (size < 1024) return `${size} B`;
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`;
  if (size < 1024 * 1024 * 1024) return `${(size / 1024 / 1024).toFixed(1)} MB`;
  return `${(size / 1024 / 1024 / 1024).toFixed(2)} GB`;
};

/**
 * 安全检测「允许上传」大类 → 扩展名映射（弹窗改版 Q1=A）。
 * ⚠️ 与后端 FileReviewCategory.EXT_MAP 保持同步（单一事实源在后端，此处为前端映射副本，
 * 改动必须两边一起改）。OTHER 无固定扩展名不参与勾选。
 */
export const FILE_REVIEW_CATEGORY_EXTS: Record<string, string[]> = {
  IMAGE: ['jpg', 'jpeg', 'png', 'gif', 'bmp', 'webp', 'svg', 'ico'],
  VIDEO: ['mp4', 'avi', 'mov', 'wmv', 'flv', 'mkv', 'm4v'],
  AUDIO: ['mp3', 'wav', 'aac', 'flac', 'ogg', 'wma'],
  DOCUMENT: ['doc', 'docx', 'xls', 'xlsx', 'ppt', 'pptx', 'pdf', 'rtf'],
  TEXT: ['txt', 'md', 'log', 'json', 'xml', 'csv'],
  ARCHIVE: ['zip', 'rar', '7z', 'tar', 'gz']
};

/** 勾选大类卡下的小字提示（常用格式截断展示） */
export const categoryExtHint = (code: string): string => {
  const exts = FILE_REVIEW_CATEGORY_EXTS[code] || [];
  return exts.slice(0, 5).join(' ') + (exts.length > 5 ? ' …' : '');
};

/** 检测记录命中规则码 → 中文（Q4=A 检测记录页签） */
export const GUARD_RULE_LABEL: Record<string, string> = {
  EXTENSION_WHITELIST: '类型不允许',
  EXT_NOT_ALLOWED: '类型不允许',
  MAGIC_MISMATCH: '伪装文件',
  OFFICE_MACRO: 'VBA 宏',
  SVG_XSS: 'SVG 脚本',
  PDF_JAVASCRIPT: 'PDF 脚本',
  PARSE_ANOMALY: '解析异常'
};

/** 检测记录处置 → tag 元数据（DENIED 红 / REVIEWED 橙 / OBSERVED 灰） */
export const GUARD_DISPOSITION_META: Record<string, { label: string; tagType: 'danger' | 'warning' | 'info' }> = {
  DENIED: { label: '已拒传', tagType: 'danger' },
  REVIEWED: { label: '强制进审', tagType: 'warning' },
  OBSERVED: { label: '观察放行', tagType: 'info' }
};
