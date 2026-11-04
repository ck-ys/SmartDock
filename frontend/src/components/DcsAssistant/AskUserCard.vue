<template>
  <div ref="rootEl" class="dcs-ask" tabindex="-1" role="dialog" aria-label="助手需要你选择">
    <div class="ah">
      <span class="ah-ic"><IconSpan name="help" /></span>
      <b>需要你选一下</b>
      <span class="ah-hint">共 {{ card.questions.length }} 题 · 全部必答</span>
      <span class="ah-ttl" :class="{ urgent: remainMs < 60_000 }" title="超时未选会自动关闭，不会替你选；助手会按稳妥默认继续或停下来问你">{{
        remainText
      }}</span>
    </div>

    <div class="abody">
      <div v-for="(q, qi) in card.questions" :key="qi" class="aq">
        <div class="aq-head">
          <span class="aq-tag">{{ q.header }}</span>
          <span v-if="q.multiSelect" class="aq-multi">可多选</span>
          <span class="aq-idx">第 {{ qi + 1 }}/{{ card.questions.length }} 题</span>
        </div>
        <p class="aq-text">{{ q.question }}</p>
        <div class="aq-opts" role="group" :aria-label="q.question">
          <button
            v-for="(o, oi) in q.options"
            :key="oi"
            type="button"
            class="aopt"
            :class="{ on: isPicked(qi, o.label) }"
            :aria-pressed="isPicked(qi, o.label)"
            :disabled="busy"
            :title="o.description || o.label"
            @click="togglePick(qi, o.label)"
          >
            <span class="aopt-no">{{ oi + 1 }}.</span>
            <span class="aopt-l">{{ o.label }}</span>
            <span v-if="o.description" class="aopt-d">{{ o.description }}</span>
          </button>
          <!-- 同款解剖：自填项就是「第 N+1 个选项」 -->
          <label class="aopt aopt-other" :class="{ on: !!(other[qi] ?? '').trim() }">
            <span class="aopt-no">{{ q.options.length + 1 }}.</span>
            <input
              v-model="other[qi]"
              type="text"
              class="aother"
              :disabled="busy"
              maxlength="200"
              placeholder="输入你的回答…（都不合适就写在这）"
              :aria-label="`${q.header}：其它补充`"
            />
          </label>
        </div>
      </div>
    </div>

    <div class="af">
      <span class="af-hint">点一行选中（可多选的题可点多个），也可以在最后一行自己写</span>
      <div class="af-btns">
        <button class="af-cancel" type="button" title="关掉这张卡（助手拿不到答案，会按稳妥默认继续）" @click="submit('cancel')">
          关闭
        </button>
        <button
          class="af-go"
          type="button"
          :class="{ busy }"
          :disabled="busy || !!missing"
          :title="missing || '提交选择，助手继续执行'"
          @click="submit('answer')"
        >
          提交
        </button>
      </div>
    </div>
    <p v-if="errText" class="aerr">{{ errText }}</p>
  </div>
</template>

<script setup lang="ts">
/**
 * 内置结构化反问卡（题12 案1，2026-10-31 拍板）：模型调 ask_user 时卡片化提问
 * （1-4 题、每题 2-4 个选项、可多选、可补「其它」）——用户在卡上点选，结果作为
 * 工具结果回到模型继续跑。与 MCP elicitation 卡同形不同源：这边答内置工具，
 * 每卡自带倒计时（超时后端按未作答收尾，模型被明确告知）。
 */
import type { AssistantAskUserAnswer, AssistantAskUserCard } from '@/api/dcs/assistant';
import { answerAskUser } from '@/api/dcs/assistant';
import IconSpan from './IconSpan.vue';

const props = defineProps<{ card: AssistantAskUserCard; threadId: string }>();
const emit = defineEmits<{ done: [id: string] }>();

const rootEl = ref<HTMLElement | null>(null);
const busy = ref(false);
const errText = ref('');
/** 每题点选的预设项 label（多选=数组可多；单选=替换） */
const picked = reactive<Record<number, string[]>>({});
const other = reactive<Record<number, string>>({});
const nowMs = ref(Date.now());
/** 到期时刻=到达时刻+服务端剩余时长（相对时长免疫两端时钟偏差，elicitation 同款） */
const deadlineAt = Date.now() + (props.card.remainingMs ?? 240_000);

const remainMs = computed(() => Math.max(0, deadlineAt - nowMs.value));
const remainText = computed(() => {
  const s = Math.ceil(remainMs.value / 1000);
  return remainMs.value <= 0 ? '已超时' : `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')} 后自动关闭`;
});
/** 未作答的题头（全部答了=空串；提交按钮据此禁用） */
const missing = computed(() => {
  const miss: string[] = [];
  props.card.questions.forEach((q, i) => {
    const hasPick = (picked[i] ?? []).length > 0;
    const hasOther = (other[i] ?? '').trim() !== '';
    if (!hasPick && !hasOther) miss.push(q.header);
  });
  return miss.length > 0 ? `还没选：${miss.join('、')}` : '';
});

let timer: ReturnType<typeof setInterval> | null = null;
onMounted(() => {
  rootEl.value?.focus({ preventScroll: true });
  // 卡出现在消息流末尾时常有一截落在折叠线以下（视觉席 P0：看不到提交按钮）——
  // 仅当卡片已进入视口（用户本就在看最新内容）时把整卡带进视野；翻历史时不打扰
  nextTick(() => {
    const el = rootEl.value;
    if (!el) return;
    const r = el.getBoundingClientRect();
    if (r.top >= 0 && r.top < window.innerHeight) {
      el.scrollIntoView({ block: 'end' });
    }
  });
  timer = setInterval(() => {
    nowMs.value = Date.now();
    if (remainMs.value <= 0) {
      // 超时收卡：后端同刻按 timeout 完成 future（模型收到「超时未作答」）
      emit('done', props.card.id);
    }
  }, 1000);
});
onBeforeUnmount(() => {
  if (timer) clearInterval(timer);
});

function isPicked(qi: number, label: string) {
  return (picked[qi] ?? []).includes(label);
}

function togglePick(qi: number, label: string) {
  const q = props.card.questions[qi];
  const cur = picked[qi] ?? [];
  if (q.multiSelect) {
    picked[qi] = cur.includes(label) ? cur.filter((x) => x !== label) : [...cur, label];
  } else {
    picked[qi] = cur.includes(label) && cur.length === 1 ? [] : [label];
  }
}

async function submit(action: 'answer' | 'cancel') {
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
  try {
    await answerAskUser(props.threadId, props.card.id, action, answers);
    emit('done', props.card.id);
  } catch (e) {
    // request 层对业务失败直接 reject（message=后端人话如「这个反问已失效」）——
    // 失效/超时的卡没有再答的必要，直接本地收卡；关闭是本地意图，失败也收
    const msg = (e as { message?: string })?.message || '网络异常，稍后再试（超时前仍可提交）';
    errText.value = msg;
    busy.value = false;
    if (action === 'cancel' || msg.includes('失效') || msg.includes('超时')) {
      emit('done', props.card.id);
    }
  }
}
</script>

<style lang="scss" scoped>
/* 视觉语言对齐 ElicitationCard（dcs-* 家族）：安静边框、小标签、无渐变无阴影 */
.dcs-ask {
  border: 1px solid var(--app-border);
  border-radius: 10px;
  background: var(--app-surface);
  padding: 10px 14px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  /* 长卡（最多 4 题）限高内滚：题头与倒计时/底部按钮始终可见，不被消息流裁掉 */
  max-height: min(62vh, 620px);
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
  gap: 12px;
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
.aq {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.aq-head {
  display: flex;
  align-items: center;
  gap: 6px;
}
.aq-tag {
  font-size: 11px;
  color: var(--dcs-text-secondary, #909399);
  background: var(--el-fill-color-light, #f5f7fa);
  border-radius: 8px;
  padding: 1px 8px;
}
.aq-multi {
  font-size: 11px;
  color: var(--dcs-text-secondary, #909399);
}
.aq-idx {
  margin-left: auto;
  font-size: 11px;
  color: var(--dcs-text-secondary, #909399);
  font-variant-numeric: tabular-nums;
}
.aq-text {
  margin: 0;
  font-size: 13px;
  line-height: 1.55;
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
