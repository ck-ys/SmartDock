<template>
  <div class="pcard">
    <div class="phead">
      <div class="pt">{{ plan.title ?? `这个任务我打算分 ${plan.steps.length} 步做` }}</div>
      <span v-if="badge" class="pbadge" :class="statusKey">{{ badge }}</span>
    </div>
    <div class="pst">
      <template v-for="(s, i) in plan.steps" :key="i">
        <span v-if="i > 0" class="parw"><IconSpan name="arrow-right" :size="12" /></span>
        <!-- eslint-disable-next-line vue/no-v-html —— 源串保持 ✏️（原型口径），渲染层按 EMO2IC 换 Lucide -->
        <span class="pstep" :class="s.state" v-html="emoToSvgHtml(stepText(s, i))"></span>
      </template>
    </div>
    <div class="pfoot" :class="statusKey">
      <IconSpan v-if="statusKey === 'pending'" name="arrow-down" :size="12" />
      <span>{{ footerText }}</span>
    </div>
  </div>
</template>

<script setup lang="ts">
/**
 * 计划卡（只读收据，2026-11-02 拍板 Q4-A）：
 * 决策入口已经搬到输入框位置的 PlanDock，这里不再放按钮，只保留标题、步骤和状态说明。
 * 历史卡仍能看到「已确认 / 已取消 / 已调整 / 已失效」，但不再提供第二个操作入口。
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

const QN = '①②③④⑤⑥⑦⑧';
// 编辑态前缀 ✏️=U+270F（EMO2IC→pencil；此前 ✎ U+270E 无映射，Q2 口径统一原型）
const stepText = (s: { label: string; state: string }, i: number) => (s.state === 'edit' ? `✏️ ${s.label}` : `${QN[i] ?? i + 1} ${s.label}`);

const statusKey = computed(() => props.plan.status ?? 'pending');
const badge = computed(() => {
  return (
    {
      pending: '待确认',
      confirmed: '已确认',
      cancelled: '已取消',
      adjusted: '已修改',
      expired: '已失效'
    } as Record<string, string>
  )[statusKey.value] ?? '';
});
const footerText = computed(() => {
  return (
    {
      pending: '在下方选择：开始执行 / 改一下 / 取消这张卡',
      confirmed: '已按这个计划开始执行',
      cancelled: '这张卡已取消，其他计划不受影响',
      adjusted: '修改意见已发出去，助手会按新意见重做计划',
      expired: '后面又出了新计划，这张卡已失效'
    } as Record<string, string>
  )[statusKey.value] ?? '';
});
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

  &.pending {
    color: var(--el-color-primary);
    border-color: var(--el-color-primary-light-5);
  }
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
.pfoot {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 10px;
  padding-top: 8px;
  border-top: 1px dashed var(--dcs-border-color-light);
  font-size: 11.5px;
  line-height: 1.4;
  color: var(--dcs-text-secondary);

  &.confirmed {
    color: var(--dcs-color-success);
  }
  &.cancelled,
  &.expired {
    color: var(--dcs-text-placeholder);
  }
  &.adjusted {
    color: var(--el-color-primary);
  }
}
@keyframes dcs-ppp {
  50% {
    box-shadow: 0 0 0 6px rgba(64, 158, 255, 0.08);
  }
}
</style>
