/**
 * 工具结果 JSON 解包（批C-2）：后端部分工具 content 为双重编码 JSON 字符串
 * （parse 一次得 string，E2E 实证 resultFull 以 `"{\"asOf\"...` 开头），两层 parse 兼容。
 *
 * <p>雪花 ID 精度防线（P0，E2E 实证）：mission_id 等 19 位整数走 JSON.parse 会丢成
 * double（…497969→…5108000），页面联动会点 A 任务打开 B 任务——parse 前把 15 位以上
 * 数字加引号保真（坑清单「Number(userId)雪花截尾」同族）。</p>
 *
 * <p>三路尝试：①单层形态先 shield 再 parse（此时插引号安全）；
 * ②shield 若破坏了双层外串语法（数字位于字符串字面量内）则 fallback 纯 parse 出内层
 * 字符串后再 shield+parse（内层数字原本就安全躺在字符串里，第一跳不损失精度）；
 * ③091 原串兜底：纯 parse 直接得对象也返回——shield 在字符串字面量内误插引号导致
 * ①崩时，原串本就是合法 JSON（数字躺在字符串里无精度风险），此前这半步缺失整体返
 * null，工具结果不渲染（触发形态=备注文本含「冒号+15位以上连续数字+`,}/]`」）。</p>
 */
export interface ToolRowsShape {
  asOf?: string;
  total?: number;
  rows?: unknown[];
  truncated?: boolean;
}

/** 值位置 15 位以上整数加引号（工具结果里计数类字段不可能达 15 位，安全） */
const BIG_NUM = /(:\s*)(\d{15,})\s*([,}\]])/g;

function shieldBigIds(s: string): string {
  return s.replace(BIG_NUM, '$1"$2"$3');
}

function tryParse(s: string): unknown {
  try {
    return JSON.parse(s);
  } catch {
    return null;
  }
}

export function parseToolJson(raw: string): ToolRowsShape | null {
  const single = tryParse(shieldBigIds(raw));
  if (single && typeof single === 'object') {
    return single as ToolRowsShape;
  }
  const outer = tryParse(raw);
  if (outer && typeof outer === 'object') {
    return outer as ToolRowsShape;
  }
  if (typeof outer === 'string') {
    const inner = tryParse(shieldBigIds(outer));
    if (inner && typeof inner === 'object') {
      return inner as ToolRowsShape;
    }
  }
  return null;
}

/**
 * 工具入参 JSON → 中文键值摘要（批I 零技术词：步骤区/来源条旧把
 * `{"mission_id":209...}` 英文键名直出）。已知键映射中文；非 JSON/数组原样
 * 返回（截断由调用方做）。只取前 3 个键值，防长参数刷屏。
 */
const ARG_KEY_CN: Record<string, string> = {
  mission_id: '任务',
  mission_name: '任务名',
  topic_id: '科目',
  keyword: '关键词',
  page: '页码',
  user_id: '用户',
  target_user_ids: '对象',
  message: '留言',
  attachment_id: '附件',
  days: '天数',
  dept: '部门',
  college: '部门'
};

export function summarizeArgs(raw: string): string {
  const s = raw.replace(/\s+/g, ' ').trim();
  if (!s.startsWith('{')) {
    return s;
  }
  // 雪花 ID 同款保真（19 位 mission_id 裸数字 parse 会丢精度，显示也要准）
  const parsed = tryParse(shieldBigIds(s));
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return s;
  }
  const parts: string[] = [];
  for (const [k, v] of Object.entries(parsed as Record<string, unknown>).slice(0, 3)) {
    const label = ARG_KEY_CN[k] ?? k;
    const val = v === null || v === undefined ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v);
    parts.push(`${label} ${val.length > 24 ? val.slice(0, 24) + '…' : val}`);
  }
  return parts.join('，');
}
