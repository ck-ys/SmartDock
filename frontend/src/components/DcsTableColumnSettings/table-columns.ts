/**
 * 表格列显隐的本机存储（按页面一个 key）。
 *
 * 只持久化「被隐藏的列」：以后页面新增列时默认可见，不会被旧设置误藏。
 */
export interface TableColumnOption {
  key: string;
  label: string;
}

export const TABLE_COLUMN_STORAGE_PREFIX = 'dcs:table-columns:';

const storageKeyOf = (storageKey: string) => `${TABLE_COLUMN_STORAGE_PREFIX}${storageKey}`;

export const readHiddenColumns = (storageKey: string): string[] => {
  try {
    const raw = localStorage.getItem(storageKeyOf(storageKey));
    if (!raw) {
      return [];
    }
    const parsed: unknown = JSON.parse(raw);
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed.filter((key): key is string => typeof key === 'string');
  } catch {
    return [];
  }
};

export const writeHiddenColumns = (storageKey: string, hidden: string[]): void => {
  try {
    localStorage.setItem(storageKeyOf(storageKey), JSON.stringify(hidden));
  } catch {
    // 隐私模式不可写，不阻断交互
  }
};
