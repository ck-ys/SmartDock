/**
 * 更新日志类型定义（三件套 2026-09-24）
 *
 * 雪花主键统一 string（后端 Long 经 JSON 序列化）。
 */

export interface ChangelogItem {
  /** new=新增 optimize=优化 fix=修复 */
  type: 'new' | 'optimize' | 'fix';
  text: string;
}

export interface ChangelogRow {
  logId: string | number;
  version: string;
  title: string;
  /** 结构化条目（后端已解析） */
  itemList: ChangelogItem[];
  /** 推送单位名单（后端已解析；空=未设置，草稿允许、发布前必须>=1家） */
  targetTenantList?: string[];
  /** 0=草稿 1=已发布 */
  status: string;
  publishTime?: string;
  createTime: string;
}

export interface ChangelogQuery extends PageQuery {
  status?: string;
  version?: string;
  title?: string;
}

export interface ChangelogSaveBody {
  logId?: string | number;
  version: string;
  title: string;
  itemList: ChangelogItem[];
  /** 0=草稿 1=已发布（0→1 即发布） */
  status?: string;
  /** 推送单位名单（编辑接口全量覆盖存量名单；发布前必须>=1家） */
  targetTenantList?: string[];
}
