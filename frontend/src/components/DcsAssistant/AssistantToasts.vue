<template>
  <teleport to="body">
    <div class="dcs-atoasts" aria-live="polite">
      <transition-group name="atoast">
        <div v-for="t in toasts" :key="t.id" class="atoast" :class="t.kind">
          <span class="at-ic"><IconSpan :name="t.kind === 'ok' ? 'check-circle' : t.kind === 'err' ? 'warn' : 'info'" /></span>
          <!-- eslint-disable-next-line vue/no-v-html —— 源串保持 emoji（原型口径），渲染层按 EMO2IC 换 Lucide -->
          <span v-html="emoToSvgHtml(t.text)"></span>
        </div>
      </transition-group>
    </div>
  </teleport>
</template>

<script setup lang="ts">
/**
 * 助手域 toast 容器（批B）：固定顶部居中，2600ms 自清（toastStore 控制）。
 * z=1500：高于抽屉(960)与全屏面板(1200)、低于 EP 弹层(2000+)——
 * 批J-2 修：旧值 990 在全屏形态被面板(1200)整体遮住。
 */
import { useAssistantToasts } from './toastStore';
import IconSpan from './IconSpan.vue';
import { emoToSvgHtml } from './icons';

const { toasts } = useAssistantToasts();
</script>

<style lang="scss" scoped>
.dcs-atoasts {
  position: fixed;
  top: 58px; /* 原型 #toasts 口径：避开顶栏 */
  left: 50%;
  transform: translateX(-50%);
  z-index: 1500;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px; /* 原型 #toasts gap:8 */
  pointer-events: none;
}
.atoast {
  display: flex;
  align-items: center;
  gap: 8px;
  max-width: 420px;
  padding: 9px 16px;
  border-radius: 8px;
  /* 批I-3：原型 .toast 白卡描边形态（原深底胶囊弃用）；批J-2 对齐原型整条文字按态染色 */
  background: var(--el-bg-color);
  border: 1px solid var(--dcs-border-color-light);
  box-shadow: var(--el-box-shadow-light);
  color: var(--dcs-text-primary);
  font-size: 13px;

  .at-ic {
    flex: none;
    display: inline-flex;
  }
  &.ok {
    color: var(--dcs-color-success);
  }
  &.err {
    color: var(--dcs-color-danger);
  }
  &.info {
    color: var(--el-color-primary);
  }
  &.ok .at-ic,
  &.err .at-ic,
  &.info .at-ic {
    color: currentColor;
  }
}
.atoast-enter-active,
.atoast-leave-active {
  transition:
    opacity 0.2s,
    transform 0.2s;
}
.atoast-enter-from,
.atoast-leave-to {
  opacity: 0;
  /* 原型 pop：上浮缩放入场（translateY(8px)→0） */
  transform: translateY(8px) scale(0.96);
}
@media (prefers-reduced-motion: reduce) {
  .atoast-enter-active,
  .atoast-leave-active {
    transition: none;
  }
}
</style>
