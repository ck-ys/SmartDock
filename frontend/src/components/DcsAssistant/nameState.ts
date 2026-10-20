/**
 * 助手称呼全局态（快刀批 Q6，2026-10-20）。
 *
 * 单一真源=模块级 ref：默认「SmartDock 小助手」，由悬浮球首探 status 的 name 字段
 * 喂入（管理台改称呼→用户下次进页面生效）；空串/异常一律不动=保持现值或默认。
 * 组件 8 处硬编码称呼（欢迎语/帮助弹窗标题/aria-label/引导选择器等）收敛于此。
 */

import { ref } from 'vue';

export const DEFAULT_ASSISTANT_NAME = 'SmartDock 小助手';

export const assistantName = ref(DEFAULT_ASSISTANT_NAME);

/** 从 status/config 载荷喂称呼（非空字符串才覆盖；含 trim） */
export function applyAssistantName(raw: unknown): void {
  if (typeof raw === 'string' && raw.trim()) {
    assistantName.value = raw.trim();
  }
}
