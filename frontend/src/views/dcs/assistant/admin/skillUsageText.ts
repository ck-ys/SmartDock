/**
 * 题2 案3（2026-10-31）：技能统计「最近一次」的展示文本。
 *
 * <p>后端正常返回 GMT+8 墙上时间（yyyy-MM-dd HH:mm:ss），字面量就是用户要看的时刻；
 * 带时区（Z / +08:00）或纯时间戳才交给 Date 换算成本机时区，避免差 8 小时。
 * 跨年补年份；没有记录或解析不了就说「从未」。</p>
 *
 * <p>抽成纯函数便于单测（dsflash P2）：当前年份可注入，测试不随系统时钟漂移。</p>
 */
export function skillLastUsedText(t?: string | null, now: Date = new Date()): string {
  if (!t) return '从未';
  const s = String(t).trim();
  const wall = s.match(/^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2})/);
  const zoned = /(?:Z|[+-]\d{2}:?\d{2})$/.test(s);
  if (wall && !zoned) {
    const [y, mo, d, h, mi] = wall.slice(1, 6).map(Number);
    if (!validWallClock(y, mo, d, h, mi)) return '从未';
    return skillTimeText(y, mo, d, h, mi, now);
  }
  // 只认标准 10 位秒 / 13 位毫秒；11-12 位多半是脏数据，交给 Date 判废（不猜单位）
  const d = /^\d{10}$/.test(s)
    ? new Date(Number(s) * 1000)
    : /^\d{13}$/.test(s)
      ? new Date(Number(s))
      : new Date(s);
  if (!isNaN(d.getTime())) {
    return skillTimeText(d.getFullYear(), d.getMonth() + 1, d.getDate(), d.getHours(), d.getMinutes(), now);
  }
  return '从未';
}

/** 墙上时间合法性（UTC 反算，避免浏览器夏令时空档误杀后端 GMT+8 时间） */
function validWallClock(y: number, mo: number, d: number, h: number, mi: number): boolean {
  if (mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59) return false;
  const probe = new Date(Date.UTC(y, mo - 1, d, h, mi));
  return (
    probe.getUTCFullYear() === y &&
    probe.getUTCMonth() === mo - 1 &&
    probe.getUTCDate() === d &&
    probe.getUTCHours() === h &&
    probe.getUTCMinutes() === mi
  );
}

function skillTimeText(y: number, mo: number, d: number, h: number, mi: number, now: Date): string {
  const p = (n: number) => String(n).padStart(2, '0');
  const md = `${p(mo)}-${p(d)} ${p(h)}:${p(mi)}`;
  return y === now.getFullYear() ? md : `${y}-${md}`;
}
