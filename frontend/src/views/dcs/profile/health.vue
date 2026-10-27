<template>
  <div class="app-container health-page">
    <div class="ui-page-head">
      <div class="ui-page-head__main">
        <div class="ui-page-head__eyebrow">画像引擎</div>
        <h1 class="ui-page-head__title">引擎健康</h1>
        <p class="ui-page-head__sub">
          事件从采集到可见要过"重建、汇总、重算"几道后台工序——哪道堵了、慢了、悄悄失败了，这里一屏看清；越线自动给管理员发站内告警（每小时
          :15 巡检）。
        </p>
      </div>
      <div class="ui-page-head__actions">
        <el-select v-model="source" class="source-select" @change="load">
          <el-option v-for="s in sourceOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-button :loading="loading" @click="load">刷新</el-button>
      </div>
    </div>

    <div v-if="health" class="alert-list">
      <template v-if="health.alerts.length">
        <div v-for="(a, i) in health.alerts" :key="i" class="alert-item">
          <span class="ui-pill ui-pill--neg">告警</span>
          <span>{{ a.message }}</span>
        </div>
      </template>
      <div v-else class="alert-item">
        <span class="ui-pill ui-pill--pos">正常</span>
        <span>近 24 小时四项指标全部在阈值内</span>
      </div>
    </div>

    <div v-loading="loading" class="ui-kpi-strip">
      <div class="ui-kpi">
        <span class="ui-kpi__label">事件延迟 P95</span>
        <strong class="ui-kpi__value">{{ ms(health?.eventLatency?.p95) }}</strong>
        <span class="ui-kpi__hint">
          <span v-if="overLatency" class="ui-pill ui-pill--warn">超阈值</span>
          采集 → 落库（P50 {{ ms(health?.eventLatency?.p50) }} · {{ health?.eventLatency?.times ?? 0 }} 条样本）
        </span>
      </div>
      <div class="ui-kpi">
        <span class="ui-kpi__label">重建耗时 P95</span>
        <strong class="ui-kpi__value">{{ ms(health?.rebuildDuration?.p95) }}</strong>
        <span class="ui-kpi__hint">
          <span v-if="overRebuild" class="ui-pill ui-pill--warn">超阈值</span>
          会话+日汇总（P50 {{ ms(health?.rebuildDuration?.p50) }} · 近 24h {{ health?.rebuildDuration?.times ?? 0 }} 次）
        </span>
      </div>
      <div class="ui-kpi">
        <span class="ui-kpi__label">任务失败率</span>
        <strong class="ui-kpi__value">{{ health?.tasks?.failRatePct ?? '0' }}%</strong>
        <span class="ui-kpi__hint">
          <span v-if="overFailRate" class="ui-pill ui-pill--warn">超阈值</span>
          模型重算+全量重算（{{ health?.tasks?.failed ?? 0 }}/{{ health?.tasks?.total ?? 0 }} 失败）
        </span>
      </div>
      <div class="ui-kpi">
        <span class="ui-kpi__label">队列积压</span>
        <strong class="ui-kpi__value">{{ health?.backlog?.events ?? 0 }}</strong>
        <span class="ui-kpi__hint">
          <span v-if="overBacklog" class="ui-pill ui-pill--warn">超阈值</span>
          待重建事件（上次重建 {{ fmt(health?.backlog?.lastRebuildAt) }}）
        </span>
      </div>
    </div>

    <el-card shadow="never">
      <template #header>
        <div class="panel-head">
          <span>近 6 次重建耗时</span>
          <span class="muted small">数据来自重建留痕（每次会话重建一行，保留 30 天）</span>
        </div>
      </template>
      <div v-if="bars.length" class="bars">
        <div v-for="(b, i) in bars" :key="i" class="bar-col">
          <div class="bar" :style="{ height: b.height + '%' }" :title="`${b.durationMs}ms · ${b.sessions} 会话 · ${b.kind} · ${fmt(b.finishedAt)}`" />
          <span class="bar-label muted small">{{ b.label }}</span>
        </div>
      </div>
      <el-empty v-else description="该源还没有重建留痕——写入事件或手动重建后出现" :image-size="60" />
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="panel-head"><span>告警阈值</span><span class="muted small">超过即触发站内告警（每源每天每指标提醒一次）</span></div>
      </template>
      <div class="threshold-row">
        <span>事件延迟 P95 &gt; {{ ms(health?.thresholds?.latencyP95Ms) }}</span>
        <span>重建耗时 P95 &gt; {{ ms(health?.thresholds?.rebuildP95Ms) }}</span>
        <span>任务失败率 &gt; {{ health?.thresholds?.failRatePct }}%</span>
        <span>队列积压 &gt; {{ health?.thresholds?.backlog }}</span>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts" name="ProfileHealth">
import { getEngineHealth, type ProfileHealth } from '@/api/dcs/core/profile';
import { useSourceOptions } from '@/composables/useProfileSources';

// 五审回修: 源下拉接口驱动(批2 承诺八页, 本页漏改)——新源有事件即可选, 拉取失败回退内置清单
const { list: sourceOptions, ensureSources } = useSourceOptions('event', [
  { label: 'web', value: 'web' },
  { label: 'order', value: 'order' },
  { label: 'supply', value: 'supply' },
  { label: 'dcs', value: 'dcs' },
  { label: 'risk', value: 'risk' }
]);
ensureSources();
const source = ref('web');
const loading = ref(false);
const health = ref<ProfileHealth | null>(null);

const ms = (v?: number | null) => (v == null ? '—' : v >= 1000 ? (v / 1000).toFixed(1) + 's' : Math.round(v) + 'ms');
const fmt = (v?: string) => (v ? String(v).replace('T', ' ').slice(5, 16) : '从未');

const overLatency = computed(() => (health.value?.eventLatency?.p95 ?? 0) > (health.value?.thresholds?.latencyP95Ms ?? Infinity));
const overRebuild = computed(() => (health.value?.rebuildDuration?.p95 ?? 0) > (health.value?.thresholds?.rebuildP95Ms ?? Infinity));
const overFailRate = computed(() => (health.value?.tasks?.failRatePct ?? 0) > (health.value?.thresholds?.failRatePct ?? Infinity));
const overBacklog = computed(() => (health.value?.backlog?.events ?? 0) > (health.value?.thresholds?.backlog ?? Infinity));

const bars = computed(() => {
  const recent = [...(health.value?.rebuildRecent ?? [])].reverse();
  const max = Math.max(1, ...recent.map((r) => r.durationMs ?? 0));
  return recent.map((r) => ({
    height: Math.max(6, Math.round(((r.durationMs ?? 0) / max) * 100)),
    label: r.finishedAt ? String(r.finishedAt).slice(11, 16) : '',
    durationMs: r.durationMs ?? 0,
    sessions: r.sessions ?? 0,
    kind: r.kind ?? '',
    finishedAt: r.finishedAt,
  }));
});

const load = async () => {
  loading.value = true;
  try {
    health.value = await getEngineHealth(source.value);
  } finally {
    loading.value = false;
  }
};

onMounted(load);
</script>

<style scoped>
.health-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.health-page > .ui-page-head {
  margin-bottom: 4px;
}
.health-page .ui-page-head__title {
  font-size: var(--dcs-font-page);
}
.health-page .ui-page-head__sub {
  font-size: var(--dcs-font-md);
}
.health-page .ui-page-head__actions {
  flex-wrap: wrap;
}
@media (min-width: 993px) {
  .source-select {
    width: 150px;
  }
}
.health-page :deep(.el-card__body) {
  min-width: 0;
}
.alert-list {
  background: var(--app-surface);
  border: 1px solid var(--app-border);
  border-radius: var(--app-card-radius);
  overflow: hidden;
}
.alert-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  color: var(--app-muted);
  font-size: 13px;
  line-height: 1.5;
}
.alert-item + .alert-item {
  border-top: 1px solid var(--app-rule);
}
.ui-kpi-strip {
  margin-top: 0;
}
.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.muted {
  color: var(--el-text-color-secondary);
}
.small {
  font-size: 12px;
}
.bars {
  display: flex;
  align-items: flex-end;
  gap: 18px;
  height: 140px;
  padding: 8px 4px 0;
}
.bar-col {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: flex-end;
  height: 100%;
  gap: 4px;
}
.bar {
  width: 60%;
  max-width: 42px;
  background: var(--app-accent);
  border-radius: var(--app-control-radius) var(--app-control-radius) 0 0;
}
.threshold-row {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
</style>
