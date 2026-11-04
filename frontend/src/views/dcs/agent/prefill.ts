/**
 * 工作台技能卡的悬停预填口径（拍板 2026-11-04 题一甲 / 题二甲 / 题四甲；
 * 同日用户反馈改成「灰色半透明幽灵字」——学 WorkBuddy 的占位层观感）。
 *
 * 纯函数放这里，页面只负责"什么时候调"——规则本身可单测（`prefill.test.ts`）：
 * 1. 句子 = 「用「技能名」帮我：」+ 技能自己的描述（不吃第二份手写文案，技能改了自动跟着变）；
 * 2. 悬停只写进 placeholder（灰字幽灵层）：真值仍为空、发不出去；
 * 3. 框里有字就不动（占位层本来就不显示，判空只为干净）；
 * 4. 点击 = 整段替换成真文本（学 ZCode：点了就是明确要用它）。
 */

/** 技能描述是 SKILL.md 原文，十个里有十个长成「用户说「A/B/C」时，真正的能力描述」。
 *  卡片正文与预填句只放后半句：前半句是触发词，读起来像没写完的句子。
 *  不是这套句式的技能原样透传，不改作者文案。 */
export function skillDesc(d: string): string {
  const s = (d || '').trim();
  const hit = s.match(/^用户说[「『][^」』]*[」』]\s*时[，,]\s*([\s\S]+)$/);
  return (hit && hit[1].trim()) || s;
}

/** 悬停/点击填进输入框的那整句 */
export function buildSkillDraft(name: string, desc: string): string {
  const d = skillDesc(desc);
  return d ? `用「${name}」帮我：${d}` : `用「${name}」帮我：`;
}

/** 悬停要不要显示灰字幽灵：框空着才显示（有字时占位层不显示，这里判空只为干净） */
export function shouldGhostOnHover(currentDraft: string): boolean {
  return !currentDraft.trim();
}

