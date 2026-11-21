<template>
  <!-- R1(2026-10-10 拍板A) 厂商真标：七家已收录（Cherry Studio 开源 SVG 6 枚 + ZCode opencode 1 枚，
       商用合规源），未收录厂商降级字母徽章（沿用 PROVIDER_SHORT 口径），不留破图 -->
  <img v-if="iconSrc" class="plogo" :class="{ 'plogo-sm': size <= 18 }" :src="iconSrc" :alt="provider" :style="boxStyle" />
  <span v-else class="plogo plogo-fb" :class="{ 'plogo-sm': size <= 18 }" :style="fallbackStyle">{{ short }}</span>
</template>

<script setup lang="ts">
/**
 * 厂商标图标（R1 厂商中心）：已知厂商渲染官方 SVG，未知厂商渲染首字母色块徽章。
 * 尺寸口径：左栏导航 26 / 详情头 40 / 下拉菜单 16。
 */
import { computed } from 'vue';
import deepseekSvg from '@/assets/icons/providers/deepseek.svg';
import qwenSvg from '@/assets/icons/providers/qwen.svg';
import zhipuSvg from '@/assets/icons/providers/zhipu.svg';
import siliconSvg from '@/assets/icons/providers/silicon.svg';
import moonshotSvg from '@/assets/icons/providers/moonshot.svg';
import openaiSvg from '@/assets/icons/providers/openai.svg';
import opencodeSvg from '@/assets/icons/providers/opencode.svg';
// 业务批4 Q10：六家+混元补齐（lobehub lobe-icons 官方 SVG，商用合规源）
import stepfunSvg from '@/assets/icons/providers/stepfun.svg';
import openrouterSvg from '@/assets/icons/providers/openrouter.svg';
import baiduSvg from '@/assets/icons/providers/baidu.svg';
import sparkSvg from '@/assets/icons/providers/spark.svg';
import minimaxSvg from '@/assets/icons/providers/minimax.svg';
import hunyuanSvg from '@/assets/icons/providers/hunyuan.svg';

const ICONS: Record<string, string> = {
  DEEPSEEK: deepseekSvg,
  QWEN: qwenSvg,
  ZHIPU: zhipuSvg,
  SILICONFLOW: siliconSvg,
  KIMI: moonshotSvg,
  OPENAI: openaiSvg,
  OPENCODE: opencodeSvg,
  STEPFUN: stepfunSvg,
  OPENROUTER: openrouterSvg,
  BAIDU_QIANFAN: baiduSvg,
  TENCENT_HUNYUAN: hunyuanSvg,
  XFYUN_SPARK: sparkSvg,
  MINIMAX: minimaxSvg
};
/** 兜底徽章底色（EP 灰阶，不引入杂色） */
const FALLBACK_BG: Record<string, string> = {
  DEEPSEEK: '#4d6bfe',
  QWEN: '#6236e7',
  ZHIPU: '#3366ff',
  SILICONFLOW: '#6e29f6',
  KIMI: '#909399',
  OPENAI: '#10a37f',
  OPENCODE: '#211e1e',
  STEPFUN: '#2b2b2b',
  OPENROUTER: '#6467f2',
  BAIDU_QIANFAN: '#2932e1',
  TENCENT_HUNYUAN: '#0053e0',
  XFYUN_SPARK: '#0b7bff',
  MINIMAX: '#ff3412'
};

const props = withDefaults(defineProps<{ provider: string; size?: number }>(), { size: 26 });

const key = computed(() => (props.provider || '').toUpperCase());
const iconSrc = computed(() => ICONS[key.value] || '');
const short = computed(() => key.value.slice(0, 2) || '?');
const boxStyle = computed(() => ({ width: `${props.size}px`, height: `${props.size}px` }));
const fallbackStyle = computed(() => ({
  ...boxStyle.value,
  background: FALLBACK_BG[key.value] || 'var(--app-muted)',
  fontSize: `${Math.max(9, Math.round(props.size * 0.38))}px`
}));
</script>

<style scoped lang="scss">
.plogo {
  flex: none;
  object-fit: contain;
  border-radius: var(--app-control-radius);
  background: var(--app-surface);
  border: 1px solid var(--app-rule);
  padding: 2px;
  &.plogo-sm {
    border-radius: var(--el-border-radius-small);
    padding: 1px;
  }
}
.plogo-fb {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--app-primary-ink);
  font-weight: 700;
  letter-spacing: 0.5px;
}
</style>
