<template>
  <div class="dcs-plan-tip" :class="{ full, busy }">
    <button class="pt-inner" type="button" :disabled="busy" @click="emit('open')">
      <span class="pt-dot" aria-hidden="true"></span>
      <span class="pt-text"
        >计划待确认<span v-if="tail"> · 尾号 {{ tail }}</span></span
      >
      <span class="pt-go">
        {{ busy ? '助手收尾中…' : '去选择' }}
        <IconSpan v-if="!busy" name="arrow-right" :size="12" />
      </span>
    </button>
  </div>
</template>

<script setup lang="ts">
/**
 * 计划待确认细条（2026-11-02 拍板 Q6-A）：
 * 用户正在输入框里打字时，计划突然到达——不抢输入框，只在上面出一条细条；
 * 点一下才展开消息流里的计划表单卡，草稿原样保留。
 */
import IconSpan from './IconSpan.vue';
import { planTail, type AssistantPlan } from './planDock';

const props = defineProps<{ plan: AssistantPlan; full?: boolean; busy?: boolean }>();
const emit = defineEmits<{ open: [] }>();

const tail = computed(() => planTail(props.plan.planId));
</script>

<style lang="scss" scoped>
.dcs-plan-tip {
  flex: none;
  width: 100%;
  padding: 0 14px 6px;

  &.full {
    padding: 0 24px 6px;

    .pt-inner {
      max-width: var(--dcs-col-max, 900px);
      margin: 0 auto;
    }
  }

  @media (max-width: 992px) {
    padding: 0 10px 4px;
  }
}
.pt-inner {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 6px 10px;
  border: 1px solid var(--dcs-border-color-light);
  border-radius: 8px;
  background: var(--el-fill-color-light);
  color: var(--dcs-text-regular);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
  transition:
    background 0.15s,
    border-color 0.15s;

  &:hover {
    border-color: var(--el-color-primary-light-5);
    background: var(--el-color-primary-light-9);
  }

  &:disabled {
    cursor: default;
    opacity: 0.7;
  }
  &:disabled:hover {
    border-color: var(--dcs-border-color-light);
    background: var(--el-fill-color-light);
  }
}
.pt-dot {
  flex: none;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--el-color-primary);
}
.pt-text {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pt-go {
  display: inline-flex;
  flex: none;
  align-items: center;
  gap: 2px;
  margin-left: auto;
  color: var(--el-color-primary);
}
</style>
