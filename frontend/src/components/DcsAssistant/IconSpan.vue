<template>
  <span class="iswap" :style="sizeStyle" v-html="svg" />
</template>

<script setup lang="ts">
/**
 * 图标组件（批I-1，Q1=A）：渲染 icons.ts 原型 Lucide 字典——span.iswap 包裹语义
 * 同名于原型运行时替换产物（原型 EMO2IC→ic()→<span class="iswap">），1em 随字号
 * 缩放；需要固定像素时传 size（如 16）。
 */
import { computed } from 'vue';
import { ic } from './icons';

const props = defineProps<{ name: string; size?: number | string; cls?: string }>();

const svg = computed(() => ic(props.name, '1em', props.cls));
const sizeStyle = computed(() => {
  if (props.size === undefined) {
    return undefined;
  }
  const v = typeof props.size === 'number' ? `${props.size}px` : props.size;
  return { width: v, height: v } as Record<string, string>;
});
</script>

<style lang="scss" scoped>
.iswap {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  vertical-align: -0.125em; /* 与文本基线对齐（同 emoji 的下沉视觉） */
  flex: none;

  :deep(svg) {
    display: block;
  }
}
</style>
