<template>
  <div
    class="dcs-fab"
    :class="{ dragging, gray, flip: menuFlip }"
    :style="fabStyle"
    @pointerdown="onDown"
    @pointermove="onMove"
    @pointerup="onUp"
    @pointercancel="onCancel"
    @lostpointercapture="onCancel"
  >
    <!-- 主动提示卡（原型 proactive：REVISE 退修提醒，球上方；每会话一次由父控）。
         AU-01(1007拍板Q1-C)：主按钮直达任务详情退修意见；「让 AI 解释」为次级辅助入口。
         指针事件 .stop：卡是 .dcs-fab 容器的子元素，不拦会冒泡到 onUp→onActivate，
         点卡任何位置都被当成点球弹快捷提问（2026-10-07 用户实测反馈"点哪都弹很别扭"） -->
    <div v-if="proactive" class="proactive" role="status" @pointerdown.stop @pointermove.stop @pointerup.stop>
      <button class="x" aria-label="关闭提醒" @click="emit('pro-dismiss')"><IconSpan name="x" /></button>
      <div class="ph"><IconSpan name="hand" /> 顺便说一句</div>
      <div class="pb">「{{ proactive.missionName }}」被退回了，去看退回意见</div>
      <div class="pac">
        <button class="pa-go" @click="emit('pro-accept')">去看退回意见</button>
        <button class="pa-no" @click="emit('pro-dismiss')">先不用</button>
      </div>
      <button class="pa-ai" type="button" @click="emit('pro-ai')">让 AI 解释怎么改</button>
    </div>

    <!-- 快捷菜单（原型 ballmenu：单击弹出；菜单开着再点球=开面板） -->
    <div v-if="menuOpen" class="ballmenu" role="menu" aria-label="快捷提问">
      <div class="tip">快捷提问 · 不用打开面板</div>
      <button v-for="m in menuItems" :key="m.label" class="bm" role="menuitem" @click="emit('ask', m.prompt)">
        <span class="bi"><IconSpan :name="m.icon" /></span>{{ m.label }}
      </button>
    </div>

    <span
      class="fab-core"
      :class="{ exec }"
      :aria-label="gray ? 'AI 助手（未配置模型）' : '打开 AI 助手'"
      role="button"
      tabindex="0"
      @keydown.enter="onActivate"
      @keydown.space.prevent="onActivate"
    >
      <!-- 原型 #ball 机器人脸逐 path 对齐（批I-1：方框参数/侧耳/眼嘴/天线/stroke 2.1） -->
      <svg
        viewBox="0 0 24 24"
        width="27"
        height="27"
        fill="none"
        stroke="currentColor"
        stroke-width="2.1"
        stroke-linecap="round"
        stroke-linejoin="round"
      >
        <rect x="4.2" y="7" width="15.6" height="12" rx="4.2" />
        <path d="M12 7V4.6" />
        <circle cx="12" cy="3.7" r="1" fill="currentColor" stroke="none" />
        <circle cx="9.2" cy="13" r="1.25" fill="currentColor" stroke="none" />
        <circle cx="14.8" cy="13" r="1.25" fill="currentColor" stroke="none" />
        <path d="M9.6 16.4h4.8" />
        <path d="M4.2 11.5H2.8M19.8 11.5h1.4" />
      </svg>
    </span>
    <span v-if="hasDot" class="fab-dot" />
    <!-- 呼吸光环：常态 3.4s 呼吸；exec（生成中）切 1s 旋转环（原型 #ball::after 两态） -->
    <span class="fab-halo" :class="{ exec }" />
  </div>
</template>

<script setup lang="ts">
/**
 * 悬浮球（批E Task E2 → PROTO PARITY 批E 外围）。
 * 单击=快捷菜单（身份化 3 问，菜单开着再点球=开抽屉）；拖动+松手贴边 24px+位置记忆；
 * 呼吸光环常态循环，exec=生成中旋转环；gray=租户已开未配模型（点击引导，spec G1）；
 * proactive=退修主动提示卡（数据与频控由父组件控）。
 */
import { pushToast } from './toastStore';
import IconSpan from './IconSpan.vue';

export interface BallMenuItem {
  icon: string;
  label: string;
  prompt: string;
}

const props = defineProps<{
  hasDot?: boolean;
  /** 生成中（单例 state.sending）→ 光环切旋转环 */
  exec?: boolean;
  /** 灰球：租户开关开但未配模型 */
  gray?: boolean;
  /** 身份化快捷问（空数组=不出菜单直接开抽屉） */
  menuItems?: BallMenuItem[];
  /** 主动提示卡数据（REVISE 退修提醒）；null=不显示。AU-01：携带直达定位字段 */
  proactive?: { missionName: string; missionId?: string; detailId?: string } | null;
}>();
const emit = defineEmits<{ open: []; ask: [q: string]; 'gray-tip': []; 'pro-accept': []; 'pro-ai': []; 'pro-dismiss': [] }>();

const STORAGE_KEY = 'dcs:assistant:ball-pos';
const pos = ref({ x: 24, y: 24 });
const dragging = ref(false);
const menuOpen = ref(false);

/* AU-08(1007拍板Q9)：快捷菜单点外部/Esc 关闭——此前仅"再点球/快捷问"两条关闭路径（审查实测悬停不消） */
function onDocDown(e: MouseEvent) {
  const t = e.target as HTMLElement;
  // 点球本身交给 onActivate 翻转（mousedown 先于 click，此处放行避免双重切换）
  if (!t.closest('.ballmenu') && !t.closest('.fab-core')) {
    menuOpen.value = false;
  }
}
function onDocKey(e: KeyboardEvent) {
  if (e.key === 'Escape') {
    menuOpen.value = false;
  }
}
watch(menuOpen, (v) => {
  if (v) {
    document.addEventListener('mousedown', onDocDown);
    document.addEventListener('keydown', onDocKey);
  } else {
    document.removeEventListener('mousedown', onDocDown);
    document.removeEventListener('keydown', onDocKey);
  }
});
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocDown);
  document.removeEventListener('keydown', onDocKey);
  window.removeEventListener('resize', onResize);
  if (snapTimer !== null) {
    window.clearTimeout(snapTimer);
  }
});

// 位置记忆（localStorage；拖出边界由 clamp 兜底；极窄视口下 min 可能算出负值，外层再 max(0) 兜底）
try {
  const saved = localStorage.getItem(STORAGE_KEY);
  if (saved) {
    const p = JSON.parse(saved);
    if (typeof p?.x === 'number' && typeof p?.y === 'number') {
      pos.value = {
        x: Math.max(0, Math.min(Math.max(p.x, 0), window.innerWidth - 64)),
        y: Math.max(0, Math.min(Math.max(p.y, 0), window.innerHeight - 64))
      };
    }
  }
} catch {
  /* 存储不可用回默认位 */
}

let downPt: { x: number; y: number } | null = null;
let moved = 0;
/** 主指针 ID：拖动进行中第二根手指落下不得重置起点（否则先抬的手指一松 moved≤6 被误判成单击→"一拖球就没了"） */
let activePointerId: number | null = null;
/** 贴边吸附延时句柄（AU-22：可取消，防旧回调覆写新拖动） */
let snapTimer: number | null = null;
/** 球贴屏幕左半时菜单翻到球右侧弹出（批H dspro P1-3：默认球左侧弹出，贴左屏会整菜单出屏） */
const menuFlip = computed(() => pos.value.x > window.innerWidth / 2);
/** 球底距叠加安全区，iPhone 手势条不压球 */
const fabStyle = computed(() => ({
  right: `${pos.value.x}px`,
  bottom: `calc(${pos.value.y}px + env(safe-area-inset-bottom, 0px))`
}));

function onDown(e: PointerEvent) {
  // 仅主键：右键/中键松手同样派发 pointerup，不过滤会被当成单击激活（开抽屉→球隐藏）
  if (e.button !== 0) {
    return;
  }
  if (activePointerId !== null && e.pointerId !== activePointerId) {
    return;
  }
  downPt = { x: e.clientX, y: e.clientY };
  moved = 0;
  activePointerId = e.pointerId;
  // AU-22: 新拖动开始即取消上一轮未触发的贴边吸附
  if (snapTimer !== null) {
    window.clearTimeout(snapTimer);
    snapTimer = null;
  }
  try {
    // 合成指针事件（自动化/辅助设备注入）无 active pointer 时 capture 会抛——点击语义不依赖它
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);
  } catch {
    /* capture 失败仅影响拖动跟随，up 判定仍走元素级派发 */
  }
}
function onMove(e: PointerEvent) {
  if (!downPt) {
    return;
  }
  if (activePointerId !== null && e.pointerId !== activePointerId) {
    return;
  }
  const dx = downPt.x - e.clientX;
  const dy = downPt.y - e.clientY;
  moved = Math.max(moved, Math.abs(dx) + Math.abs(dy));
  if (moved > 6) {
    dragging.value = true;
    pos.value = {
      x: Math.min(Math.max(window.innerWidth - e.clientX - 28, 0), window.innerWidth - 64),
      y: Math.min(Math.max(window.innerHeight - e.clientY - 28, 0), window.innerHeight - 64)
    };
  }
}
function onUp(e: PointerEvent) {
  if (activePointerId !== null && e.pointerId !== activePointerId) {
    return;
  }
  if (downPt && moved <= 6) {
    onActivate();
  } else if (downPt) {
    // 拖动松手：贴边 24px（按球中心判定左/右）+toast（原型 280ms 缓动后吸附）
    pushToast('位置已记住');
    const cx = window.innerWidth - pos.value.x - 28;
    // AU-22: 存句柄——280ms 内再次拖动会被旧回调覆写位置；新拖动即取消未触发的吸附
    if (snapTimer !== null) {
      window.clearTimeout(snapTimer);
    }
    snapTimer = window.setTimeout(() => {
      snapTimer = null;
      pos.value = { x: cx > window.innerWidth / 2 ? 24 : window.innerWidth - 64 - 24, y: pos.value.y };
      savePos();
    }, 280);
  }
  downPt = null;
  activePointerId = null;
  dragging.value = false;
  savePos();
}
/** 指针流被系统中断（浏览器手势接管/切窗/笔输入派发 pointercancel，或 capture 丢失）：
    只复位拖动态、绝不触发单击激活——否则 downPt 永不复位进入"悬停即跟手"幽灵态，下一次轻点又误开抽屉 */
function onCancel() {
  downPt = null;
  activePointerId = null;
  dragging.value = false;
  if (snapTimer !== null) {
    window.clearTimeout(snapTimer);
    snapTimer = null;
  }
  savePos();
}
/** 窗口变窄（分屏/DevTools/换屏/抽屉开关联动滚动条）时把球拉回屏幕内并持久化——
    此前仅组件挂载时钳制一次，贴左停靠遇变窄球整体出屏且跨路由持续不可见，只有整页刷新才救得回 */
function onResize() {
  const x = Math.max(0, Math.min(Math.max(pos.value.x, 0), window.innerWidth - 64));
  const y = Math.max(0, Math.min(Math.max(pos.value.y, 0), window.innerHeight - 64));
  if (x !== pos.value.x || y !== pos.value.y) {
    pos.value = { x, y };
    savePos();
  }
}
window.addEventListener('resize', onResize);
function savePos() {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(pos.value));
  } catch {
    /* 忽略 */
  }
}

/** 单击语义（原型 toggle）：灰球=引导；菜单开=关菜单并开抽屉；菜单关=开菜单（无菜单项时直接开抽屉） */
function onActivate() {
  if (props.gray) {
    emit('gray-tip');
    return;
  }
  // proactive 卡在场=注意力已在退修提醒上，快捷提问让位，点球直接开面板
  //（2026-10-07 用户定版：快捷提问只在"没有顺便说一句"时才弹）
  if (props.proactive) {
    emit('open');
    return;
  }
  if (menuOpen.value) {
    menuOpen.value = false;
    emit('open');
    return;
  }
  if ((props.menuItems?.length ?? 0) === 0) {
    emit('open');
    return;
  }
  menuOpen.value = true;
}

/** 外部开抽屉时同步收菜单 */
function closeMenu() {
  menuOpen.value = false;
}

/* AU-34(1008)：pulse 死路径已删——原调用方（index.vue onStoppedBall）在抽屉开着时球必隐藏，
   脉冲永空跑；停止反馈由 TurnBlock 停止便条承担 */
defineExpose({ closeMenu });
</script>

<style lang="scss" scoped>
.dcs-fab {
  position: fixed;
  z-index: 900;
  width: 56px;
  height: 56px;
  user-select: none;
  touch-action: none;
  cursor: pointer;

  &.dragging {
    cursor: grabbing;
    .fab-core {
      transform: scale(0.92);
      opacity: 0.7;
    }
  }
}
.fab-core {
  position: absolute;
  inset: 0;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  /* 原型 #ball 同款：左上高光/右下深底三层径向渐变 + 四层投影（Q10 向原型对齐） */
  background:
    radial-gradient(circle at 30% 25%, rgb(255 255 255 / 95%), rgb(255 255 255 / 0%) 32%),
    radial-gradient(circle at 72% 80%, rgb(18 66 128 / 50%), transparent 58%), linear-gradient(145deg, #82c8ff 0%, #409eff 48%, #2a6bc4 100%);
  box-shadow:
    0 8px 20px rgb(42 107 196 / 40%),
    0 2px 6px rgb(42 107 196 / 22%),
    0 0 22px rgb(64 158 255 / 30%),
    inset 0 2px 3px rgb(255 255 255 / 55%),
    inset 0 -4px 8px rgb(8 36 84 / 35%);
  transition:
    transform 0.25s cubic-bezier(0.34, 1.56, 0.64, 1),
    box-shadow 0.25s,
    opacity 0.2s;
  outline: none;

  /* 原型 .glyph 同款球脸投影 */
  svg {
    filter: drop-shadow(0 1px 1px rgb(255 255 255 / 40%));
  }
  &:hover {
    transform: translateY(-3px) scale(1.06);
    box-shadow:
      0 14px 28px rgb(42 107 196 / 48%),
      0 4px 10px rgb(42 107 196 / 26%),
      0 0 30px rgb(64 158 255 / 38%),
      inset 0 2px 3px rgb(255 255 255 / 55%),
      inset 0 -4px 8px rgb(8 36 84 / 35%);
  }
  &:focus-visible {
    box-shadow: 0 0 0 3px var(--el-color-primary-light-7);
  }
}
.gray .fab-core {
  background: linear-gradient(145deg, var(--el-text-color-disabled), var(--el-text-color-placeholder));
  box-shadow: 0 4px 14px rgb(0 0 0 / 16%);
  cursor: not-allowed;
}
.fab-dot {
  position: absolute;
  top: 2px;
  right: 2px;
  width: 12px;
  height: 12px;
  border-radius: 50%;
  background: var(--dcs-color-danger);
  border: 2px solid #fff;
}
.fab-halo {
  position: absolute;
  inset: -7px;
  border-radius: 50%;
  pointer-events: none;
  border: 2.5px solid rgb(64 158 255 / 55%);
  animation: dcs-fab-breath 3.4s ease-out infinite;

  /* 生成中：呼吸圈切旋转环（原型 .exec::after） */
  &.exec {
    animation: dcs-fab-spin 1s linear infinite;
    border-color: rgb(64 158 255 / 85%);
    border-top-color: transparent;
    border-width: 3px;
  }
}
@keyframes dcs-fab-breath {
  0% {
    transform: scale(0.8);
    opacity: 0;
  }
  30% {
    opacity: 0.5;
  }
  100% {
    transform: scale(1.18);
    opacity: 0;
  }
}
@keyframes dcs-fab-spin {
  to {
    transform: rotate(360deg);
  }
}

/* 快捷菜单（原型 ballmenu：球左侧弹出；.flip=球贴左屏翻到右侧，防出屏） */
.ballmenu {
  position: absolute;
  right: 66px;
  bottom: 0;
  width: 176px;
  background: var(--el-bg-color);
  border: 1px solid var(--dcs-border-color-lighter);
  border-radius: 12px;
  box-shadow: 0 8px 24px rgb(0 0 0 / 14%);
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  transform-origin: bottom right;
  animation: dcs-fab-zoom 0.18s ease both;

  .tip {
    font-size: 11px;
    color: var(--dcs-text-placeholder);
    padding: 0 4px 4px;
  }
  .bm {
    height: 34px;
    border: none;
    border-radius: 8px;
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 0 10px;
    font-size: 13px;
    color: var(--dcs-text-regular);
    background: none;
    cursor: pointer;
    text-align: left;

    .bi {
      font-size: 15px;
      flex: none;
    }
    &:hover {
      background: var(--el-color-primary-light-9);
      color: var(--el-color-primary);
    }
  }
}

/* 贴左屏翻转：菜单改从球右侧弹出 */
.dcs-fab.flip .ballmenu {
  right: auto;
  left: 66px;
  transform-origin: bottom left;
}

/* 主动提示卡（原型 proactive：球上方） */
.proactive {
  position: absolute;
  right: 0;
  bottom: 66px;
  width: 248px;
  background: var(--el-bg-color);
  border: 1px solid var(--el-color-primary-light-7);
  border-radius: 12px;
  box-shadow: 0 8px 24px rgb(0 0 0 / 14%);
  padding: 12px;
  transform-origin: bottom right;
  animation: dcs-fab-proactive 0.25s ease both;

  /* 气泡尾巴指向球 */
  &::after {
    content: '';
    position: absolute;
    bottom: -6px;
    right: 20px;
    width: 12px;
    height: 12px;
    background: var(--el-bg-color);
    border-right: 1px solid var(--el-color-primary-light-7);
    border-bottom: 1px solid var(--el-color-primary-light-7);
    transform: rotate(45deg) skew(6deg, 6deg);
  }

  .x {
    position: absolute;
    top: 6px;
    right: 6px;
    border: none;
    background: none;
    cursor: pointer;
    color: var(--dcs-text-placeholder);
    font-size: 12px;
    padding: 2px 4px;

    &:hover {
      color: var(--dcs-text-regular);
    }
  }
  .ph {
    font-size: 12px;
    font-weight: 600;
    color: var(--dcs-text-primary);
    margin-bottom: 6px;
  }
  .pb {
    font-size: 12.5px;
    line-height: 1.65;
    color: var(--dcs-text-regular);
  }
  /* AU-01 次级辅助入口：小字链接形态，低于主按钮一档（主=直达真数据，AI=可解释器） */
  .pa-ai {
    display: block;
    margin-top: 6px;
    border: none;
    background: none;
    padding: 0;
    font-size: 11.5px;
    color: var(--el-color-primary);
    text-decoration: underline;
    text-underline-offset: 2px;
    cursor: pointer;
    opacity: 0.85;

    &:hover {
      opacity: 1;
    }
  }
  .pac {
    display: flex;
    gap: 8px;
    margin-top: 10px;

    button {
      height: 26px;
      border-radius: 6px;
      font-size: 12px;
      cursor: pointer;
      padding: 0 12px;
    }
    .pa-go {
      border: none;
      background: var(--el-color-primary);
      color: #fff;

      &:hover {
        background: var(--el-color-primary-light-3);
      }
    }
    .pa-no {
      border: 1px solid var(--dcs-border-color-light);
      background: none;
      color: var(--dcs-text-secondary);

      &:hover {
        color: var(--dcs-text-regular);
      }
    }
  }
}
/* 手机端主动卡改底部 sheet，不再贴着球浮在小角落遮挡内容 */
@media (max-width: 992px) {
  .dcs-fab {
    width: 60px;
    height: 60px;
  }

  .proactive {
    position: fixed;
    right: 12px;
    bottom: calc(88px + env(safe-area-inset-bottom, 0px));
    left: 12px;
    width: auto;
    max-width: none;
    padding: 14px 14px 12px;
    border-radius: 14px;
    transform-origin: bottom center;
  }

  .proactive::after {
    display: none;
  }

  .proactive .x {
    top: 2px;
    right: 2px;
    width: 44px;
    height: 44px;
    font-size: 14px;
  }

  .proactive .pac button {
    height: 44px;
    padding: 0 16px;
    font-size: 13px;
  }

  .proactive .pa-ai {
    display: flex;
    align-items: center;
    min-height: 44px;
    margin-top: 2px;
  }
}

@keyframes dcs-fab-zoom {
  from {
    opacity: 0;
    transform: scale(0.9);
  }
}
@keyframes dcs-fab-proactive {
  from {
    opacity: 0;
    transform: scale(0.92) translateY(8px);
  }
}
@media (prefers-reduced-motion: reduce) {
  .fab-core,
  .fab-halo {
    transition: none;
    animation: none;
  }
  .ballmenu,
  .proactive {
    animation: none;
  }
}
</style>
