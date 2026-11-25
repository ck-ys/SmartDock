/**
 * 钱包流水「按日期分组」的纯函数（2026-11-25 钱包批 Q3 = 案 1）。
 *
 * 病根：原来是一条平铺的长表，对账时得自己数「11-24 那天花了多少」。
 * 按天分组 + 每天一个小计，才是账本该有的样子。
 *
 * 两条口径：
 * - **分组键取后端时间的「日期部分」**（前 10 位），不 new Date() 再转本地时区——
 *   后端时间已按 GMT+8 落库，再转一次会在跨时区机器上把凌晨的行分到前一天。
 * - **组内保持后端给的顺序**（create_time 倒序、同日按主键倒序），不重排：
 *   重排会让「同一天被切到两页」时顺序与翻页不一致。
 */

export interface LedgerLike {
  createTime?: string | null;
  direction?: string | null;
  amount?: number | string | null;
}

export interface LedgerDay<T extends LedgerLike> {
  /** 分组键：yyyy-MM-dd */
  day: string;
  /** 展示用：11-25 */
  label: string;
  /** 展示用：周二 */
  weekday: string;
  /** 当天收入小计 */
  totalIn: number;
  /** 当天支出小计 */
  totalOut: number;
  /** 当天的行（保持入参顺序） */
  rows: T[];
}

const WEEKDAYS = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'];

/** 取日期部分（yyyy-MM-dd）；取不到返回空串，由调用方归到「未知日期」组 */
export const dayKeyOf = (createTime?: string | null): string => {
  if (!createTime) return '';
  const s = String(createTime).trim();
  // 兼容 "2026-11-25 21:00:00" 与 "2026-11-25T21:00:00.000+08:00"
  const m = s.match(/^(\d{4})-(\d{2})-(\d{2})/);
  return m ? `${m[1]}-${m[2]}-${m[3]}` : '';
};

const weekdayOf = (day: string): string => {
  const m = day.match(/^(\d{4})-(\d{2})-(\d{2})$/);
  if (!m) return '';
  // 用 UTC 构造再取星期，避开本地时区把日期推前一天
  const d = new Date(Date.UTC(Number(m[1]), Number(m[2]) - 1, Number(m[3])));
  return WEEKDAYS[d.getUTCDay()] ?? '';
};

const num = (v: unknown): number => {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
};

/**
 * 按天分组并算每天的收入/支出小计。
 *
 * 返回顺序 = 入参里「首次出现」的顺序（后端已按时间倒序，所以就是日期倒序）。
 * 没有日期的行归到最后一组（key 为空串），不丢弃——丢掉会让「合计对不上」。
 */
export function groupByDay<T extends LedgerLike>(rows: T[]): LedgerDay<T>[] {
  const map = new Map<string, LedgerDay<T>>();
  for (const row of rows || []) {
    const key = dayKeyOf(row.createTime);
    let bucket = map.get(key);
    if (!bucket) {
      bucket = {
        day: key,
        label: key ? key.slice(5) : '日期未知',
        weekday: weekdayOf(key),
        totalIn: 0,
        totalOut: 0,
        rows: []
      };
      map.set(key, bucket);
    }
    bucket.rows.push(row);
    const amount = num(row.amount);
    if (row.direction === 'IN') bucket.totalIn += amount;
    else if (row.direction === 'OUT') bucket.totalOut += amount;
  }
  return [...map.values()];
}

/**
 * 当前页各天小计的合计（用于「本页合计」提示，与全量合计区分）。
 * 全量合计必须走后端 `/summary`，前端累加只能代表当前页。
 */
export function pageTotals<T extends LedgerLike>(rows: T[]): { totalIn: number; totalOut: number; count: number } {
  let totalIn = 0;
  let totalOut = 0;
  for (const row of rows || []) {
    const amount = num(row.amount);
    if (row.direction === 'IN') totalIn += amount;
    else if (row.direction === 'OUT') totalOut += amount;
  }
  return { totalIn, totalOut, count: (rows || []).length };
}
