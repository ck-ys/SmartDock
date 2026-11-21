<template>
  <div
    ref="rootEl"
    class="dcs-pfc"
    :data-plan-id="plan.planId"
    role="dialog"
    aria-label="计划待确认"
    tabindex="-1"
    @keydown="onKeydown"
  >
    <div class="pfc-hd">
      <span class="pfc-kicker">计划待确认</span>
      <b class="pfc-title">{{ plan.title ?? `这个任务我打算分 ${plan.steps.length} 步做` }}</b>
      <span v-if="tail" class="pfc-tail" title="卡号尾号，用来区分同一线程里的多张卡">尾号 {{ tail }}</span>
    </div>
    <div v-if="stepSummary" class="pfc-steps">{{ stepSummary }}</div>

    <div class="pfc-rows" role="group" aria-label="计划操作">
      <button
        v-for="(row, i) in rows"
        :key="row.key"
        :ref="(el) => setRowRef(el, i)"
        class="pfc-row"
        :class="{ on: active === i, picked: picked === i, danger: row.key === 'cancel' }"
        type="button"
        :aria-pressed="picked === i"
        @focus="active = i"
        @click="pick(i)"
      >
        <span class="pfc-no">{{ i + 1 }}.</span>
        <b class="pfc-lb">{{ row.label }}</b>
        <span class="pfc-ds">{{ row.desc }}</span>
      </button>

      <!-- 第 4 行：自由回答（ZCode 同款解剖——它就是第 N+1 个选项，参与上下键循环） -->
      <div class="pfc-row pfc-free" :class="{ on: active === rows.length }" @click="focusFree">
        <span class="pfc-no">{{ rows.length + 1 }}.</span>
        <textarea
          ref="inputEl"
          v-model="feedback"
          class="pfc-input"
          rows="1"
          maxlength="300"
          placeholder="输入你的回答…"
          aria-label="自由回答（可补充说明）"
          @focus="active = rows.length"
          @input="active = rows.length"
          @keydown.enter.exact="onInputEnter"
        />
      </div>
    </div>

    <div class="pfc-ft">
      <span class="pfc-hint">使用 Tab / 上下键选择，回车或空格选中</span>
      <div class="pfc-btns">
        <button class="pfc-btn" type="button" @click="emit('collapse')">忽略</button>
        <button class="pfc-btn pfc-go" type="button" :disabled="!canSubmit" @click="submit">提交</button>
      </div>
    </div>
    <p class="pfc-err" aria-live="polite">{{ errText }}</p>
  </div>
</template>

<script setup lang="ts">
/**
 * 计划确认表单卡（2026-11-04 拍板题四甲）：照 ZCode 追问卡解剖做在消息流里——
 * 编号行（1/2/3 是动作，第 4 行是自由输入）+ 底栏提示 + 忽略 / 提交。
 * 确认期间宿主把输入框收起（卡下方没有第二块输入区）；不预选（active=-1），
 * 选中不靠背景色（序号变色 + 行底 5% 灰），Tab / ↑↓ / Enter / Space 全接管。
 */
import type { AssistantPlan } from './planDock';
import { planTail } from './planDock';

const props = defineProps<{ plan: AssistantPlan }>();
const emit = defineEmits<{
  confirm: [planId: string];
  adjust: [planId: string, feedback: string];
  cancel: [planId: string];
  /** 忽略 = 暂不决定：卡留在消息流里，输入框还回来（细条可再展开） */
  collapse: [];
}>();

const QN = '①②③④⑤⑥⑦⑧';
const rootEl = ref<HTMLElement | null>(null);
const inputEl = ref<HTMLTextAreaElement | null>(null);
const rowEls = ref<HTMLButtonElement[]>([]);
/** ZCode 同款：不预选 */
const active = ref(-1);
/** 已选中的行（和焦点分开：Tab 到底栏时选中不丢） */
const picked = ref(-1);
const feedback = ref('');
const errText = ref('');

const tail = computed(() => planTail(props.plan.planId));
const stepSummary = computed(() => props.plan.steps.map((s, i) => `${QN[i] ?? i + 1} ${s.label}`).join(' · '));
const rows = computed(() => [
  { key: 'start', label: '开始执行', desc: `按这 ${props.plan.steps.length} 步做` },
  { key: 'adjust', label: '改一下', desc: '写一句要改的地方' },
  { key: 'cancel', label: '取消这张卡', desc: '只作废这张，不影响别的卡' }
]);
const canSubmit = computed(() => feedback.value.trim() !== '' || picked.value >= 0);

watch(
  () => props.plan.planId,
  () => {
    active.value = -1;
    picked.value = -1;
    feedback.value = '';
    errText.value = '';
  }
);

// 卡首次出现时接住焦点（旧 PlanDock 的落点约定）：
// 有草稿/用户正在别处打字时不抢焦点（Safari 组词时抢焦点会中断输入法）；
// 卡被正文顶出视口时带进视野。
onMounted(() => {
  nextTick(() => {
    const ae = document.activeElement as HTMLElement | null;
    const typing = !!ae && (ae.tagName === 'INPUT' || ae.tagName === 'TEXTAREA' || ae.isContentEditable);
    if (typing) {
      return;
    }
    rootEl.value?.scrollIntoView({ block: 'nearest' });
    rootEl.value?.focus({ preventScroll: true });
  });
});

function setRowRef(el: any, i: number) {
  if (el) {
    rowEls.value[i] = el as HTMLButtonElement;
  }
}

function focusAt(i: number) {
  active.value = i;
  nextTick(() => {
    if (i === rows.value.length) {
      inputEl.value?.focus({ preventScroll: true });
      return;
    }
    rowEls.value[i]?.focus({ preventScroll: true });
  });
}

/** 点行 = 选中（ZCode 里点选项也不直接提交，要再按「提交」） */
function pick(i: number) {
  active.value = i;
  errText.value = '';
  // 选中与焦点分开：Tab 去底栏时选中不能跟着丢（否则「提交」变灰、键盘走不到）
  if (i < rows.value.length) {
    picked.value = i;
  }
  if (i === 1) {
    // 「改一下」：选中即把光标送进第 4 行，少点一次
    nextTick(() => inputEl.value?.focus({ preventScroll: true }));
    return;
  }
}

/** 点第 4 行只把光标送进输入框，不改已选行——
 *  行语义冲突留给 submit() 拦截提示，绝不静默把「取消」改成「改一下」 */
function focusFree() {
  active.value = rows.value.length;
  nextTick(() => inputEl.value?.focus({ preventScroll: true }));
}

function submit() {
  const planId = props.plan.planId;
  if (!planId) {
    return;
  }
  const text = feedback.value.trim();
  const row = rows.value[picked.value];
  // 第 4 行写了字又去点「开始执行/取消」：不静默丢字，也不偷换语义，先让用户自己选清楚
  if (text && row && (row.key === 'start' || row.key === 'cancel')) {
    errText.value = '第 4 行还写着修改意见——要按它改请选「改一下」；要开始或取消请先清空第 4 行';
    return;
  }
  // 行语义优先：明确点了「开始执行/取消这张卡」就按它走，
  // 只有停在自由输入行或还没选行时，才把文字当成「改一下」
  if (row?.key === 'start') {
    emit('confirm', planId);
    return;
  }
  if (row?.key === 'cancel') {
    emit('cancel', planId);
    return;
  }
  if (text) {
    emit('adjust', planId, text);
    return;
  }
  if (row?.key === 'adjust') {
    focusAt(rows.value.length);
    errText.value = '在下面第 4 行写一句要改的地方';
    return;
  }
  errText.value = '先选一行，或在第 4 行写一句';
}

// Vue 的 .enter 修饰符不看输入法组词：中文选字确认的 Enter 不能当提交
function onInputEnter(e: KeyboardEvent) {
  // Safari/旧 WebKit 选字确认：isComposing=false 但 keyCode=229
  if (e.isComposing || e.keyCode === 229) {
    return;
  }
  e.preventDefault();
  submit();
}

function onKeydown(e: KeyboardEvent) {
  // 输入法组词中的按键属于输入法（含 Safari 的 keyCode 229）
  if (e.isComposing || e.keyCode === 229) {
    return;
  }
  // Ctrl/Cmd/Alt 组合是浏览器/系统快捷键，绝不顺带触发计划动作
  if (e.ctrlKey || e.metaKey || e.altKey) {
    return;
  }
  const total = rows.value.length + 1;
  const inField = (e.target as HTMLElement | null)?.tagName === 'TEXTAREA';

  if (e.key === 'Escape') {
    e.preventDefault();
    e.stopPropagation();
    emit('collapse');
    return;
  }
  if (e.key === 'Tab') {
    // 行内循环；到第一行/最后一行就把 Tab 还给浏览器——否则底栏「忽略/提交」永远轮不到
    const dir = e.shiftKey ? -1 : 1;
    if (active.value === -1) {
      // 刚挂载/焦点在卡根：Tab 进第一行，Shift+Tab 放行（不制造焦点陷阱）
      if (e.shiftKey) {
        return;
      }
      e.preventDefault();
      focusAt(0);
      return;
    }
    if ((dir === 1 && active.value === total - 1) || (dir === -1 && active.value === 0)) {
      return;
    }
    e.preventDefault();
    focusAt((active.value + dir + total) % total);
    return;
  }
  if (inField) {
    return;
  }
  if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault();
    if (active.value === -1) {
      focusAt(0);
      return;
    }
    const dir = e.key === 'ArrowDown' ? 1 : -1;
    const next = (active.value + dir + total) % total;
    // 方向键=选择（焦点跟随选中）；Tab 只移动焦点，选中留在原行
    picked.value = next < rows.value.length ? next : -1;
    focusAt(next);
    return;
  }
  if (e.key === 'Enter' || e.key === ' ') {
    // 只接管行按钮；底栏按钮和输入框保留原生行为（否则「提交」按 Enter 会被劫持成重选行）
    if (e.target instanceof HTMLElement && e.target.classList.contains('pfc-row')) {
      e.preventDefault();
      pick(active.value);
    }
  }
}

defineExpose({ focus: () => rootEl.value?.focus({ preventScroll: true }) });
</script>

<style lang="scss" scoped>
/* 视觉语言：ZCode 追问卡——白卡、1px 10% 黑描边、无阴影无渐变；选中=5% 黑底+序号变深 */
.dcs-pfc {
  border: 1px solid var(--app-border, rgba(13, 13, 13, 0.1));
  border-radius: 12px;
  background: var(--app-surface, #fff);
  padding: 12px 14px 10px;
  &:focus-visible {
    outline: 2px solid var(--el-color-primary-light-5);
    outline-offset: -2px;
  }
}
.pfc-hd {
  display: flex;
  align-items: baseline;
  gap: 8px;
  flex-wrap: wrap;
}
.pfc-kicker {
  flex: none;
  font-size: 11px;
  line-height: 18px;
  color: var(--app-muted, #666);
  background: var(--el-fill-color-light, #f5f5f5);
  border-radius: 6px;
  padding: 0 7px;
}
.pfc-title {
  font-size: 14px;
  line-height: 1.4;
  color: var(--app-text, #181818);
  font-weight: 600;
}
.pfc-tail {
  margin-left: auto;
  flex: none;
  font-size: 11px;
  color: var(--app-faint, #8a8a8a);
  font-variant-numeric: tabular-nums;
}
.pfc-steps {
  margin-top: 3px;
  overflow: hidden;
  font-size: 12px;
  line-height: 1.5;
  color: var(--app-muted, #666);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pfc-rows {
  display: flex;
  flex-direction: column;
  gap: 1px;
  margin-top: 8px;
}
.pfc-row.picked {
  background: color-mix(in srgb, var(--app-text, #181818) 6%, transparent);
}
.pfc-row {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 2px 8px;
  width: 100%;
  min-height: 32px;
  padding: 5px 8px;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: var(--app-text, #181818);
  text-align: left;
  cursor: pointer;
  font: inherit;

  &:hover,
  &.on {
    background: color-mix(in srgb, var(--app-text, #181818) 6%, transparent);
  }
  &:disabled {
    cursor: not-allowed;
    opacity: 0.6;
  }
}
.pfc-no {
  flex: none;
  width: 15px;
  font-size: 12px;
  line-height: 20px;
  color: var(--app-faint, #8a8a8a);
  text-align: right;
  font-variant-numeric: tabular-nums;
}
.pfc-row.picked .pfc-no {
  color: var(--app-text, #181818);
  font-weight: 600;
}
.pfc-lb {
  flex: none;
  font-size: 13px;
  font-weight: 600;
  line-height: 20px;
}
.pfc-ds {
  flex: 1 1 120px;
  min-width: 0;
  font-size: 12px;
  line-height: 20px;
  color: var(--app-muted, #666);
  overflow-wrap: anywhere;
}
.pfc-row.danger .pfc-lb {
  color: var(--el-color-danger, #d54941);
}
.pfc-free {
  align-items: flex-start;
  cursor: text;
  border-color: var(--app-border, rgba(13, 13, 13, 0.1));
  background: var(--app-surface, #fff);
  &:hover {
    background: var(--app-surface, #fff);
  }
  &.on {
    background: var(--app-surface, #fff);
    border-color: var(--app-control, rgba(13, 13, 13, 0.12));
  }
  .pfc-no {
    line-height: 24px;
  }
}
.pfc-input {
  flex: 1;
  min-width: 0;
  padding: 2px 0;
  border: 0;
  background: transparent;
  color: var(--app-text, #181818);
  font: inherit;
  font-size: 13px;
  line-height: 20px;
  resize: none;
  field-sizing: content;
  max-height: 116px;
  &:focus {
    outline: none;
  }
  &::placeholder {
    color: var(--app-faint, #8a8a8a);
  }
}
.pfc-ft {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 8px;
}
.pfc-hint {
  flex: 1;
  min-width: 160px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--app-faint, #8a8a8a);
}
.pfc-btns {
  display: flex;
  gap: 8px;
  flex: none;
}
.pfc-btn {
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--app-border, rgba(13, 13, 13, 0.1));
  border-radius: 8px;
  background: var(--app-surface, #fff);
  color: var(--app-muted, #666);
  font-size: 12px;
  cursor: pointer;

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}
.pfc-go {
  border-color: var(--app-text, #181818);
  background: var(--app-text, #181818);
  color: var(--app-surface, #fff);
  &:not(:disabled):hover {
    background: color-mix(in srgb, var(--app-text, #181818) 82%, #000);
  }
}
.pfc-err {
  margin: 4px 0 0;
  min-height: 0;
  font-size: 12px;
  color: var(--el-color-danger, #d54941);
  &:empty {
    display: none;
  }
}
@media (max-width: 480px) {
  .pfc-row {
    min-height: 40px;
  }
  .pfc-ds {
    display: none;
  }
  .pfc-hint {
    display: none;
  }
  .pfc-btns {
    width: 100%;
    justify-content: flex-end;
  }
}
</style>
