<template>
  <div class="pcard">
    <div class="phead">
      <div class="pt">{{ plan.title ?? `这个任务我打算分 ${plan.steps.length} 步做` }}</div>
      <span v-if="badge" class="pbadge" :class="plan.status">{{ badge }}</span>
    </div>
    <div class="pst">
      <template v-for="(s, i) in plan.steps" :key="i">
        <span v-if="i > 0" class="parw"><IconSpan name="arrow-right" :size="12" /></span>
        <!-- eslint-disable-next-line vue/no-v-html —— 源串保持 ✏️（原型口径），渲染层按 EMO2IC 换 Lucide -->
        <span class="pstep" :class="s.state" v-html="emoToSvgHtml(stepText(s, i))"></span>
      </template>
    </div>
    <div class="pact">
      <button class="pa-ghost" :disabled="locked" title="把计划填回输入框，改完再发" @click="emit('edit-plan', planText, plan.planId)"><IconSpan name="pencil" /> 改计划</button>
      <button class="pa-go" :disabled="locked" title="让助手按这个计划开始执行" @click="emit('confirm-plan', plan.planId)"><IconSpan name="play-circle" /> 直接执行</button>
      <button class="pa-ghost" :disabled="locked" title="收起计划卡（不影响对话）" @click="emit('cancel-plan', plan.planId)">取消</button>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 计划卡（批C-2，原型 pcard2 翻译；P1-3 拍板补做）：
 * CUSTOM plan 事件带步骤数组 → useAssistant turn.plan → 本卡渲染。
 * 后端接线（哪个工具发 plan 事件）随后端批次——无触发源期间组件就绪不出现。
 * 三键均为真实动作：改计划=计划文本回填输入框；直接执行=按计划下发指令；取消=收卡。
 *
 * 多实例批 4：卡片带 planId，三键回传该 id；status 非 pending 时按钮置灰并显示
 * 文字徽章（另一标签页已确认/取消时，本页旧卡不再可点）。
 */
import IconSpan from './IconSpan.vue';
import { emoToSvgHtml } from './icons';

const props = defineProps<{
  plan: {
    planId?: string;
    title?: string;
    steps: Array<{ label: string; state: 'pending' | 'run' | 'done' | 'edit' }>;
    status?: 'pending' | 'confirmed' | 'cancelled' | 'adjusted' | 'expired';
  };
}>();
const emit = defineEmits<{
  'edit-plan': [text: string, planId?: string];
  'confirm-plan': [planId?: string];
  'cancel-plan': [planId?: string];
}>();

const QN = '①②③④⑤⑥⑦⑧';
// 编辑态前缀 ✏️=U+270F（EMO2IC→pencil；此前 ✎ U+270E 无映射，Q2 口径统一原型）
const stepText = (s: { label: string; state: string }, i: number) => (s.state === 'edit' ? `✏️ ${s.label}` : `${QN[i] ?? i + 1} ${s.label}`);

const locked = computed(() => !!props.plan.status && props.plan.status !== 'pending');
const badge = computed(() => {
  const s = props.plan.status;
  if (!s || s === 'pending') return '';
  return ({ confirmed: '已确认', cancelled: '已取消', adjusted: '已按修改后计划执行', expired: '已过期' } as Record<string, string>)[s] ?? '';
});

const planText = computed(() => `按这个计划执行：\n${props.plan.steps.map((s, i) => `${i + 1}. ${s.label}`).join('\n')}\n开始吧。`);
</script>

<style lang="scss" scoped>
.pcard {
  border: 1px solid var(--dcs-border-color);
  border-radius: 10px;
  background: var(--el-bg-color);
  padding: 12px;
}
.phead {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}
.pt {
  font-weight: 600;
  font-size: 13px;
  color: var(--dcs-text-primary);
}
.pbadge {
  flex: none;
  border-radius: 999px;
  padding: 1px 8px;
  font-size: 11px;
  border: 1px solid var(--dcs-border-color-light);
  color: var(--dcs-text-secondary);

  &.confirmed {
    color: var(--dcs-color-success);
    border-color: var(--dcs-color-success-light-5, #b3e19d);
  }
  &.cancelled,
  &.expired {
    color: var(--dcs-text-placeholder);
  }
  &.adjusted {
    color: var(--el-color-primary);
    border-color: var(--el-color-primary-light-5);
  }
}
.pst {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.pstep {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  border: 1.5px solid var(--dcs-border-color);
  border-radius: 15px;
  padding: 4px 11px;
  font-size: 12.5px;
  color: var(--dcs-text-regular);
  background: var(--el-bg-color);
  transition: 0.2s;

  &.edit {
    border-style: dashed;
    border-color: var(--dcs-color-warning);
  }
  &.run {
    border-color: var(--el-color-primary);
    color: var(--el-color-primary);
    box-shadow: 0 0 0 3px rgba(64, 158, 255, 0.18);
    /* 原型 L737-738：运行步骤呼吸脉冲 */
    animation: dcs-ppp 1s ease infinite;
  }
  &.done {
    border-color: var(--dcs-color-success);
    color: var(--dcs-color-success);
    background: var(--dcs-color-success-light-9, #f0f9eb);
  }
}
.parw {
  color: var(--dcs-text-placeholder);
}
.pact {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 12px;

  button {
    border-radius: 8px;
    padding: 5px 12px;
    font-size: 12.5px;
    cursor: pointer;
    border: 1px solid var(--dcs-border-color-light);
    background: var(--el-bg-color);
    color: var(--dcs-text-regular);

    &:hover {
      border-color: var(--el-color-primary-light-5);
      color: var(--el-color-primary);
    }
    &:disabled {
      cursor: not-allowed;
      opacity: 0.55;
    }
  }
  .pa-go {
    background: var(--el-color-primary);
    border-color: var(--el-color-primary);
    color: var(--app-primary-ink);

    &:hover {
      background: var(--el-color-primary-light-3);
      color: var(--app-primary-ink);
    }
  }
}
@keyframes dcs-ppp {
  50% {
    box-shadow: 0 0 0 6px rgba(64, 158, 255, 0.08);
  }
}
</style>
