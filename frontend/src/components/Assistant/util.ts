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

/**
 * 待确认停靠区定位（抽屉与会话页两个宿主并存，不能按 id 取唯一元素）。
 * 几何可见性判定兜底屏外/缓存 DOM 的边角（keep-alive 移出文档树的宿主天然不可达）。
 */
export function findVisibleHitlDock(): HTMLElement | null {
  const list = Array.from(document.querySelectorAll<HTMLElement>('.dcs-hitl-dock'));
  return (
    list.find((el) => {
      const r = el.getBoundingClientRect();
      return r.width > 0 && r.right > 0 && r.left < window.innerWidth && r.bottom > 0;
    }) ?? null
  );
}
