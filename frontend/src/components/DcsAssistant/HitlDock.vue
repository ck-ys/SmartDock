<template>
  <div
    v-if="ask && ask.cardState === 'pending'"
    ref="rootEl"
    class="dcs-hitl-dock"
    :class="{ full }"
    role="region"
    aria-label="待确认操作"
    tabindex="-1"
    @keydown="onKeydown"
  >
    <div class="dock-scroll">
      <form-card
        v-if="ask.toolName === 'act_create_mission'"
        :ask="ask"
        @resolve="(a, r, e) => emit('resolve', a, r, e)"
        @revise="(m) => emit('revise', m)"
      />
      <template-card
        v-else-if="ask.toolName === 'act_create_template'"
        :ask="ask"
        @resolve="(a, r, e) => emit('resolve', a, r, e)"
        @revise="(m) => emit('revise', m)"
      />
      <confirm-card v-else :ask="ask" @resolve="(a, r, e) => emit('resolve', a, r, e)" />
    </div>
    <div class="dock-foot">
      <span class="dock-note">待确认期间暂停输入，确认或取消后自动恢复（草稿保留）</span>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * HITL 确认停靠区（对齐母仓批18 Q21-A，ZCode bottom dock 同构）。
 *
 * 待确认卡不再渲染在答案流末尾，而是占据输入框的位置；宿主在 pending 期间把
 * Composer 收起（保持挂载，草稿不丢）。卡片内容完全复用 FormCard/TemplateCard/
 * ConfirmCard，本组件只负责摆放与键盘语义（Esc=取消、Enter=确认、↑↓ 在按钮间移动）。
 *
 * 可访问性：根节点可聚焦；卡内输入框获得焦点时 Esc 仍取消、Enter 不劫持。
 */
import type { HitlAsk, HitlEdits } from './useAssistant';
import FormCard from './FormCard.vue';
import TemplateCard from './TemplateCard.vue';
import ConfirmCard from './ConfirmCard.vue';

const props = defineProps<{ ask: HitlAsk | null; full?: boolean }>();
const emit = defineEmits<{
  resolve: [approved: boolean, remember?: boolean, edits?: HitlEdits];
  revise: [message: string];
}>();

const rootEl = ref<HTMLElement | null>(null);

/** 卡到达/切换时把焦点送进停靠区（ConfirmCard 也会自聚焦，二者都在 dock 内）；
 *  屏外实例（抽屉收起时残留的宿主）不抢焦点 */
watch(
  () => [props.ask?.toolName, props.ask?.cardState] as const,
  ([, state]) => {
    if (state === 'pending') {
      nextTick(() => {
        const el = rootEl.value;
        if (!el) return;
        const r = el.getBoundingClientRect();
        if (r.width === 0 || r.right <= 0 || r.left >= window.innerWidth) return;
        el.focus({ preventScroll: true });
      });
    }
  },
  { immediate: true }
);

function clickAction(selector: string) {
  const btn = rootEl.value?.querySelector<HTMLButtonElement>(selector);
  if (btn && !btn.disabled) {
    btn.click();
    return true;
  }
  return false;
}

function onKeydown(e: KeyboardEvent) {
  if (!props.ask || props.ask.cardState !== 'pending') return;
  const target = e.target as HTMLElement | null;
  const tag = target?.tagName ?? '';
  const inField = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT';

  if (e.key === 'Escape') {
    e.preventDefault();
    e.stopPropagation();
    clickAction('.cf-cancel');
    return;
  }
  // 输入框里的 Enter 属于用户输入（附言/备注），不劫持；
  // 焦点在按钮上时 Enter 走原生激活——否则会代点「确认执行」并吞掉焦点按钮的本意
  if (inField || tag === 'BUTTON') return;

  if (e.key === 'Enter') {
    if (clickAction('.cf-go')) {
      e.preventDefault();
    }
    return;
  }
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    const buttons = Array.from(rootEl.value?.querySelectorAll<HTMLButtonElement>('button:not([disabled])') ?? []);
    if (buttons.length === 0) return;
    e.preventDefault();
    const current = buttons.indexOf(document.activeElement as HTMLButtonElement);
    const dir = e.key === 'ArrowDown' ? 1 : -1;
    const next = current < 0 ? (dir > 0 ? 0 : buttons.length - 1) : (current + dir + buttons.length) % buttons.length;
    buttons[next]?.focus();
  }
}
</script>

<style lang="scss" scoped>
.dcs-hitl-dock {
  flex: none;
  /* 与 Composer 同宽：会话页 .main 是 align-items:center 的列容器，不显式 width:100%
     会收缩成内容宽（1024 下只剩 290px，表单被挤扁） */
  width: 100%;
  padding: 8px 14px 6px;
  background: transparent;
  max-height: min(46vh, 460px);
  display: flex;
  flex-direction: column;
  gap: 4px;

  &:focus-visible {
    outline: 2px solid var(--el-color-primary-light-5);
    outline-offset: -2px;
  }

  &.full {
    padding: 8px 24px 6px;
    background: var(--el-fill-color-light, #f5f7fa);
  }

  @media (max-width: 992px) {
    max-height: min(52vh, 440px);
    padding: 6px 10px 4px;
  }
}
.dock-scroll {
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  /* 卡自带圆角与边框；滚动容器只负责高度，不额外加框 */
  display: flex;
  flex-direction: column;
}
/* 名单很长时确认/取消吸在停靠区底缘——内部滚动的永远是卡身，按钮永远可达 */
.dock-scroll :deep(.dcs-confirm > .cf) {
  position: sticky;
  bottom: 0;
  z-index: 1;
  background: inherit;
  border-radius: 0 0 9px 9px;
}
.dock-foot {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 11px;
  color: var(--dcs-text-placeholder);
  padding: 0 2px;
  line-height: 1.4;
}
.dock-note {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
/* 全屏/会话页与输入框同列（Composer.full 的 .box 同宽同起点） */
.dcs-hitl-dock.full .dock-scroll,
.dcs-hitl-dock.full .dock-foot {
  width: 100%;
  max-width: var(--dcs-col-max, 900px);
  margin: 0 auto 0 0;
}
</style>
