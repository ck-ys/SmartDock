/**
 * 智能体工作台（批3 · V8 纸面进门）纯逻辑：
 * - 起手式三组场景的提示词模板（点一张直接灌进输入框）
 * - 最近会话按「今天 / 昨天 / 更早」分组（只做展示分组，不改后端口径）
 *
 * 单独抽文件是为了能被 vitest 直接测，组件里不重复实现。
 * 时间口径一律走 time.ts 的北京墙钟解析（lastMessageTime 无时区，裸 new Date 在非 +08
 * 浏览器会错组——2026-10-11 实锤，会话页已同源修复）。
 */
import type { AssistantConversation } from '@/api/dcs/assistant';
import { cnDayDiff, cnEpoch } from '@/components/DcsAssistant/time';
import { displayTitle } from '@/utils/assistantTitle';

export interface SuggestionChip {
  /** 卡片上显示的两个字场景名 */
  label: string;
  /** 点击后灌进输入框的完整提示词 */
  prompt: string;
}

export interface PresetTab {
  key: 'office' | 'dev' | 'design';
  label: string;
  chips: SuggestionChip[];
}

/** 三组场景：都是「用户会真的这么问」的句子，点了之后还要人补细节，不承诺具体能力。 */
export const PRESET_TABS: PresetTab[] = [
  {
    key: 'office',
    label: '办公',
    chips: [
      { label: '写周报', prompt: '帮我写本周周报：先问我这周做了哪些事，再按「进展 / 问题 / 下周计划」成稿。' },
      { label: '整理会议纪要', prompt: '把下面的会议记录整理成纪要，按「结论 / 待办（负责人+截止）/ 分歧」三段输出：\n' },
      { label: '起草通知', prompt: '帮我起草一份内部通知：说明事项、时间、影响范围和需要谁做什么，语气简洁。\n' },
      { label: '汇总反馈', prompt: '把这些用户反馈归类汇总，标出高频问题和最该先处理的三条：\n' },
      { label: '分析数据', prompt: '帮我分析这组数据的趋势和异常点，先用一句话给结论，再列依据：\n' },
      { label: '制作 PPT', prompt: '帮我把这份材料拆成 8 页 PPT 的提纲：每页一个观点，配一句要点。\n' }
    ]
  },
  {
    key: 'dev',
    label: '开发',
    chips: [
      { label: '解释代码', prompt: '解释这段代码在做什么，重点说清输入、输出和边界情况：\n' },
      { label: '写单测', prompt: '为下面的函数补单元测试，覆盖正常路径和边界值，用现有测试框架的写法：\n' },
      { label: '查接口', prompt: '帮我查一下这个接口的参数和返回结构，说明哪些字段可能为空：\n' },
      { label: '排错', prompt: '我遇到这个报错，先判断根因再给修复方案，不要直接改代码：\n' },
      { label: '写 SQL', prompt: '按下面的口径写一条查询语句，注意分页和空值处理：\n' },
      { label: '评审方案', prompt: '评审这个技术方案：列出风险、遗漏和更简单的替代做法。\n' }
    ]
  },
  {
    key: 'design',
    label: '设计',
    chips: [
      { label: '页面结构', prompt: '帮我把这个页面拆成区块，说明每块放什么、信息优先级怎么排：\n' },
      { label: '配色建议', prompt: '给这个界面配一套克制的配色：一个主色、一个强调色、三级灰，说明用在哪。\n' },
      { label: '文案润色', prompt: '把这段文案改得更短更清楚，保留原意，不要口号：\n' },
      { label: '交互检查', prompt: '检查这个交互流程：空态、加载、失败、无权限四种情况分别该显示什么？\n' },
      { label: '空态设计', prompt: '为这个列表设计空态：一句说明 + 一个主按钮，不要插画。\n' },
      { label: '对比方案', prompt: '给这个组件出两个方案，说清各自适合什么场景、代价是什么。\n' }
    ]
  }
];

export interface SessionGroup {
  key: '今天' | '昨天' | '更早';
  items: AssistantConversation[];
}

/**
 * 按最后消息时间分三组（北京自然日，与浏览器时区无关）；
 * 非法/缺失时间落「更早」，不丢行。now = epoch 毫秒。
 */
export function groupSessions(list: AssistantConversation[], now = Date.now()): SessionGroup[] {
  const buckets: Record<SessionGroup['key'], AssistantConversation[]> = { 今天: [], 昨天: [], 更早: [] };
  for (const c of list) {
    const diff = cnDayDiff(c.lastMessageTime, now);
    // 负数（时钟毛刺/未来串）按今天处理，与会话页 cnDayDiff 消费口径一致
    if (diff !== null && diff <= 0) {
      buckets['今天'].push(c);
    } else if (diff === 1) {
      buckets['昨天'].push(c);
    } else {
      buckets['更早'].push(c);
    }
  }
  return (['今天', '昨天', '更早'] as const).filter((k) => buckets[k].length > 0).map((k) => ({ key: k, items: buckets[k] }));
}

/**
 * 会话行的紧凑时间（北京墙钟）：今天 HH:mm / 昨天 / 更早 MM-DD。
 * 列表栏只有 33px 高，不放「昨天 10:00」这类长串。
 */
export function rowTime(t: string | null | undefined, now = Date.now()): string {
  const ms = cnEpoch(t);
  if (ms === null) return '';
  const diff = cnDayDiff(t, now);
  if (diff !== null && diff <= 0) {
    return new Date(ms + 8 * 3600_000).toISOString().slice(11, 16);
  }
  if (diff === 1) return '昨天';
  return new Date(ms + 8 * 3600_000).toISOString().slice(5, 10);
}

/** 会话行显示名：空标题兜底「新会话」；历史 JSON 残片走会话页同款 displayTitle 清洗。 */
export function sessionTitle(c: AssistantConversation): string {
  const t = (c.title ?? '').trim();
  return t ? displayTitle(c) : '新会话';
}
