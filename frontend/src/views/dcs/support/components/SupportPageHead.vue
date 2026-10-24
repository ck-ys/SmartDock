<template>
  <header class="sp-head">
    <div class="sp-head__text">
      <h1 class="sp-head__title">{{ title }}</h1>
      <p v-if="description" class="sp-head__desc">{{ description }}</p>
    </div>
    <div v-if="$slots.aside" class="sp-head__aside">
      <slot name="aside" />
    </div>
  </header>
</template>

<script setup name="SupportPageHead" lang="ts">
defineProps<{
  title: string;
  description?: string;
}>();
</script>

<style lang="scss" scoped>
.sp-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 24px;
  padding: 2px 2px 18px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--dcs-border-color-lighter);
}

.sp-head__text {
  min-width: 0;
}

.sp-head__title {
  margin: 0;
  font-size: 28px;
  line-height: 1.2;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--dcs-text-primary);
}

.sp-head__desc {
  margin: 7px 0 0;
  max-width: 760px;
  font-size: 13.5px;
  line-height: 1.65;
  color: var(--dcs-text-secondary);
}

.sp-head__aside {
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

@media (max-width: 768px) {
  .sp-head {
    flex-direction: column;
    align-items: stretch;
    gap: 12px;
    padding-bottom: 14px;
  }

  .sp-head__title {
    font-size: 23px;
  }

  .sp-head__aside {
    justify-content: flex-start;
  }
}
</style>
