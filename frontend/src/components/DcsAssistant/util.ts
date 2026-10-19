/**
 * 助手组件小件公共库（批I 重复小件收口：解包 5 处/ISO 时间格式化统一）。
 * 只收确实多处重复且语义一致的写法，不扩面。
 */

/** request 返回剥壳（拦截器成功路径返回 R 包装 {code,msg,data}——data ?? 本体双兼容） */
export function unwrap<T>(r: unknown): T {
  const d = (r as { data?: unknown })?.data;
  return (d !== undefined ? d : r) as T;
}

/** ISO 时间 → "YYYY-MM-DD HH:mm"（非法/空返回 '—'；DataCard/会话页同款口径） */
export function fmtIsoTime(iso: unknown): string {
  const s = String(iso ?? '');
  if (!s) {
    return '—';
  }
  const m = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/.exec(s);
  return m ? `${m[1]} ${m[2]}` : s.slice(0, 16);
}
