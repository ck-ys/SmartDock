<template>
  <div class="pcard">
    <div class="pt">{{ plan.title ?? `这个任务我打算分 ${plan.steps.length} 步做` }}</div>
    <div class="pst">
      <template v-for="(s, i) in plan.steps" :key="i">
        <span v-if="i > 0" class="parw"><IconSpan name="arrow-right" :size="12" /></span>
        <!-- eslint-disable-next-line vue/no-v-html —— 源串保持 ✏️（原型口径），渲染层按 EMO2IC 换 Lucide -->
        <span class="pstep" :class="s.state" v-html="emoToSvgHtml(stepText(s, i))"></span>
      </template>
    </div>
    <div class="pact">
      <button class="pa-ghost" title="把计划填回输入框，改完再发" @click="emit('edit-plan', planText)"><IconSpan name="pencil" /> 改计划</button>
      <button class="pa-go" title="让助手按这个计划开始执行" @click="emit('confirm-plan')"><IconSpan name="play-circle" /> 直接执行</button>
      <button class="pa-ghost" title="收起计划卡（不影响对话）" @click="emit('cancel-plan')">取消</button>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 计划卡（批C-2，原型 pcard2 翻译；P1-3 拍板补做）：
 * CUSTOM plan 事件带步骤数组 → useAssistant turn.plan → 本卡渲染。
 * 后端接线（哪个工具发 plan 事件）随后端批次——无触发源期间组件就绪不出现。
 * 三键均为真实动作：改计划=计划文本回填输入框；直接执行=按计划下发指令；取消=收卡。
 */
import IconSpan from './IconSpan.vue';
import { emoToSvgHtml } from './icons';

const props = defineProps<{ plan: { title?: string; steps: Array<{ label: string; state: 'pending' | 'run' | 'done' | 'edit' }> } }>();
const emit = defineEmits<{ 'edit-plan': [text: string]; 'confirm-plan': []; 'cancel-plan': [] }>();

const QN = '①②③④⑤⑥⑦⑧';
// 编辑态前缀 ✏️=U+270F（EMO2IC→pencil；此前 ✎ U+270E 无映射，Q2 口径统一原型）
const stepText = (s: { label: string; state: string }, i: number) => (s.state === 'edit' ? `✏️ ${s.label}` : `${QN[i] ?? i + 1} ${s.label}`);

const planText = computed(() => `按这个计划执行：\n${props.plan.steps.map((s, i) => `${i + 1}. ${s.label}`).join('\n')}\n开始吧。`);
</script>

<style lang="scss" scoped>
.pcard {
  border: 1px solid var(--dcs-border-color);
  border-radius: 10px;
  background: var(--el-bg-color);
  padding: 12px;
}
.pt {
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 10px;
  color: var(--dcs-text-primary);
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
