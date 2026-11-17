/**
 * 批 A Q3（2026-11-14 拍板案1）：定时任务模板四卡——列表页点一下，带真实提示词预填新建页。
 * 规矩：每条 prompt 只承诺自动化真实具备的能力（只读查询 + 汇总 + 通知/会话），
 * 不写「同步到 xxx」「发给谁」这类没有的能力；名字照板：写周报/查逾期/每日摘要/文档同步。
 */

export interface AutomationTemplate {
  key: string;
  title: string;
  desc: string;
  prompt: string;
  scheduleType: 'DAILY' | 'WEEKLY';
  scheduleTime: string;
  /** 1=一 … 7=日（仅 WEEKLY 用） */
  weekdays: number[];
}

export const AUTOMATION_TEMPLATES: AutomationTemplate[] = [
  {
    key: 'weekly',
    title: '写周报',
    desc: '每周五 17:00 汇总本周任务完成与延期，产出周报草稿',
    prompt: '把这周我名下任务的完成情况汇总成一段周报草稿：完成了什么、延期了什么、下周要盯什么。数据以工具返回为准，查不到的直说。',
    scheduleType: 'WEEKLY',
    scheduleTime: '17:00',
    weekdays: [5]
  },
  {
    key: 'overdue',
    title: '查逾期',
    desc: '每天 09:00 列出过了截止时间还没完成的任务',
    prompt: '把已经过了截止时间还没完成的任务列出来，按超期天数排序，标出每条的责任人；没有逾期的也明说一句「本周无逾期」。',
    scheduleType: 'DAILY',
    scheduleTime: '09:00',
    weekdays: []
  },
  {
    key: 'digest',
    title: '每日摘要',
    desc: '每天 08:00 一段晨间摘要，今天到期和我负责的重点',
    prompt: '把今天到期和我负责的任务汇总成一段晨间摘要，两三句话说清今天的重点和要注意的截止时间。',
    scheduleType: 'DAILY',
    scheduleTime: '08:00',
    weekdays: []
  },
  {
    key: 'docsync',
    title: '文档同步',
    desc: '每周一 09:00 把上周变化整理成可归档的简报文档',
    prompt: '把上周的任务进展整理成一份简报文档（Markdown）：按任务分组、写清状态变化，可直接粘贴归档。',
    scheduleType: 'WEEKLY',
    scheduleTime: '09:00',
    weekdays: [1]
  }
];

export function templateByKey(key: unknown): AutomationTemplate | null {
  const k = String(key ?? '');
  return AUTOMATION_TEMPLATES.find((t) => t.key === k) ?? null;
}
