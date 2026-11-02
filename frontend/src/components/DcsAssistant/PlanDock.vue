<template>
  <div ref="rootEl" class="dcs-plan-dock" :class="{ full }" role="region" aria-label="计划待确认" tabindex="-1" @keydown="onKeydown">
    <div class="pd-box">
      <div class="pd-head">
        <span class="pd-kicker"
          >计划待确认<span v-if="tail"> · 尾号 {{ tail }}</span></span
        >
        <!-- 正在写修改意见时不给“收起”，避免半句话被卸载吞掉；Esc 先回三行再收 -->
        <button v-if="canCollapse && !adjusting" class="pd-collapse" type="button" title="先收起，计划还在" @click="emit('collapse')">收起</button>
      </div>
      <div class="pd-title">{{ plan.title ?? `这个任务我打算分 ${plan.steps.length} 步做` }}</div>
      <div v-if="stepSummary" class="pd-steps">{{ stepSummary }}</div>

      <div class="pd-rows" role="listbox" aria-label="计划操作">
        <button
          v-for="(row, i) in rows"
          :key="row.key"
          :ref="(el) => setRowRef(el, i)"
          class="pd-row"
          :class="{ on: active === i, danger: row.key === 'cancel' }"
          type="button"
          role="option"
          :aria-selected="active === i"
          @mouseenter="active = i"
          @focus="active = i"
          @click="choose(i)"
        >
          <b>{{ i + 1 }}</b>
          <span>{{ row.label }}</span>
          <em v-if="row.hint">{{ row.hint }}</em>
        </button>
      </div>

      <div v-if="adjusting" class="pd-adjust">
        <input
          ref="inputEl"
          v-model="feedback"
          class="pd-input"
          type="text"
          maxlength="200"
          placeholder="写一句要改的地方，例如：第 2 步先别发通知"
          aria-label="修改意见"
          @keydown.enter.prevent="onAdjustEnter"
          @keydown.esc.stop.prevent="backToRows"
        />
        <button class="pd-send" type="button" :disabled="!feedback.trim()" @click="submitAdjust">按修改做</button>
      </div>

      <div class="pd-foot">
        <span v-if="adjusting">Enter 发送 · Esc 返回</span>
        <span v-else>↑↓ 选择 · Enter 确认 · Esc 取消 · 1/2/3 直达</span>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 计划决策停靠区（2026-11-02 拍板 Q1/Q2/Q3/Q6-A）：
 * 计划待确认且输入框为空（或用户从细条主动展开）时，占据输入框位置。
 * 三行编号：1 开始执行 / 2 改一下 / 3 取消这张卡；↑↓ 移动、Enter 确认、
 * Esc 取消、1/2/3 直达。改一下只收一句反馈，不把整份计划回填输入框。
 */
import { planTail, type AssistantPlan } from './planDock';

const props = defineProps<{
  plan: AssistantPlan;
  full?: boolean;
  /** 用户有草稿时允许「收起」回到输入框；草稿为空时停靠区是唯一入口，不给收起 */
  canCollapse?: boolean;
}>();
const emit = defineEmits<{
  confirm: [planId?: string];
  adjust: [planId: string | undefined, feedback: string];
  cancel: [planId?: string];
  collapse: [];
}>();

const QN = '①②③④⑤⑥⑦⑧';
const rootEl = ref<HTMLElement | null>(null);
const inputEl = ref<HTMLInputElement | null>(null);
const rowEls = ref<HTMLButtonElement[]>([]);
const active = ref(0);
const adjusting = ref(false);
const feedback = ref('');

const tail = computed(() => planTail(props.plan.planId));
const stepSummary = computed(() => props.plan.steps.map((s, i) => `${QN[i] ?? i + 1} ${s.label}`).join(' · '));
const rows = computed(() => [
  { key: 'start', label: '开始执行', hint: `按这 ${props.plan.steps.length} 步做` },
  { key: 'adjust', label: '改一下', hint: '写一句要改的地方' },
  { key: 'cancel', label: '取消这张卡', hint: tail.value ? `只作废这张 · 尾号 ${tail.value}` : '只作废这张，不影响别的卡' }
]);

watch(
  () => props.plan.planId,
  () => {
    active.value = 0;
    adjusting.value = false;
    feedback.value = '';
  }
);

onMounted(() => {
  nextTick(() => rootEl.value?.focus({ preventScroll: true }));
});

function setRowRef(el: any, i: number) {
  if (el) {
    rowEls.value[i] = el as HTMLButtonElement;
  }
}

function focusRow(i: number) {
  active.value = i;
  nextTick(() => rowEls.value[i]?.focus({ preventScroll: true }));
}

function choose(i: number) {
  const row = rows.value[i];
  if (!row) {
    return;
  }
  // 没有卡号时 confirm/cancel 会退化成“本线程任意一张 pending”，一律不动作
  if (!props.plan.planId) {
    return;
  }
  if (row.key === 'start') {
    emit('confirm', props.plan.planId);
    return;
  }
  if (row.key === 'adjust') {
    startAdjust();
    return;
  }
  emit('cancel', props.plan.planId);
}

function startAdjust() {
  active.value = 1;
  adjusting.value = true;
  nextTick(() => inputEl.value?.focus({ preventScroll: true }));
}

function submitAdjust() {
  const text = feedback.value.trim();
  if (!text || !props.plan.planId) {
    return;
  }
  emit('adjust', props.plan.planId, text);
}

// Vue 的 .enter 修饰符不看输入法组词：中文选字确认的 Enter 不能当「发送」
function onAdjustEnter(e: KeyboardEvent) {
  if (e.isComposing) {
    return;
  }
  submitAdjust();
}

function backToRows() {
  adjusting.value = false;
  focusRow(1);
}

function onKeydown(e: KeyboardEvent) {
  const target = e.target as HTMLElement | null;
  const tag = target?.tagName ?? '';
  const inField = tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT';

  // 输入法组词中的 Enter/Esc 属于输入法，不是计划决策
  if (e.isComposing) {
    return;
  }
  // Ctrl+1/Cmd+1/Alt+1 是浏览器/系统快捷键，绝不能顺带触发“开始执行”
  if (e.ctrlKey || e.metaKey || e.altKey) {
    return;
  }

  if (e.key === 'Escape') {
    e.preventDefault();
    e.stopPropagation();
    if (adjusting.value) {
      backToRows();
      return;
    }
    emit('cancel', props.plan.planId);
    return;
  }
  if (inField) {
    return;
  }
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault();
    const dir = e.key === 'ArrowDown' ? 1 : -1;
    focusRow((active.value + dir + rows.value.length) % rows.value.length);
    return;
  }
  if (e.key === 'Enter') {
    // 行按钮的 Enter 统一走 active：鼠标悬停会把高亮移到另一行，
    // 若交给原生 click 会点到“有焦点但没高亮”的旧行（误执行/误取消）。
    if (target?.classList.contains('pd-row')) {
      e.preventDefault();
      choose(active.value);
      return;
    }
    // 收起/按修改做等普通按钮保留原生行为
    if (tag === 'BUTTON') {
      return;
    }
    e.preventDefault();
    choose(active.value);
    return;
  }
  if (e.key === '1' || e.key === '2' || e.key === '3') {
    e.preventDefault();
    choose(Number(e.key) - 1);
  }
}

defineExpose({ focus: () => rootEl.value?.focus({ preventScroll: true }) });
</script>

<style lang="scss" scoped>
.dcs-plan-dock {
  flex: none;
  width: 100%;
  padding: 8px 14px 6px;

  &:focus-visible {
    outline: 2px solid var(--el-color-primary-light-5);
    outline-offset: -2px;
  }

  &.full {
    padding: 8px 24px 6px;
    background: var(--el-fill-color-light, #f5f7fa);

    .pd-box {
      max-width: var(--dcs-col-max, 900px);
      margin: 0 auto;
    }
  }

  @media (max-width: 992px) {
    padding: 6px 10px 4px;
  }
}
.pd-box {
  border: 1px solid var(--dcs-border-color);
  border-radius: 10px;
  background: var(--el-bg-color);
  padding: 10px 12px 8px;
}
.pd-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.pd-kicker {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--el-color-primary);
  letter-spacing: 0.02em;
}
.pd-collapse {
  flex: none;
  border: 0;
  border-radius: 4px;
  padding: 2px 4px;
  background: transparent;
  color: var(--dcs-text-placeholder);
  font-size: 11.5px;
  cursor: pointer;

  &:hover {
    color: var(--el-color-primary);
    background: var(--el-fill-color-light);
  }
}
.pd-title {
  margin-top: 2px;
  font-size: 13px;
  font-weight: 600;
  line-height: 1.35;
  color: var(--dcs-text-primary);
}
.pd-steps {
  margin-top: 4px;
  overflow: hidden;
  font-size: 11.5px;
  color: var(--dcs-text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pd-rows {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: 8px;
}
.pd-row {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  min-height: 38px;
  padding: 6px 8px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--dcs-text-primary);
  text-align: left;
  cursor: pointer;
  transition:
    background 0.15s,
    border-color 0.15s;

  &:hover,
  &.on {
    background: var(--el-fill-color-light);
    border-color: var(--dcs-border-color-light);
  }

  b {
    flex: none;
    width: 16px;
    font-size: 12px;
    color: var(--dcs-text-secondary);
    text-align: center;
  }
  &.on b {
    color: var(--el-color-primary);
  }
  span {
    font-size: 13px;
    font-weight: 500;
  }
  em {
    margin-left: auto;
    overflow: hidden;
    font-style: normal;
    font-size: 11.5px;
    color: var(--dcs-text-placeholder);
    text-overflow: ellipsis;
    white-space: nowrap;
  }
  &.danger span {
    color: var(--dcs-color-danger);
  }
  &.danger.on b {
    color: var(--dcs-color-danger);
  }
}
.pd-adjust {
  display: flex;
  gap: 8px;
  margin-top: 6px;
}
.pd-input {
  flex: 1;
  min-width: 0;
  padding: 7px 10px;
  border: 1px solid var(--dcs-border-color);
  border-radius: 8px;
  background: var(--el-bg-color);
  color: var(--dcs-text-primary);
  font-size: 12.5px;
  outline: none;

  &:focus {
    border-color: var(--el-color-primary);
    box-shadow: 0 0 0 2px var(--el-color-primary-light-8);
  }
}
.pd-send {
  flex: none;
  padding: 0 12px;
  border: 1px solid var(--el-color-primary);
  border-radius: 8px;
  background: var(--el-color-primary);
  color: var(--app-primary-ink);
  font-size: 12.5px;
  cursor: pointer;

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}
.pd-foot {
  margin-top: 6px;
  font-size: 11px;
  line-height: 1.4;
  color: var(--dcs-text-placeholder);
}
@media (max-width: 480px) {
  .pd-title {
    font-size: 12.5px;
  }
  .pd-row {
    min-height: 44px;
  }
  // 保留行尾说明（尤其“只作废这张 · 尾号 XXXX”），放不下时省略号截断
  .pd-row em {
    font-size: 11px;
  }
}
</style>
