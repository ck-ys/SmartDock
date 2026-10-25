<template>
  <header class="sp-head">
    <div class="sp-head__main">
      <div v-if="eyebrow" class="sp-head__eyebrow">{{ eyebrow }}</div>
      <h1 class="sp-head__title">{{ title }}</h1>
      <p v-if="description" class="sp-head__desc">{{ description }}</p>
    </div>
    <div v-if="$slots.aside" class="sp-head__aside">
      <slot name="aside" />
    </div>
  </header>
</template>

<script setup name="SupportPageHead" lang="ts">
withDefaults(
  defineProps<{
    title: string;
    description?: string;
    eyebrow?: string;
  }>(),
  {
    eyebrow: ''
  }
);
</script>

<style lang="scss" scoped>
/* 与 DcsOperationsPageHeader 同一组数值：eyebrow 12/400 faint → 标题 20/600 → 副题 13/muted */
.sp-head {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px 16px;
  padding: 2px 2px 0;
  margin-bottom: 16px;
}

.sp-head__main {
  flex: 1 1 320px;
  min-width: 0;
}

.sp-head__eyebrow {
  margin-bottom: 5px;
  color: var(--app-faint);
  font-size: var(--dcs-font-sm);
  font-weight: 400;
  line-height: 1.3;
}

.sp-head__title {
  margin: 0;
  color: var(--app-text);
  font-size: var(--dcs-font-page);
  font-weight: 600;
  line-height: 1.35;
}

.sp-head__desc {
  margin: 6px 0 0;
  color: var(--app-muted);
  font-size: var(--dcs-font-md);
  line-height: 1.6;
}

.sp-head__aside {
  display: flex;
  flex: none;
  align-items: center;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
  --el-component-size: var(--dcs-control-h);
}

@media (max-width: 992px) {
  .sp-head {
    flex-direction: column;
  }

  .sp-head__main {
    flex: 0 0 auto;
  }

  .sp-head__aside {
    width: 100%;
    justify-content: flex-start;
  }
}
</style>
