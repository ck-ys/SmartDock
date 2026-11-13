<template>
  <svg class="brand-mark" :width="px" :height="px" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <template v-if="showPiece">
      <mask :id="maskId">
        <rect width="24" height="24" fill="#fff" />
        <rect x="12.2" y="8.4" width="7.8" height="7.8" fill="#000" />
      </mask>
      <rect x="2.6" y="2.6" width="18.8" height="18.8" rx="4.6" fill="var(--brand-ink, #1e2a38)" :mask="`url(#${maskId})`" />
      <rect x="13.6" y="9.8" width="5" height="5" fill="var(--brand-signal, #2f6feb)" />
    </template>
    <!-- ≤20px 简化版：只画方料。缝在 16px 下只剩 0.9px，件会糊成一道脏边，任何配色都救不回来 -->
    <rect v-else x="2.6" y="2.6" width="18.8" height="18.8" rx="4.6" fill="var(--brand-ink, #1e2a38)" />
  </svg>
</template>

<script lang="ts">
/** 同页多实例时 mask id 必须互不相同，用模块级计数器（放 setup 里每个实例都会从 1 开始）。 */
let brandMarkSeq = 0;
</script>

<script setup lang="ts">
/**
 * 品牌标「停靠」：一块方料右侧铣一个槽，件坐在槽里，四周留等宽的缝。
 * 24 网格：方料 18.8 见方、圆角 4.6；槽 7.8 见方、偏心 4.0（不在正中，这是刻意的）；
 * 件 5.0 见方，四周缝恒等 1.4——等缝是铣出来的工艺感，不是摆出来的。
 *
 * 2026-11-13 视觉批 Q11（回执案 1 · L2）：方料=藏青 #1e2a38、件=业务蓝 #2f6feb，
 * 两个值分别等于业务壳侧栏色与按钮色。上一版青绿 #12a594 + 珊瑚 #ff5a3c 的件/底
 * 对比只有 1.01、色相 164°（近互补），小尺寸会视觉颤动，已撤。
 * 尺寸规则（同批定）：≥22px 完整版；≤20px 简化版只画方料（favicon 取简化版）。
 */
import { computed } from 'vue';

const props = withDefaults(defineProps<{ size?: number }>(), { size: 22 });
const px = computed(() => `${props.size}px`);
const showPiece = computed(() => props.size >= 22);
const maskId = `sd-brand-mask-${++brandMarkSeq}`;
</script>

<style lang="scss" scoped>
.brand-mark {
  display: block;
  flex: none;
}
</style>
