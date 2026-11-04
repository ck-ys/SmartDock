<template>
  <svg class="brand-mark" :width="px" :height="px" viewBox="0 0 24 24" aria-hidden="true" focusable="false">
    <mask :id="maskId">
      <rect width="24" height="24" fill="#fff" />
      <rect x="7.2" y="2" width="9.6" height="13.6" fill="#000" />
    </mask>
    <rect x="2.4" y="2.4" width="19.2" height="19.2" rx="4.2" fill="var(--brand-ink, #12a594)" :mask="`url(#${maskId})`" />
    <rect x="9.4" y="7.2" width="5.2" height="5.2" fill="var(--brand-signal, #ff5a3c)" />
  </svg>
</template>

<script lang="ts">
/** 同页多实例时 mask id 必须互不相同，用模块级计数器（放 setup 里每个实例都会从 1 开始）。 */
let brandMarkSeq = 0;
</script>

<script setup lang="ts">
/**
 * 品牌标「停靠」：一块方料开一个朝上的托槽，珊瑚件坐在槽里，四周留缝。
 * 24 网格：方料 19.2 见方、圆角 4.2；槽 9.6 宽、开口到方料顶边以上（所以轮廓上有个缺口）；
 * 件 5.2 见方，左右缝 2.2、底缝 3.2。珊瑚只用在这颗件上——全站珊瑚只表示「正在发生」。
 * 为什么是开口槽不是封闭窗口：真机 22px 实测，封闭窗口糊成「青块+红点」（像录像键），
 * 开口之后 22px 仍认得出是个托着东西的槽（见 .workshop/logs 2026-11-04）。
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
