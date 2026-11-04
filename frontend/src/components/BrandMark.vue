<template>
  <svg class="brand-mark" :width="px" :height="px" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <mask :id="maskId">
      <rect width="24" height="24" fill="#fff" />
      <rect x="12.2" y="8.4" width="7.8" height="7.8" fill="#000" />
    </mask>
    <rect x="2.6" y="2.6" width="18.8" height="18.8" rx="4.6" fill="var(--brand-ink, #12a594)" :mask="`url(#${maskId})`" />
    <rect x="13.6" y="9.8" width="5" height="5" fill="var(--brand-signal, #ff5a3c)" />
  </svg>
</template>

<script lang="ts">
/** 同页多实例时 mask id 必须互不相同，用模块级计数器（放 setup 里每个实例都会从 1 开始）。 */
let brandMarkSeq = 0;
</script>

<script setup lang="ts">
/**
 * 品牌标「停靠」：一块方料右侧铣一个槽，珊瑚件坐在槽里，四周留等宽的缝。
 * 24 网格：方料 18.8 见方、圆角 4.6；槽 7.8 见方、偏心 4.0（不在正中，这是刻意的）；
 * 件 5.0 见方，四周缝恒等 1.4——等缝是铣出来的工艺感，不是摆出来的。
 * 珊瑚只用在这颗件上：全站珊瑚只表示「正在发生」，标上这颗就是"零件在动"。
 * 小尺寸实测：22px 顶栏下缝约 1.3px 仍可见，读作"方块里嵌着一件"；16px favicon 更紧一些，
 * 但件与底仍分得开（见 .workshop/logs 2026-11-04）。
 */
import { computed } from 'vue';

const props = withDefaults(defineProps<{ size?: number }>(), { size: 22 });
const px = computed(() => `${props.size}px`);
const maskId = `sd-brand-mask-${++brandMarkSeq}`;
</script>

<style lang="scss" scoped>
.brand-mark {
  display: block;
  flex: none;
}
</style>
