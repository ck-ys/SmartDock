/**
 * 助手域 toast（PROTO PARITY 批B → 批I-3 三档，原型 #toasts）：白卡描边、
 * 顶部居中 top:58px、2600ms 自清。kind 三档：err=⚠ / ok=✅ / info=ℹ（默认，
 * 原型 toast() 无类名即 info——图标在渲染层按 EMO2IC 换 Lucide）。
 * 模块级单例——抽屉/全屏/会话页任何宿主的 onToast 都汇聚到一份容器（AssistantToasts.vue 渲染）。
 */
import { ref } from 'vue';

export interface AssistantToast {
  id: number;
  text: string;
  kind: 'ok' | 'err' | 'info';
}

const toasts = ref<AssistantToast[]>([]);
let seq = 0;

export function pushToast(text: string, kind: 'ok' | 'err' | 'info' = 'info') {
  const id = ++seq;
  toasts.value.push({ id, text, kind });
  setTimeout(() => {
    toasts.value = toasts.value.filter((t) => t.id !== id);
  }, 2600);
}

export function useAssistantToasts() {
  return { toasts, pushToast };
}
