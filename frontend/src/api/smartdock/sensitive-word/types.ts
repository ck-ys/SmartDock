export interface SensitiveWordQuery extends PageQuery {
  wordText?: string;
  category?: string;
  action?: string;
  enabled?: string;
  builtin?: string;
  /** 生效场景筛选(2026-09-22): CHAT/FORM/FILE, CSV 包含匹配 */
  scene?: string;
}

export interface SensitiveWordVO extends BaseEntity {
  wordId: number | string;
  tenantId?: string;
  wordText: string;
  category?: string;
  action: 'MASK' | 'BLOCK' | 'REVIEW';
  enabled: string;
  builtin: string;
  remark?: string;
  /** 生效场景 CSV(2026-09-22): CHAT/FORM/FILE 逗号组合, 存量行默认 CHAT */
  scene?: string;
  createTime?: string;
  updateTime?: string;
  /** 近 7 天误报次数(收尾批 Q1: 列表接口 enrich, 词库调优参考) */
  fpCnt?: number;
  /** 近 7 天命中事件数(误报率分母, 列表接口 enrich) */
  hitCnt?: number;
  /** 疑似过严(误报率>=50% 且命中>=5 次, 2026-09-23 拍板): 橙色提示标数据源 */
  overstrict?: boolean;
}

export interface SensitiveWordForm extends BaseEntity {
  wordId?: number | string;
  tenantId?: string;
  wordText?: string;
  category?: string;
  action?: 'MASK' | 'BLOCK' | 'REVIEW';
  enabled?: string;
  builtin?: string;
  remark?: string;
  /** 生效场景 CSV(2026-09-22): CHAT/FORM/FILE 逗号组合 */
  scene?: string;
}
