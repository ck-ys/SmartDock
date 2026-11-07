<template>
  <div ref="rootEl" class="dcs-ask" tabindex="-1" role="dialog" aria-label="助手需要你选择">
    <div class="ah">
      <span class="ah-ic"><IconSpan name="help" /></span>
      <b>{{ card.questions[idx]?.header || '请选择' }}</b>
      <span class="ah-hint">{{ idx + 1 }} / {{ card.questions.length }}</span>
      <span class="ah-ttl" :class="{ urgent: remainMs < 60_000 }" :title="autoContinue === false ? '超时未答会改用文字问你，不会替你乱选' : '超时未答会按设置里的「提问自动继续」处理，不会替你乱选'">{{
        remainText
      }}</span>
    </div>

    <div class="abody">
      <p class="aq-text">{{ card.questions[idx]?.question }}</p>
      <div class="aq-opts" role="group" :aria-label="card.questions[idx]?.question">
        <button
          v-for="(o, oi) in card.questions[idx]?.options ?? []"
          :key="oi"
          type="button"
          class="aopt"
          :class="{ on: isPicked(idx, o.label) }"
          :aria-pressed="isPicked(idx, o.label)"
          :disabled="busy"
          :title="o.description || o.label"
          @click="pick(idx, o.label)"
        >
          <span class="aopt-no">{{ oi + 1 }}.</span>
          <span class="aopt-l">{{ o.label }}</span>
          <span v-if="o.description" class="aopt-d">{{ o.description }}</span>
        </button>
        <!-- 同款解剖：自填项就是「第 N+1 个选项」 -->
        <label class="aopt aopt-other" :class="{ on: !!(other[idx] ?? '').trim() }">
          <span class="aopt-no">{{ (card.questions[idx]?.options?.length ?? 0) + 1 }}.</span>
          <input
            v-model="other[idx]"
            type="text"
            class="aother"
            :disabled="busy"
            maxlength="200"
            placeholder="输入你的回答…（都不合适就写在这）"
            :aria-label="`${card.questions[idx]?.header ?? ''}：其它补充`"
            @keydown.enter.prevent="primary"
          />
        </label>
      </div>
    </div>

    <div class="af">
      <span class="af-hint">点一行选中，也可以在最后一行自己写；不答直接继续＝按最稳妥的默认往下走</span>
      <div class="af-btns">
        <button class="af-ignore" type="button" title="不用回答这一问（助手会按稳妥默认继续）" :disabled="busy" @click="submit('cancel')">
          忽略
        </button>
        <button v-if="idx > 0" class="af-prev" type="button" :disabled="busy" @click="prev">上一题</button>
        <button class="af-go" type="button" :class="{ busy }" :disabled="busy" @click="primary">
          {{ idx < card.questions.length - 1 ? '继续' : '提交' }}
        </button>
      </div>
    </div>
    <p v-if="errText" class="aerr">{{ errText }}</p>
  </div>
</template>

<script setup lang="ts">
/**
 * 内置结构化反问卡（题12 案1；题10 乙 2026-11-06 照 ZCode 重排）：
 * 贴在输入框上方、一次一题、右上角 1/N 翻页；主按钮从不禁用（空着提交＝「未回答，已自动继续」）；
 * 「忽略」= 取消（后端按未作答收尾，模型走稳妥默认）。答完由宿主落一行「已询问」回执（题8）。
 */
import type { AssistantAskUserAnswer, AssistantAskUserCard } from '@/api/dcs/assistant';
import { answerAskUser } from '@/api/dcs/assistant';
import IconSpan from './IconSpan.vue';

interface AskUserDone {
  id: string;
  outcome: 'answered' | 'cancelled' | 'timeout' | 'skipped';
  answers: AssistantAskUserAnswer[];
}

const props = defineProps<{ card: AssistantAskUserCard; threadId: string; autoContinue?: boolean }>();
const emit = defineEmits<{ done: [payload: AskUserDone] }>();

const rootEl = ref<HTMLElement | null>(null);
const busy = ref(false);
const errText = ref('');
/** 当前题（一次一题，ZCode 同形） */
const idx = ref(0);
/** 每题点选的预设项 label（多选=数组可多；单选=替换） */
const picked = reactive<Record<number, string[]>>({});
const other = reactive<Record<number, string>>({});
const nowMs = ref(Date.now());
/** 到期时刻=到达时刻+服务端剩余时长（相对时长免疫两端时钟偏差） */
const deadlineAt = Date.now() + (props.card.remainingMs ?? 240_000);

const remainMs = computed(() => Math.max(0, deadlineAt - nowMs.value));
const remainText = computed(() => {
  const s = Math.ceil(remainMs.value / 1000);
  if (remainMs.value <= 0) {
    return '已超时';
  }
  const left = `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`;
  // 设置页「提问自动继续」关着时，到点不是继续，是改用文字问——倒计时文案不能再说「自动继续」
  return props.autoContinue === false ? `${left} 后改用文字问你` : `${left} 后自动继续`;
});

let timer: ReturnType<typeof setInterval> | null = null;
onMounted(() => {
  rootEl.value?.focus({ preventScroll: true });
  timer = setInterval(() => {
    nowMs.value = Date.now();
    if (remainMs.value <= 0) {
      // 超时收卡：后端同刻按「提问自动继续」收尾（空答案 or 超时），前端只留回执
      emit('done', { id: props.card.id, outcome: 'timeout', answers: [] });
    }
  }, 1000);
});
onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
});

function isPicked(qi: number, label: string) {
  return (picked[qi] ?? []).includes(label);
}

/** 单选点一下即选中并前进（ZCode 单选行为）；多选只切换、等提交 */
function pick(qi: number, label: string) {
  const q = props.card.questions[qi];
  const cur = picked[qi] ?? [];
  if (q.multiSelect) {
    picked[qi] = cur.includes(label) ? cur.filter((x) => x !== label) : [...cur, label];
    return;
  }
  picked[qi] = [label];
  if (qi < props.card.questions.length - 1) {
    setTimeout(() => next(), 120);
  }
}

function next() {
  if (idx.value < props.card.questions.length - 1) {
    idx.value += 1;
  }
}
function prev() {
  if (idx.value > 0) {
    idx.value -= 1;
  }
}

/** 主按钮：不是最后一题＝继续翻页；最后一题＝提交（有答就发答案，空答＝skipped） */
function primary() {
  if (idx.value < props.card.questions.length - 1) {
    next();
    return;
  }
  void submit(hasAnyAnswer() ? 'answer' : 'skip');
}

function hasAnyAnswer(): boolean {
  return props.card.questions.some((_, i) => (picked[i] ?? []).length > 0 || (other[i] ?? '').trim() !== '');
}

async function submit(action: 'answer' | 'cancel' | 'skip') {
  if (busy.value) return;
  busy.value = true;
  errText.value = '';
  const answers: AssistantAskUserAnswer[] = props.card.questions.map((q, i) => {
    const one: AssistantAskUserAnswer = {
      header: q.header,
      question: q.question,
      picked: [...(picked[i] ?? [])]
    };
    const o = (other[i] ?? '').trim();
    if (o) one.other = o;
    return one;
  });
  // 空答/忽略：后端只收 answer|cancel，两条都按「未作答」收尾——
  // 区别只在回执文案：空答=未回答，已自动继续；忽略=未提供回答
  const apiAction: 'answer' | 'cancel' = action === 'answer' ? 'answer' : 'cancel';
  const outcome: AskUserDone['outcome'] = action === 'answer' ? 'answered' : action === 'skip' ? 'skipped' : 'cancelled';
  try {
    await answerAskUser(props.threadId, props.card.id, apiAction, action === 'answer' ? answers : []);
    emit('done', { id: props.card.id, outcome, answers: action === 'answer' ? answers : [] });
  } catch (e) {
    // request 层对业务失败直接 reject（message=后端人话如「这个反问已失效」）——
    // 失效/超时的卡没有再答的必要，直接本地收卡；忽略是本地意图，失败也收
    const msg = (e as { message?: string })?.message || '网络异常，稍后再试（超时前仍可提交）';
    errText.value = msg;
    busy.value = false;
    if (action !== 'answer' || msg.includes('失效') || msg.includes('超时')) {
      emit('done', { id: props.card.id, outcome: action === 'answer' ? 'cancelled' : outcome, answers: [] });
    }
  }
}
</script>

<style lang="scss" scoped>
/* 视觉语言对齐 ElicitationCard（dcs-* 家族）：安静边框、小标签、无渐变无阴影。
   题10 乙：整卡贴在输入框上方（dock），不再随消息流滚走。 */
.dcs-ask {
  border: 1px solid var(--app-border);
  border-radius: 14px;
  background: var(--app-surface);
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  max-height: min(56vh, 520px);
  &:focus-visible {
    outline: 2px solid var(--el-color-primary-light-5);
    outline-offset: -2px;
  }
}
.ah {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  row-gap: 4px;
  flex: none;
  font-size: 13px;
  b {
    font-size: 13px;
  }
}
.abody {
  display: flex;
  flex-direction: column;
  gap: 8px;
  min-height: 0;
  overflow-y: auto;
  padding-right: 4px;
}
.ah-ic {
  display: inline-flex;
  color: var(--el-color-primary);
}
.ah-hint {
  font-size: 11px;
  color: var(--dcs-text-secondary, #909399);
  font-variant-numeric: tabular-nums;
}
.ah-ttl {
  margin-left: auto;
  font-size: 11px;
  color: var(--dcs-text-secondary, #909399);
  font-variant-numeric: tabular-nums;
  &.urgent {
    color: var(--el-color-danger);
  }
}
.aq-text {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.6;
  color: var(--dcs-text-primary, #303133);
  word-break: break-word;
}
.aq-opts {
  display: flex;
  flex-direction: column;
  gap: 1px;
}
/* 同款解剖：编号行；选中不靠蓝底，靠序号反白 + 行底 5% 灰 */
.aopt {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 2px 8px;
  width: 100%;
  min-height: 32px;
  text-align: left;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  padding: 5px 8px;
  cursor: pointer;
  font: inherit;
  &:hover {
    background: color-mix(in srgb, var(--app-text, #181818) 6%, transparent);
  }
  &.on {
    background: color-mix(in srgb, var(--app-text, #181818) 6%, transparent);
  }
  &:disabled {
    opacity: 0.55;
    cursor: not-allowed;
  }
}
.aopt-no {
  flex: none;
  width: 15px;
  font-size: 12px;
  line-height: 20px;
  text-align: right;
  color: var(--app-faint, #8a8a8a);
  font-variant-numeric: tabular-nums;
}
.aopt.on .aopt-no {
  color: var(--app-text, #181818);
  font-weight: 600;
}
.aopt-l {
  flex: 0 1 auto;
  min-width: 0;
  font-size: 13px;
  line-height: 20px;
  color: var(--app-text, #181818);
  font-weight: 600;
  overflow-wrap: anywhere;
}
.aopt-d {
  flex: 1 1 120px;
  min-width: 0;
  font-size: 11.5px;
  line-height: 20px;
  color: var(--app-muted, #666);
  overflow-wrap: anywhere;
}
.aopt-other {
  align-items: flex-start;
  cursor: text;
  border-color: var(--app-border, rgba(13, 13, 13, 0.1));
  background: var(--app-surface, #fff);
  &:hover,
  &.on {
    background: var(--app-surface, #fff);
  }
  .aopt-no {
    line-height: 24px;
  }
  &:focus-within {
    border-color: var(--app-control, rgba(13, 13, 13, 0.12));
    background: color-mix(in srgb, var(--app-text, #181818) 6%, transparent);
  }
}
.aother {
  flex: 1;
  min-width: 0;
  border: 0;
  padding: 2px 0;
  font: inherit;
  font-size: 13px;
  line-height: 20px;
  background: transparent;
  color: var(--app-text, #181818);
  &::placeholder {
    color: var(--app-faint, #8a8a8a);
  }
  &:focus-visible {
    outline: none;
  }
}
.af {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  flex: none;
}
.af-hint {
  flex: 1;
  min-width: 160px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--app-faint, #8a8a8a);
}
.af-btns {
  display: flex;
  gap: 8px;
  justify-content: flex-end;
  flex-wrap: wrap;
  flex: none;
  margin-left: auto;
  button {
    height: 32px;
    border-radius: 8px;
    border: 1px solid var(--app-border, rgba(13, 13, 13, 0.1));
    font-size: 12.5px;
    padding: 0 12px;
    cursor: pointer;
    background: var(--app-surface, #fff);
    color: var(--app-muted, #666);
    &:disabled {
      opacity: 0.55;
      cursor: not-allowed;
    }
  }
  .af-go {
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
.aerr {
  margin: 0;
  color: var(--el-color-danger);
  font-size: 12px;
  text-align: right;
}
</style>
