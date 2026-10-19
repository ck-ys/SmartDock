/**
 * AI 错误信息人话映射（业务反馈批1015c Q9）：AgentScope/Reactor 库层与上游厂商的
 * 英文错误原文统一在此归类成中文短语，原文保留进 detail 供「查看技术详情」折叠。
 * 聊天错误卡（useAssistant.onRunError）与 AI 用量管理页错误列共用本表——新增错误
 * 形态先归进现有类，避免两处各自维护漂移。
 */
export interface HumanizedAiError {
  /** 短语口径：列表/卡片直接显示 */
  title: string;
  /** 聊天错误卡完整句（带重试指引） */
  chat: string;
  /** 原始英文原文：折叠/tooltip 用，不裸出 */
  detail: string;
}

const RULES: Array<{ re: RegExp; title: string }> = [
  { re: /rate ?limi|too many requests|429|quota exceeded/i, title: '请求太频繁或额度受限，稍等片刻再试' },
  { re: /connect|connection refused|network is unreachable|ECONN|ENOTFOUND|socket hang up/i, title: '网络连不上服务，请检查网络后重试' },
  { re: /insufficient[^\n]*(balance|credit|funds)|余额不足/i, title: '余额或积分不足，请充值或订阅套餐' },
  { re: /unauthorized|invalid[^\n]*api[^\n]*key|authentication|401/i, title: '密钥或登录已失效，请联系管理员' },
  { re: /forbidden|not enough permission|403/i, title: '没有权限执行此操作' },
  { re: /model not found|no such model|404/i, title: '请求的模型或资源不存在' },
  { re: /overloaded|server error|internal server error|bad gateway|service unavailable|50[023]/i, title: 'AI 服务暂时不可用，稍后再试' }
];

/** 未知/未归类错误统一口径：短语 + 原文进 detail（不裸出吓用户，也不吞线索） */
export function humanizeAiError(msg?: string, code?: string): HumanizedAiError {
  const raw = (msg || '').trim();
  // 判定顺序（三审 dspro P2 复核）：code 精确判定最优先——TIMEOUT_ERROR 是超时专属码先判；
  // 宽文本 timed out 刻意不认——"Connection is not available, request timed out" 这类
  // 连接池/SQL 故障（code=INTERNAL_ERROR）若被抢先报成超时，会把服务故障误导成网络问题
  if (code === 'TIMEOUT_ERROR') {
    const title = '回答超时了，可能是网络或服务繁忙';
    return { title, chat: `${title}。点「重试」再试一次；反复出现建议换个模型或稍后再用。`, detail: raw };
  }
  // 库层流式固定文案的窄正则兜底（无 code 时唯一超时依据）
  if (/Stream timeout|transport error during streaming|Did not observe any item/i.test(raw)) {
    const title = '回答超时了，可能是网络或服务繁忙';
    return { title, chat: `${title}。点「重试」再试一次；反复出现建议换个模型或稍后再用。`, detail: raw };
  }
  for (const r of RULES) {
    if (r.re.test(raw)) {
      return { title: r.title, chat: `${r.title}。可以点「重试」再试一次。`, detail: raw };
    }
  }
  // INTERNAL_ERROR 是框架"其余一切异常"的兜底码（NPE/SQL 等也算），不能报成网络问题
  if (code === 'INTERNAL_ERROR') {
    const title = '服务这边出了点问题，已记录';
    return { title, chat: `${title}。可以先点「重试」；仍不行请联系管理员。`, detail: raw };
  }
  if (!raw) {
    const title = '服务开小差了，请稍后再试';
    return { title, chat: title, detail: '' };
  }
  // 未知分支的透传契约（useAssistant.test 既有口径）：短消息/含中文的错误原文对用户有意义
  // （工具失败的人话文案、契约错误原文等）——原样直出；只有长英文串（库层堆栈/厂商原文）
  // 才收进兜底短语 + 原文折叠，兼顾"不吓人"与"不吞线索"
  if (raw.length <= 80 || /[一-龥]/.test(raw)) {
    return { title: raw, chat: raw, detail: '' };
  }
  return { title: '服务异常（详情见技术信息）', chat: '服务开小差了，请稍后再试', detail: raw };
}
