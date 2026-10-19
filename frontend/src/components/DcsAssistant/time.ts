/**
 * 批A Q12（拍板 2026-10-11）：会话列表共用时间工具。
 *
 * 背景（2026-10-11 浏览器实测定案）：会话接口的 lastMessageTime 是「北京墙钟、空格分隔、
 * 无时区」串（如 `2026-10-11 21:55:02`，后端 -Duser.timezone=GMT+8 序列化 LocalDateTime）。
 * 旧实现 new Date(str) 按浏览器本地时区解析——非中国时区浏览器（本开发机 GMT-4）把它
 * 解析成未来时间，diff 为负，`diff < 60_000` 恒真 → 恒「刚刚」（业务 #10b 实锤）。
 *
 * 口径（拍板 Q12-A）：
 * - 无偏移串一律按北京墙钟（+08:00）解析求 epoch；带 Z/±offset 的串原样解析。
 * - 显示墙钟一律北京时（与解析口径自洽：无偏移串显示值=串本身墙钟），跨时区浏览器一致。
 * - diff 负数（时钟毛刺/未来时间）钳 0；相对阈值（60s/60m）与「今天/昨天」分组阈值
 *   （北京自然日零点）分开定义（ZCode 经验，两套阈值混用是联动 bug 温床）。
 */
const CN_TZ = '+08:00';
const CN_TZ_OFFSET_MS = 8 * 3600_000;

/** 无偏移串（北京墙钟）或带偏移串 → epoch 毫秒；无效返回 null。
 *  批A 审查回修 P1-1/P1-2：relTime 与活跃绿点/今天昨天分组共用此解析，单一真源，
 *  杜绝「同一串在相邻行两种解析」（绿点误亮、分组与显示打架） */
export function cnEpoch(t?: string | number | null): number | null {
  if (t === null || t === undefined || t === '') {
    return null;
  }
  let ms: number;
  if (typeof t === 'number') {
    ms = t;
  } else {
    const s = t.trim();
    if (s === '') {
      return null;
    }
    // 统一分隔符后：带时区标记原样解析；无标记按北京墙钟补 +08:00（截到毫秒位）
    const iso = s.includes(' ') ? s.replace(' ', 'T') : s;
    ms = /[Zz]|[+-]\d{2}:?\d{2}$/.test(iso) ? Date.parse(iso) : Date.parse(iso.slice(0, 23) + CN_TZ);
  }
  return Number.isNaN(ms) ? null : ms;
}

/** epoch/时间串 → 北京自然日键 'YYYY-MM-DD'（分组「今天/昨天/更早」统一日界，与浏览器时区无关） */
export function cnDayKey(t?: string | number | null, now: number = Date.now()): string | null {
  const ms = cnEpoch(t);
  return ms === null ? null : new Date(ms + CN_TZ_OFFSET_MS).toISOString().slice(0, 10);
}

/** 北京自然日差：now 减 item 的天数（0=同一天即今天，1=昨天；负=未来/时钟毛刺按今天处理） */
export function cnDayDiff(t?: string | number | null, now: number = Date.now()): number | null {
  const day = cnDayKey(t, now);
  if (day === null) {
    return null;
  }
  return Math.round((Date.parse(cnDayKey(now)!) - Date.parse(day)) / 86400_000);
}

export function relTime(t?: string | number | null, now: number = Date.now()): string {
  const ms = cnEpoch(t);
  if (ms === null) {
    return '';
  }
  const diff = Math.max(0, now - ms);
  if (diff < 60_000) {
    return '刚刚';
  }
  if (diff < 3600_000) {
    return `${Math.floor(diff / 60_000)} 分钟前`;
  }
  const cn = new Date(ms + CN_TZ_OFFSET_MS).toISOString(); // 北京墙钟 ISO
  const hm = cn.slice(11, 16);
  const nowCnDay = new Date(now + CN_TZ_OFFSET_MS).toISOString().slice(0, 10);
  const dayDiff = Math.round((Date.parse(cn.slice(0, 10)) - Date.parse(nowCnDay)) / 86400_000);
  if (dayDiff === 0) {
    return hm;
  }
  if (dayDiff === -1) {
    return `昨天 ${hm}`;
  }
  return cn.slice(5, 10); // MM-DD
}
