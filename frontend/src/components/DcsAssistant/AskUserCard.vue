<template>
  <div
    ref="rootEl"
    class="dcs-ask"
    tabindex="0"
    role="dialog"
    aria-label="助手需要你选择"
    @mouseenter="snoozeOnce"
    @keydown="onKeydown"
  >
    <div class="ask-head">
      <div class="ask-title">
        <span class="ask-chip">{{ q?.header || '请选择' }}</span>
        <span class="ask-question">{{ q?.question }}</span>
      </div>
      <div class="ask-tools">
        <button
          v-if="showCountdown"
          type="button"
          class="ask-count"
          title="停止倒计时"
          @click.stop="snoozeOnce"
        >
          {{ countdownSeconds }}秒
        </button>
        <button type="button" class="ask-arrow" :disabled="idx === 0" aria-label="上一题" @click="prev">◀</button>
        <span class="ask-n">{{ idx + 1 }} / {{ total }}</span>
        <button type="button" class="ask-arrow" :disabled="idx === total - 1" aria-label="下一题" @click="next">▶</button>
      </div>
    </div>

    <div class="ask-body">
      <div class="ask-opts" role="group" :aria-label="q?.question">
        <button
          v-for="(o, oi) in q?.options ?? []"
          :key="oi"
          type="button"
          class="ask-opt"
          :class="{ on: isPicked(idx, o.label), active: active === oi }"
          :aria-pressed="isPicked(idx, o.label)"
          :disabled="busy"
          @mouseenter="active = oi"
          @click="pick(idx, o.label)"
        >
          <span v-if="q?.multiSelect" class="ask-cb" :class="{ on: isPicked(idx, o.label) }">
            {{ isPicked(idx, o.label) ? '✓' : '' }}
          </span>
          <span v-else class="ask-no">{{ oi + 1 }}.</span>
          <span class="ask-text">
            <span class="ask-label">{{ o.label }}</span>
            <span class="ask-desc">{{ o.description }}</span>
          </span>
        </button>
        <label
          class="ask-opt ask-other"
          :class="{ on: !!(other[idx] ?? '').trim(), active: active === optionCount }"
          @mouseenter="active = optionCount"
        >
          <span v-if="q?.multiSelect" class="ask-cb" :class="{ on: !!(other[idx] ?? '').trim() }">
            {{ (other[idx] ?? '').trim() ? '✓' : '' }}
          </span>
          <span v-else class="ask-no">{{ optionCount + 1 }}.</span>
          <textarea
            v-model="other[idx]"
            rows="1"
            class="ask-input"
            :disabled="busy"
            maxlength="200"
            placeholder="输入你的回答..."
            :aria-label="`${q?.header ?? ''}：其它补充`"
            @focus="onOtherFocus"
            @input="onOtherInput"
            @keydown="onOtherKeydown"
          />
        </label>
      </div>
    </div>

    <div class="ask-foot">
      <span class="ask-hint"><span class="ask-i">i</span>使用 Tab / 上下键选择，回车或空格选中</span>
      <div class="ask-btns">
        <button class="ask-ignore" type="button" title="不用回答这一问（助手会按稳妥默认继续）" :disabled="busy" @click="submit('cancel')">
          忽略
        </button>
        <button class="ask-go" type="button" :class="{ busy }" :disabled="busy" @click="primary">
          {{ idx < total - 1 ? '继续' : '提交' }}
        </button>
      </div>
    </div>
    <p v-if="errText" class="ask-err">{{ errText }}</p>
  </div>
</template>

<script setup lang="ts">
/**
 * 内置结构化反问卡（ZCode ElicitationDialog 同构，2026-11-07 Q2=乙 像素级照搬）：
 * 一次一题；题头=完整问题；右上角倒计时（前 60 秒不显示）+ ◀ 1/N ▶；
 * 单选数字、多选方框；自填 textarea；底部键位提示；最高 72dvh/42rem。
 * 首次有效交互（悬停/点选/改字/翻页/点倒计时）永久停表；最后一题单选即提交。
 */
import type { AssistantAskUserAnswer, AssistantAskUserCard } from '@/api/dcs/assistant';
import { answerAskUser, snoozeAskUser } from '@/api/dcs/assistant';

interface AskUserDone {
  id: string;
  outcome: 'answered' | 'cancelled' | 'timeout' | 'skipped';
  answers: AssistantAskUserAnswer[];
}

const props = defineProps<{ card: AssistantAskUserCard; threadId: string; autoContinue?: boolean }>();
const emit = defineEmits<{ done: [payload: AskUserDone] }>();

const AUTO_MS = 300_000;
const rootEl = ref<HTMLElement | null>(null);
const busy = ref(false);
const errText = ref('');
const idx = ref(0);
const picked = reactive<Record<number, string[]>>({});
const other = reactive<Record<number, string>>({});
const nowMs = ref(Date.now());
const snoozed = ref(props.card.snoozed === true);
const active = ref(-1);
const deadlineAt = ref(
  typeof props.card.deadlineAt === 'number' && props.card.deadlineAt > 0
    ? props.card.deadlineAt
    : Date.now() + (props.card.remainingMs ?? AUTO_MS)
);

const total = computed(() => props.card.questions.length);
const q = computed(() => props.card.questions[idx.value]);
const optionCount = computed(() => q.value?.options?.length ?? 0);
const remainMs = computed(() => (snoozed.value ? 0 : Math.max(0, deadlineAt.value - nowMs.value)));
const autoOn = computed(() => props.card.autoContinue !== false && props.autoContinue !== false);
const countdownWindowMs = computed(() => (props.card.countdownWindowSeconds ?? 60) * 1000);
// ZCode ELICITATION_FINAL_MINUTE_MS：剩不到 60 秒才显示，不是「满 60 秒就显示」
const showCountdown = computed(() =>
  autoOn.value && !snoozed.value && remainMs.value > 0
    && remainMs.value < countdownWindowMs.value
);
const countdownSeconds = computed(() => Math.max(1, Math.floor(remainMs.value / 1000)));

let timer: ReturnType<typeof setInterval> | null = null;
let submitted = false;

onMounted(() => {
  rootEl.value?.focus({ preventScroll: true });
  timer = setInterval(() => {
    nowMs.value = Date.now();
    if (!snoozed.value && !submitted && remainMs.value <= 0) {
      submitted = true;
      // 自动继续=后端按空答案收尾（ZCode accept 空答案）；关掉自动继续=真超时
      emit('done', { id: props.card.id, outcome: autoOn.value ? 'skipped' : 'timeout', answers: [] });
    }
  }, 1000);
  autosize();
});

onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
});

watch(
  () => props.card.snoozed,
  (v) => {
    if (v) snoozed.value = true;
  }
);
watch(
  () => props.card.deadlineAt,
  (v) => {
    if (typeof v === 'number' && v > 0) deadlineAt.value = v;
  }
);

function isPicked(qi: number, label: string) {
  return (picked[qi] ?? []).includes(label);
}

/** 首次有效交互：永久停表；后端失败静默（卡已失效时下一次提交会收到 409，由 submit 兜底） */
function snoozeOnce() {
  if (snoozed.value || submitted) return;
  snoozed.value = true;
  void snoozeAskUser(props.threadId, props.card.id).catch((e) => {
    // 卡已失效（已答/已超时）：立刻收卡，别留一张点了也没用的死卡；网络类失败保留本地停表
    const msg = (e as { message?: string })?.message ?? '';
    if (/失效|超时|排队/.test(msg)) {
      submitted = true;
      emit('done', { id: props.card.id, outcome: 'cancelled', answers: [] });
    }
  });
}

function setActive(i: number) {
  active.value = i;
}

function autosize() {
  const el = rootEl.value?.querySelector<HTMLTextAreaElement>('.ask-input');
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = `${Math.min(el.scrollHeight, 104)}px`;
}

function onOtherFocus() {
  active.value = optionCount.value;
  snoozeOnce();
}

function onOtherInput() {
  snoozeOnce();
  autosize();
}

function onOtherKeydown(e: KeyboardEvent) {
  // 中文输入法组词时按 Enter 只是确认候选，不能误提交（ZCode isImeComposingKeyEvent 同款）
  if (e.isComposing || e.keyCode === 229) return;
  snoozeOnce();
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    primary();
  } else if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
    // 输入框是末尾选项：上下键离开输入框回到选项，避免焦点困在 textarea
    e.preventDefault();
    active.value = e.key === 'ArrowDown' ? 0 : Math.max(0, optionCount.value - 1);
    rootEl.value?.focus({ preventScroll: true });
  } else if (e.key === 'Escape') {
    e.preventDefault();
    if (idx.value > 0) prev();
    else void submit('cancel');
  }
}

/** 单选：选中即前进；最后一题立即提交。多选：只切换，等提交。 */
function pick(qi: number, label: string) {
  if (busy.value || submitted) return;
  snoozeOnce();
  const question = props.card.questions[qi];
  if (!question) return;
  const cur = picked[qi] ?? [];
  if (question.multiSelect) {
    picked[qi] = cur.includes(label) ? cur.filter((x) => x !== label) : [...cur, label];
    return;
  }
  picked[qi] = [label];
  // ZCode：单选点完立即进入下一题；最后一题立即提交（没有等待动画）
  if (qi < props.card.questions.length - 1) {
    next();
  } else {
    void submit('answer');
  }
}

function next() {
  if (idx.value < total.value - 1) {
    idx.value += 1;
    active.value = -1;
    rootEl.value?.focus({ preventScroll: true });
  }
}

function prev() {
  if (idx.value > 0) {
    idx.value -= 1;
    active.value = -1;
    rootEl.value?.focus({ preventScroll: true });
  }
}

function hasAnyAnswer(): boolean {
  return props.card.questions.some((_, i) => (picked[i] ?? []).length > 0 || (other[i] ?? '').trim() !== '');
}

function primary() {
  snoozeOnce();
  if (idx.value < total.value - 1) {
    next();
    return;
  }
  void submit(hasAnyAnswer() ? 'answer' : 'skip');
}

function onKeydown(e: KeyboardEvent) {
  if (e.target instanceof HTMLTextAreaElement) return;
  if (e.key === 'Tab' || e.key === 'ArrowDown' || e.key === 'ArrowUp') {
    e.preventDefault();
    snoozeOnce();
    const max = optionCount.value;
    if (e.key === 'ArrowUp' || (e.key === 'Tab' && e.shiftKey)) {
      active.value = active.value <= 0 ? max : active.value - 1;
    } else {
      active.value = active.value >= max ? 0 : active.value + 1;
    }
    if (active.value === max) {
      rootEl.value?.querySelector<HTMLTextAreaElement>('.ask-input')?.focus();
    } else {
      rootEl.value?.focus({ preventScroll: true });
    }
    return;
  }
  if (e.key === 'Enter' || e.key === ' ') {
    e.preventDefault();
    snoozeOnce();
    if (active.value >= 0 && active.value < optionCount.value) {
      pick(idx.value, q.value?.options?.[active.value]?.label ?? '');
    } else if (active.value === optionCount.value) {
      rootEl.value?.querySelector<HTMLTextAreaElement>('.ask-input')?.focus();
    } else {
      primary();
    }
    return;
  }
  if (e.key === 'Escape') {
    e.preventDefault();
    snoozeOnce();
    if (idx.value > 0) prev();
    else void submit('cancel');
    return;
  }
  if (/^[1-9]$/.test(e.key)) {
    const i = Number(e.key) - 1;
    if (i < optionCount.value) {
      pick(idx.value, q.value?.options?.[i]?.label ?? '');
    }
  }
}

async function submit(action: 'answer' | 'cancel' | 'skip') {
  if (busy.value || submitted) return;
  snoozeOnce();
  busy.value = true;
  errText.value = '';
  const answers: AssistantAskUserAnswer[] = props.card.questions.map((question, i) => {
    const one: AssistantAskUserAnswer = {
      header: question.header,
      question: question.question,
      picked: [...(picked[i] ?? [])]
    };
    const o = (other[i] ?? '').trim();
    if (o) one.other = o;
    return one;
  });
  // 忽略=用户明确不答（cancel）；空着点继续/提交=提交空答案（模型按稳妥默认走，ZCode 同义）
  const apiAction: 'answer' | 'cancel' = action === 'cancel' ? 'cancel' : 'answer';
  const outcome: AskUserDone['outcome'] = action === 'answer' ? 'answered' : action === 'skip' ? 'skipped' : 'cancelled';
  try {
    await answerAskUser(props.threadId, props.card.id, apiAction, action === 'cancel' ? [] : answers);
    submitted = true;
    emit('done', { id: props.card.id, outcome, answers: action === 'cancel' ? [] : answers });
  } catch (e) {
    const msg = (e as { message?: string })?.message || '网络异常，稍后再试（超时前仍可提交）';
    errText.value = msg;
    busy.value = false;
    if (action !== 'answer' || msg.includes('失效') || msg.includes('超时') || msg.includes('排队')) {
      submitted = true;
      emit('done', { id: props.card.id, outcome: action === 'answer' ? 'cancelled' : outcome, answers: [] });
    }
  }
}
</script>

<style lang="scss" scoped>
/* ZCode ElicitationDialog 的几何/层级：rounded-2xl / p-3 / 72dvh / 42rem / shadow-xs。
   颜色仍走本仓主题令牌，亮暗两套自动跟随（Q2=乙：结构像素级，颜色不硬编码）。 */
.dcs-ask {
  width: 100%;
  max-height: min(72dvh, 42rem);
  border: 1px solid var(--app-border, rgba(13, 13, 13, 0.1));
  border-radius: 16px;
  background: var(--app-surface, #fff);
  box-shadow: 0 1px 2px rgba(13, 13, 13, 0.05);
  padding: 12px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  overflow: hidden;
  // 全局 [tabindex]:focus-visible 规则会把圆角改成 --app-radius-sm(4.8px) 并加蓝框；
  // ZCode 卡没有焦点环，这里按卡自己的 16px 圆角/无 outline 覆盖回去
  &.dcs-ask:focus-visible {
    border-radius: 16px;
    outline: none;
  }
}

.ask-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex: none;
}
.ask-title {
  display: flex;
  align-items: baseline;
  gap: 8px;
  min-width: 0;
  flex: 1;
  flex-wrap: wrap;
}
.ask-chip {
  flex: none;
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  border: 1px solid var(--app-border, rgba(13, 13, 13, 0.1));
  border-radius: 6px;
  padding: 0 6px;
  font-size: 12px;
  line-height: 18px;
  color: var(--app-muted, #666);
}
.ask-question {
  min-width: 0;
  font-size: 14px;
  line-height: 24px;
  font-weight: 500;
  color: var(--app-text, #181818);
  overflow-wrap: anywhere;
}
.ask-tools {
  flex: none;
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--app-faint, #8a8a8a);
  font-size: 14px;
  white-space: nowrap;
}
.ask-count {
  height: 24px;
  border: 1px solid var(--app-border, rgba(13, 13, 13, 0.1));
  border-radius: 6px;
  background: var(--app-surface, #fff);
  padding: 0 7px;
  font: inherit;
  font-size: 12px;
  color: var(--app-muted, #666);
  cursor: pointer;
  font-variant-numeric: tabular-nums;
  &:hover {
    background: color-mix(in srgb, var(--app-text, #181818) 6%, transparent);
  }
}
.ask-arrow {
  border: 0;
  background: transparent;
  color: var(--app-faint, #8a8a8a);
  cursor: pointer;
  font-size: 14px;
  padding: 1px 3px;
  &:disabled {
    opacity: 0.35;
    cursor: default;
  }
}
.ask-n {
  font-size: 14px;
  color: var(--app-faint, #8a8a8a);
  font-variant-numeric: tabular-nums;
}

.ask-body {
  min-height: 0;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding-right: 2px;
}
.ask-opts {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.ask-opt {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 36px;
  text-align: left;
  border: 1px solid transparent;
  border-radius: 12px;
  background: transparent;
  padding: 8px 12px;
  cursor: pointer;
  font: inherit;
  &:hover {
    background: color-mix(in srgb, var(--app-text, #181818) 6%, transparent);
  }
  &.on {
    background: color-mix(in srgb, var(--app-text, #181818) 6%, transparent);
  }
  &.active {
    background: color-mix(in srgb, var(--app-text, #181818) 6%, transparent);
  }
  &:disabled {
    opacity: 0.55;
    cursor: not-allowed;
  }
}
.ask-no {
  flex: none;
  width: 20px;
  text-align: right;
  font-size: 14px;
  line-height: 20px;
  font-weight: 500;
  color: var(--app-faint, #8a8a8a);
  font-variant-numeric: tabular-nums;
}
.ask-opt.on .ask-no {
  color: var(--app-text, #181818);
}
.ask-cb {
  flex: none;
  width: 16px;
  height: 16px;
  border: 1px solid var(--app-border, rgba(13, 13, 13, 0.1));
  border-radius: 4px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  line-height: 1;
  color: var(--app-surface, #fff);
  &.on {
    background: var(--app-text, #181818);
    border-color: var(--app-text, #181818);
  }
}
.ask-text {
  flex: 1;
  min-width: 0;
  font-size: 14px;
  line-height: 20px;
  overflow-wrap: anywhere;
}
.ask-label {
  font-weight: 500;
  color: var(--app-text, #181818);
}
.ask-desc {
  margin-left: 8px;
  color: var(--app-muted, #666);
}
.ask-other {
  align-items: center;
  cursor: text;
  border-color: transparent;
  background: transparent;
  .ask-no {
    align-self: flex-start;
    margin-top: 1px;
  }
  &:focus-within {
    background: color-mix(in srgb, var(--app-text, #181818) 6%, transparent);
  }
}
.ask-input {
  flex: 1;
  min-width: 0;
  border: 0;
  padding: 2px 0;
  font: inherit;
  font-size: 14px;
  font-weight: 500;
  line-height: 20px;
  background: transparent;
  color: var(--app-text, #181818);
  resize: none;
  max-height: 5lh;
  overflow-y: auto;
  &::placeholder {
    color: var(--app-faint, #8a8a8a);
  }
  &:focus-visible {
    outline: none;
  }
}

.ask-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  flex-wrap: wrap;
  flex: none;
}
.ask-hint {
  flex: 1;
  min-width: 160px;
  font-size: 14px;
  line-height: 1.5;
  color: var(--app-faint, #8a8a8a);
}
.ask-i {
  display: inline-flex;
  width: 16px;
  height: 16px;
  margin-right: 8px;
  border: 1px solid var(--app-faint, #8a8a8a);
  border-radius: 50%;
  align-items: center;
  justify-content: center;
  font-size: 10px;
}
.ask-btns {
  display: flex;
  gap: 8px;
  margin-left: auto;
  flex-wrap: wrap;
  flex: none;
  button {
    height: 36px;
    border-radius: 8px;
    border: 1px solid var(--app-border, rgba(13, 13, 13, 0.1));
    font-size: 14px;
    padding: 0 14px;
    cursor: pointer;
    background: var(--app-surface, #fff);
    color: var(--app-muted, #666);
    &:disabled {
      opacity: 0.55;
      cursor: not-allowed;
    }
  }
  .ask-go {
    background: var(--app-text, #181818);
    border-color: var(--app-text, #181818);
    color: var(--app-surface, #fff);
    &:not(:disabled):hover {
      background: color-mix(in srgb, var(--app-text, #181818) 82%, #000);
    }
    &.busy {
      opacity: 0.6;
    }
  }
}
.ask-err {
  margin: 0;
  color: var(--el-color-danger);
  font-size: 12px;
  text-align: right;
}

</style>
