<template>
  <section class="dcs-ops-header" :aria-labelledby="headingId">
    <div class="dcs-ops-header__intro">
      <div class="dcs-ops-header__eyebrow">{{ eyebrow }}</div>
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
