<script setup lang="ts">
import { computed } from 'vue';
import { useDocumentVisibility, useMediaQuery, usePreferredReducedMotion, useStorage } from '@vueuse/core';
import { VideoPause, VideoPlay } from '@element-plus/icons-vue';
import { useI18n } from 'vue-i18n';
import { resolveSceneMode, type SceneMode } from '../sceneMotion';

/**
 * 登录页左侧视觉：纯 CSS/SVG 绘制的「信号中枢」场景，零位图、零文案。
 * - 深色底 + 发光核心 + 环形轨道 + 汇入平台的光纤信号 + 抽象玻璃数据卡；
 * - 所有元素绝对定位按百分比排布，任意面板尺寸自适应；
 * - 手机端（≤1000px）降级为紧凑 hero：隐藏数据卡与开关，只留核心与平台。
 * 若需换主题：直接改样式区对应的色值与 .zone/.platform 系列尺寸即可，不涉及任何图片资产。
 */
const { locale } = useI18n();
const isEnglish = computed(() => locale.value.startsWith('en'));
const savedMode = useStorage<SceneMode>('smartdock-login-scene-mode', 'dynamic');
const reducedMotion = usePreferredReducedMotion();
const visibility = useDocumentVisibility();
const compact = useMediaQuery('(max-width: 1000px)');
const mode = computed(() =>
  resolveSceneMode({
    choice: savedMode.value,
    reduced: reducedMotion.value === 'reduce',
    hidden: visibility.value === 'hidden',
    compact: compact.value
  })
);
const motionHint = computed(() => {
  if (reducedMotion.value === 'reduce') return isEnglish.value ? 'Reduced motion is enabled in your system' : '已遵循系统减少动态效果设置';
  return isEnglish.value ? 'Switch the scene between still and animated' : '切换静态与动态背景';
});

// viewBox 归一为 0..100，坐标即面板百分比；信号只汇到平台边缘，不穿入台面。
const signals = [
  { id: 'left', d: 'M -4 42 C 12 46 24 52 35 57', duration: '12s', delay: '-3s' },
  { id: 'right', d: 'M 104 36 C 88 42 76 50 65 57', duration: '14s', delay: '-6s' },
  { id: 'below', d: 'M 50 106 C 50 96 50 91 50 86', duration: '15s', delay: '-9s' }
];
const particles = [
  { x: 12, y: 18, size: 3, delay: '-1s', duration: '6s' },
  { x: 24, y: 64, size: 2, delay: '-3s', duration: '7s' },
  { x: 8, y: 82, size: 2, delay: '-5s', duration: '8s' },
  { x: 38, y: 12, size: 2, delay: '-2s', duration: '9s' },
  { x: 64, y: 16, size: 3, delay: '-6s', duration: '7s' },
  { x: 84, y: 22, size: 2, delay: '-4s', duration: '8s' },
  { x: 92, y: 56, size: 3, delay: '-7s', duration: '9s' },
  { x: 88, y: 78, size: 2, delay: '-2s', duration: '6s' },
  { x: 70, y: 88, size: 2, delay: '-8s', duration: '10s' },
  { x: 18, y: 40, size: 2, delay: '-9s', duration: '11s' },
  { x: 30, y: 90, size: 2, delay: '-3s', duration: '8s' },
  { x: 76, y: 70, size: 2, delay: '-5s', duration: '7s' }
];
const bars = [38, 62, 48, 78, 56];

function toggleMotion() {
  savedMode.value = savedMode.value === 'static' ? 'dynamic' : 'static';
}
</script>

<template>
  <div class="login-scene" :class="{ 'is-moving': mode === 'dynamic', 'is-compact': compact }" :data-scene-mode="mode">
    <div class="scene-art" aria-hidden="true">
      <span class="scene-aurora scene-aurora--a"></span>
      <span class="scene-aurora scene-aurora--b"></span>
      <span class="scene-pillar"></span>
      <span class="scene-grid"></span>
      <span class="scene-vignette"></span>

      <svg class="scene-signals" viewBox="0 0 100 100" preserveAspectRatio="none">
        <defs>
          <linearGradient id="signal-left" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stop-color="#3ddad7" stop-opacity="0" />
            <stop offset="48%" stop-color="#4fd6e8" stop-opacity="0.5" />
            <stop offset="100%" stop-color="#a9d5ff" stop-opacity="0.95" />
          </linearGradient>
          <linearGradient id="signal-right" x1="1" y1="0" x2="0" y2="0">
            <stop offset="0%" stop-color="#7c6cff" stop-opacity="0" />
            <stop offset="55%" stop-color="#5ea8ff" stop-opacity="0.45" />
            <stop offset="100%" stop-color="#a9d5ff" stop-opacity="0.9" />
          </linearGradient>
          <linearGradient id="signal-below" x1="0" y1="1" x2="0" y2="0">
            <stop offset="0%" stop-color="#3ddad7" stop-opacity="0" />
            <stop offset="100%" stop-color="#a9d5ff" stop-opacity="0.85" />
          </linearGradient>
        </defs>
        <path
          v-for="signal in signals"
          :key="signal.id"
          class="signal-line"
          :d="signal.d"
          :stroke="signal.id === 'below' ? 'url(#signal-below)' : signal.id === 'left' ? 'url(#signal-left)' : 'url(#signal-right)'"
          pathLength="1000"
          vector-effect="non-scaling-stroke"
        />
        <path
          v-for="signal in signals"
          :key="`${signal.id}-glint`"
          class="signal-glint"
          :d="signal.d"
          pathLength="1000"
          vector-effect="non-scaling-stroke"
          :style="{ animationDuration: signal.duration, animationDelay: signal.delay }"
        />
      </svg>
      <span class="signal-node signal-node--sink" style="left: 50%; top: 86%"></span>

      <div class="zone">
        <span class="zone__halo"></span>
        <span class="orbit orbit--far"></span>
        <span class="orbit orbit--outer"></span>
        <span class="orbit orbit--mid"></span>
        <span class="orbit-arc"></span>
        <span class="ripple ripple--a"></span>
        <span class="ripple ripple--b"></span>
        <span class="beam"></span>
        <span class="platform"></span>
        <span class="platform__ring platform__ring--a"></span>
        <span class="platform__ring platform__ring--b"></span>
        <span class="platform__glow"></span>
        <span class="portal"></span>
        <span class="portal__ring"></span>
        <span class="signal-node signal-node--edge" style="left: 1%; top: 66%"></span>
        <span class="signal-node signal-node--edge" style="left: 99%; top: 66%"></span>
        <div class="core">
          <span class="core__halo"></span>
          <span class="core__orb"></span>
          <span class="core__glint"></span>
          <span class="core__ring"></span>
        </div>
      </div>
      <span class="scene-floor"></span>

      <div class="tile tile--bars">
        <span class="tile__label"></span>
        <span class="tile__bars">
          <i v-for="(height, index) in bars" :key="index" :style="{ height: `${height}%` }"></i>
        </span>
      </div>

      <div class="tile tile--spark">
        <svg viewBox="0 0 120 48" preserveAspectRatio="none">
          <path class="tile__spark-line" d="M2 40 L18 29 L34 34 L52 20 L70 27 L88 12 L104 19 L118 6" />
          <path class="tile__spark-glow" d="M2 40 L18 29 L34 34 L52 20 L70 27 L88 12 L104 19 L118 6" />
        </svg>
      </div>

      <div class="tile tile--dots">
        <span
          v-for="n in 15"
          :key="n"
          class="tile__dot"
          :class="{ 'is-on': [2, 3, 5, 8, 9, 12, 14].includes(n) }"
        ></span>
      </div>

      <span
        v-for="(particle, index) in particles"
        :key="index"
        class="particle"
        :class="{ 'particle--cyan': [2, 5, 9].includes(index) }"
        :style="{
          left: `${particle.x}%`,
          top: `${particle.y}%`,
          width: `${particle.size}px`,
          height: `${particle.size}px`,
          animationDelay: particle.delay,
          animationDuration: particle.duration
        }"
      ></span>
    </div>

    <button
      class="scene-motion-toggle"
      type="button"
      :aria-label="isEnglish ? 'Animated background' : '动态背景'"
      :title="motionHint"
      :aria-pressed="mode === 'dynamic'"
      :disabled="reducedMotion === 'reduce'"
      @click="toggleMotion"
    >
      <component :is="mode === 'dynamic' ? VideoPause : VideoPlay" aria-hidden="true" />
    </button>
  </div>
</template>

<style scoped lang="scss">
.login-scene {
  position: relative;
  width: 100%;
  min-width: 0;
  height: 100%;
  overflow: hidden;
  isolation: isolate;
  background:
    radial-gradient(92% 58% at 22% 6%, rgb(70 130 220 / 16%) 0%, rgb(70 130 220 / 0%) 62%),
    radial-gradient(110% 70% at 50% 108%, rgb(38 86 170 / 42%) 0%, rgb(38 86 170 / 0%) 62%),
    linear-gradient(168deg, #071228 0%, #081b3a 46%, #0a2450 100%);
}

.scene-art {
  position: absolute;
  inset: 0;
}

/* ---------- 氛围层 ---------- */
.scene-aurora {
  position: absolute;
  border-radius: 50%;
  filter: blur(70px);
}

.scene-aurora--a {
  left: -12%;
  top: -16%;
  width: 58%;
  aspect-ratio: 1;
  background: radial-gradient(circle, rgb(64 140 255 / 38%) 0%, rgb(64 140 255 / 0%) 68%);
}

.scene-aurora--b {
  right: -16%;
  bottom: -20%;
  width: 56%;
  aspect-ratio: 1;
  background: radial-gradient(circle, rgb(108 92 231 / 28%) 0%, rgb(108 92 231 / 0%) 70%);
}

.scene-pillar {
  position: absolute;
  left: 50%;
  top: 4%;
  width: min(34%, 360px);
  height: 66%;
  transform: translateX(-50%);
  background: linear-gradient(180deg, rgb(90 160 255 / 0%) 0%, rgb(90 160 255 / 9%) 48%, rgb(90 160 255 / 0%) 100%);
  filter: blur(18px);
}

.scene-grid {
  position: absolute;
  inset: 0;
  background-image: radial-gradient(rgb(140 190 255 / 17%) 1px, transparent 1px);
  background-size: 26px 26px;
  mask-image: radial-gradient(120% 90% at 50% 54%, #000 8%, transparent 72%);
  -webkit-mask-image: radial-gradient(120% 90% at 50% 54%, #000 8%, transparent 72%);
}

.scene-vignette {
  position: absolute;
  inset: 0;
  background: radial-gradient(120% 100% at 50% 46%, rgb(2 8 22 / 0%) 42%, rgb(2 8 22 / 55%) 100%);
}

/* ---------- 信号线 ---------- */
.scene-signals {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  overflow: visible;
}

.signal-line {
  fill: none;
  stroke-width: 1.5px;
  opacity: 0.95;
}

.signal-glint {
  fill: none;
  stroke: #dcf1ff;
  stroke-width: 2px;
  stroke-linecap: round;
  stroke-dasharray: 14 986;
  stroke-dashoffset: 1000;
  opacity: 0;
}

.is-moving .signal-glint {
  opacity: 0.9;
  animation-name: signal-flow;
  animation-timing-function: linear;
  animation-iteration-count: infinite;
}

/* ---------- 中枢：轨道 + 平台 + 核心 ---------- */
.zone {
  position: absolute;
  left: 50%;
  top: 50%;
  width: min(52%, 660px);
  aspect-ratio: 1;
  transform: translate(-50%, -50%);
}

.zone__halo {
  position: absolute;
  left: 50%;
  top: 50%;
  width: 150%;
  aspect-ratio: 1;
  transform: translate(-50%, -50%);
  background: radial-gradient(circle, rgb(70 140 240 / 13%) 0%, rgb(70 140 240 / 0%) 62%);
  filter: blur(8px);
}

.orbit {
  position: absolute;
  border-radius: 50%;
}

.orbit--far {
  inset: -26%;
  border: 1px solid rgb(110 160 235 / 7%);
}

.orbit--outer {
  inset: 0;
  border: 1px solid rgb(122 170 240 / 14%);
}

.orbit--mid {
  inset: 9%;
  border: 1px solid rgb(122 170 240 / 9%);
}

.orbit-arc {
  position: absolute;
  inset: 2.5%;
  border-radius: 50%;
  background: conic-gradient(
    from 210deg,
    rgb(94 168 255 / 0%) 0deg 190deg,
    rgb(94 168 255 / 85%) 236deg,
    rgb(168 212 255 / 20%) 272deg,
    rgb(94 168 255 / 0%) 300deg
  );
  mask-image: radial-gradient(farthest-side, transparent calc(100% - 2.5px), #000 calc(100% - 2px));
  -webkit-mask-image: radial-gradient(farthest-side, transparent calc(100% - 2.5px), #000 calc(100% - 2px));
}

.is-moving .orbit-arc {
  animation: orbit-spin 26s linear infinite;
}

/* 地面涟漪：围绕平台的扩散椭圆，填补下半区并制造纵深 */
.ripple {
  position: absolute;
  left: 50%;
  top: 66%;
  border: 1px solid rgb(110 165 240 / 11%);
  border-radius: 50%;
  transform: translate(-50%, -50%);
}

.ripple--a {
  width: 132%;
  aspect-ratio: 2.6 / 1;
}

.ripple--b {
  width: 168%;
  aspect-ratio: 2.8 / 1;
  border-color: rgb(110 165 240 / 7%);
}

.platform {
  position: absolute;
  left: 50%;
  top: 66%;
  width: 100%;
  aspect-ratio: 2.6 / 1;
  transform: translate(-50%, -50%);
  border-radius: 50%;
  background:
    radial-gradient(ellipse at 50% 6%, rgb(150 205 255 / 24%) 0%, rgb(150 205 255 / 0%) 55%),
    linear-gradient(180deg, rgb(30 68 118 / 92%) 0%, rgb(13 32 62 / 94%) 55%, rgb(6 16 34 / 96%) 100%);
  box-shadow:
    inset 0 1.5px 0 rgb(180 220 255 / 70%),
    inset 0 -30px 50px -30px rgb(0 0 0 / 90%),
    0 36px 80px -44px rgb(0 0 0 / 85%),
    0 0 70px rgb(60 130 230 / 18%);
}

/* 台缘光环 */
.platform::before {
  content: '';
  position: absolute;
  inset: -1px;
  border: 1px solid rgb(140 200 255 / 26%);
  border-radius: 50%;
  box-shadow: 0 0 26px rgb(90 160 255 / 26%);
}

/* 前左缘弧光：只亮一段，形成金属反光层次 */
.platform::after {
  content: '';
  position: absolute;
  inset: -1.5px;
  border-radius: 50%;
  background: conic-gradient(
    from 128deg,
    rgb(170 220 255 / 0%) 0deg 44deg,
    rgb(170 220 255 / 78%) 74deg,
    rgb(170 220 255 / 0%) 108deg
  );
  mask-image: radial-gradient(farthest-side, transparent calc(100% - 3px), #000 calc(100% - 2px));
  -webkit-mask-image: radial-gradient(farthest-side, transparent calc(100% - 3px), #000 calc(100% - 2px));
}

/* 台面同心刻环 */
.platform__ring {
  position: absolute;
  left: 50%;
  top: 66%;
  border: 1px solid rgb(140 190 255 / 14%);
  border-radius: 50%;
  transform: translate(-50%, -50%);
}

.platform__ring--a {
  width: 78%;
  aspect-ratio: 2.6 / 1;
}

.platform__ring--b {
  width: 52%;
  aspect-ratio: 2.6 / 1;
  border-color: rgb(140 190 255 / 20%);
}

.platform__glow {
  position: absolute;
  left: 50%;
  top: 66%;
  width: 74%;
  aspect-ratio: 3.4 / 1;
  transform: translate(-50%, -50%);
  background: radial-gradient(ellipse, rgb(88 158 255 / 20%) 0%, rgb(88 158 255 / 0%) 68%);
  filter: blur(16px);
}

/* 中枢井口：光环而非亮斑 */
.portal {
  position: absolute;
  left: 50%;
  top: 57%;
  width: 20%;
  aspect-ratio: 3.2 / 1;
  transform: translate(-50%, -50%);
  border-radius: 50%;
  background: radial-gradient(
    ellipse,
    rgb(150 215 255 / 0%) 0% 32%,
    rgb(120 195 255 / 36%) 52%,
    rgb(70 130 225 / 16%) 72%,
    rgb(70 130 225 / 0%) 100%
  );
  box-shadow: 0 0 36px rgb(100 180 255 / 30%);
}

.portal__ring {
  position: absolute;
  left: 50%;
  top: 57%;
  width: 13.5%;
  aspect-ratio: 3.2 / 1;
  transform: translate(-50%, -50%);
  border: 1px solid rgb(140 230 235 / 50%);
  border-radius: 50%;
  box-shadow:
    0 0 16px rgb(110 225 230 / 42%),
    inset 0 0 12px rgb(110 225 230 / 22%);
}

.beam {
  position: absolute;
  left: 50%;
  top: 44%;
  width: 14%;
  height: 13%;
  transform: translateX(-50%);
  background: linear-gradient(180deg, rgb(120 190 255 / 26%) 0%, rgb(120 190 255 / 0%) 100%);
  border-radius: 50%;
  filter: blur(10px);
}

.core {
  /* zone 百分比定宽：与平台同一坐标系，窄桌面下井口环不会被球体盖住 */
  position: absolute;
  left: 50%;
  top: 44%;
  width: 22%;
  aspect-ratio: 1;
  transform: translate(-50%, -50%);
}

.core__halo {
  position: absolute;
  inset: -46%;
  border-radius: 50%;
  background: radial-gradient(circle, rgb(90 170 255 / 26%) 0%, rgb(90 170 255 / 0%) 68%);
  filter: blur(10px);
}

.core__orb {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  background: radial-gradient(circle at 36% 30%, #dff2ff 0 5%, #b5ddff 15%, #79bdf5 33%, #3679cb 58%, #16407f 80%, #081a38 100%);
  box-shadow:
    inset 0 0 0 1px rgb(200 232 255 / 32%),
    inset -12px -16px 32px rgb(0 18 55 / 55%),
    0 0 40px rgb(96 180 255 / 38%);
}

/* 环绕轨道：给球体一个「卫星」层次，避免像纯渐变球 */
.core__ring {
  position: absolute;
  inset: -16%;
  border: 1px solid rgb(140 195 255 / 28%);
  border-top-color: rgb(200 235 255 / 60%);
  border-radius: 50%;
  transform: rotate(-20deg) scaleY(0.36);
}

.core__glint {
  position: absolute;
  left: 26%;
  top: 20%;
  width: 24%;
  height: 17%;
  border-radius: 50%;
  background: radial-gradient(closest-side, rgb(255 255 255 / 62%), transparent);
  filter: blur(3px);
}

.is-moving .core__orb {
  animation: core-pulse 7.5s ease-in-out infinite;
}

/* 底部地面辉光：压住画面下缘，避免下方空洞 */
.scene-floor {
  position: absolute;
  left: 50%;
  bottom: -16%;
  width: 86%;
  aspect-ratio: 3 / 1;
  transform: translateX(-50%);
  background: radial-gradient(ellipse, rgb(48 110 205 / 20%) 0%, rgb(48 110 205 / 0%) 68%);
  filter: blur(40px);
  pointer-events: none;
}

/* 信号汇入点：--edge 贴平台左右缘(zone 坐标)，--sink 在场景下方汇入 */
.signal-node {
  position: absolute;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #bfe4ff;
  box-shadow: 0 0 12px rgb(150 210 255 / 85%);
  opacity: 0.8;
}

.signal-node--sink {
  margin: -2.5px 0 0 -2.5px;
}

.signal-node--edge {
  margin: -2.5px 0 0 -2.5px;
  z-index: 1;
}

/* ---------- 抽象玻璃数据卡（无文字） ---------- */
.tile {
  position: absolute;
  border: 1px solid rgb(150 195 255 / 22%);
  border-radius: 16px;
  background: linear-gradient(150deg, rgb(120 170 255 / 12%), rgb(120 170 255 / 4%));
  box-shadow:
    inset 0 1px 0 rgb(220 240 255 / 22%),
    0 20px 44px -30px rgb(0 0 0 / 60%);
  backdrop-filter: blur(10px);
}

.tile--bars {
  left: 7%;
  top: 13%;
  width: clamp(120px, 12vw, 168px);
  height: 104px;
  padding: 16px;
  transform: rotate(-7deg);
}

.tile__label {
  display: block;
  width: 44%;
  height: 7px;
  border-radius: 999px;
  background: rgb(190 220 255 / 32%);
}

.tile__bars {
  display: flex;
  align-items: flex-end;
  gap: 7px;
  height: 52px;
  margin-top: 14px;
}

.tile__bars i {
  flex: 1;
  border-radius: 4px 4px 2px 2px;
  background: linear-gradient(180deg, #8cc6ff 0%, rgb(94 168 255 / 26%) 100%);
  box-shadow: 0 0 12px rgb(94 168 255 / 35%);
}

.tile--spark {
  right: 7%;
  top: 22%;
  width: clamp(132px, 11vw, 158px);
  height: 92px;
  padding: 14px;
  transform: rotate(6deg);
}

.tile--spark svg {
  display: block;
  width: 100%;
  height: 100%;
  overflow: visible;
}

.tile__spark-line {
  fill: none;
  stroke: #8cc6ff;
  stroke-width: 2;
  stroke-linecap: round;
  stroke-linejoin: round;
}

.tile__spark-glow {
  fill: none;
  stroke: rgb(94 168 255 / 45%);
  stroke-width: 6;
  stroke-linecap: round;
  stroke-linejoin: round;
  filter: blur(6px);
}

.tile--dots {
  left: 9%;
  bottom: 15%;
  display: grid;
  grid-template-columns: repeat(5, 8px);
  gap: 9px 12px;
  padding: 16px;
  transform: rotate(-4deg);
}

.tile__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: rgb(160 200 250 / 22%);
}

.tile__dot.is-on {
  background: #7cc0ff;
  box-shadow: 0 0 10px rgb(124 192 255 / 70%);
}

.is-moving .tile {
  animation: tile-float 11s ease-in-out infinite;
}

.is-moving .tile--spark {
  animation-delay: -3.5s;
}

.is-moving .tile--dots {
  animation-delay: -7s;
}

/* ---------- 光点 ---------- */
.particle {
  position: absolute;
  border-radius: 50%;
  background: #cfe8ff;
  box-shadow: 0 0 10px rgb(160 215 255 / 80%);
  opacity: 0.25;
}

.particle--cyan {
  background: #8ff3ef;
  box-shadow: 0 0 10px rgb(120 235 230 / 80%);
}

.is-moving .particle {
  animation-name: particle-twinkle;
  animation-timing-function: ease-in-out;
  animation-iteration-count: infinite;
}

/* ---------- 动效开关（纯图标，语义在 aria-label/title） ---------- */
.scene-motion-toggle {
  position: absolute;
  z-index: 4;
  right: clamp(16px, 2vw, 28px);
  bottom: clamp(14px, 2vh, 24px);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  padding: 0;
  border: 1px solid rgb(130 180 255 / 26%);
  border-radius: 50%;
  background: rgb(12 30 62 / 55%);
  color: rgb(208 228 255 / 82%);
  font: inherit;
  cursor: pointer;
  backdrop-filter: blur(10px);
  transition:
    color 0.2s ease,
    border-color 0.2s ease,
    background 0.2s ease;
}

.scene-motion-toggle svg {
  width: 16px;
  height: 16px;
}

.scene-motion-toggle:hover:not(:disabled) {
  border-color: rgb(150 195 255 / 55%);
  background: rgb(20 44 86 / 78%);
  color: #eaf4ff;
}

.scene-motion-toggle:focus-visible {
  outline: 3px solid #5ea8ff;
  outline-offset: 3px;
}

.scene-motion-toggle:disabled {
  cursor: default;
  opacity: 0.65;
}

/* ---------- 手机端紧凑 hero ---------- */
.is-compact {
  .zone {
    width: min(54vw, 200px);
    top: 53%;
  }

  .core {
    width: 56px;
  }

  .tile,
  .scene-motion-toggle {
    display: none;
  }

  .scene-grid {
    background-size: 22px 22px;
  }

  .signal-line {
    opacity: 0.55;
  }

  .platform__glow {
    opacity: 0.7;
  }
}

@keyframes signal-flow {
  0% {
    stroke-dashoffset: 1000;
    opacity: 0;
  }

  10%,
  86% {
    opacity: 0.9;
  }

  100% {
    stroke-dashoffset: 0;
    opacity: 0;
  }
}

@keyframes orbit-spin {
  to {
    transform: rotate(360deg);
  }
}

@keyframes core-pulse {
  0%,
  100% {
    transform: translateY(0) scale(1);
  }

  50% {
    transform: translateY(-9px) scale(1.015);
  }
}

@keyframes tile-float {
  0%,
  100% {
    translate: 0 0;
  }

  50% {
    translate: 0 -8px;
  }
}

@keyframes particle-twinkle {
  0%,
  100% {
    opacity: 0.18;
    transform: scale(0.85);
  }

  50% {
    opacity: 0.85;
    transform: scale(1.15);
  }
}

@media (prefers-reduced-motion: reduce) {
  .login-scene *,
  .login-scene *::before,
  .login-scene *::after {
    animation: none !important;
  }

  .signal-glint {
    opacity: 0 !important;
  }
}
</style>
