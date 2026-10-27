<template>
  <section class="dcs-ops-header" :aria-labelledby="headingId">
    <div class="dcs-ops-header__intro">
      <!-- 2026-10-27 重构拍板 Q6=乙：眉标（“DCS 运营工作台 / 钱包与计费”这一行）下线。
           它只重复标题已经说了的模块名，却固定占掉约 20px；prop 与默认值保留，避免调用方类型报错 -->
      <div class="dcs-ops-header__title-row">
        <div>
          <h1 :id="headingId" class="dcs-ops-header__title">{{ title }}</h1>
          <p class="dcs-ops-header__subtitle">{{ subtitle }}</p>
        </div>
        <div v-if="$slots.actions" class="dcs-ops-header__actions">
          <slot name="actions" />
        </div>
      </div>
    </div>

    <div v-if="metrics.length" class="dcs-ops-metrics" aria-label="页面概览">
      <article v-for="metric in metrics" :key="metric.label" class="dcs-ops-metric" :class="`dcs-ops-metric--${metric.tone || 'primary'}`">
        <span class="dcs-ops-metric__label">{{ metric.label }}</span>
        <strong class="dcs-ops-metric__value">{{ metric.value }}</strong>
        <span v-if="metric.hint" class="dcs-ops-metric__hint">{{ metric.hint }}</span>
      </article>
    </div>
  </section>
</template>

<script setup lang="ts">
export interface DcsOperationsMetric {
  label: string;
  value: string | number;
  hint?: string;
  tone?: 'primary' | 'success' | 'warning' | 'danger' | 'neutral';
}

withDefaults(
  defineProps<{
    title: string;
    subtitle: string;
    eyebrow?: string;
    metrics?: DcsOperationsMetric[];
  }>(),
  {
    eyebrow: 'DCS 运营工作台',
    metrics: () => []
  }
);

const headingId = `dcs-ops-heading-${Math.random().toString(36).slice(2, 9)}`;
</script>
