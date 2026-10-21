<template>
  <div class="app-container health-page">
    <el-card shadow="never" class="hero-card">
      <div class="hero-head">
        <div class="hero-copy">
          <h3>引擎健康</h3>
          <p>事件从采集到可见要过"重建、汇总、重算"几道后台工序——哪道堵了、慢了、悄悄失败了，这里一屏看清；越线自动给管理员发站内告警（每小时 :15 巡检）。</p>
        </div>
        <div class="hero-actions">
          <el-select v-model="source" style="width: 150px" @change="load">
            <el-option v-for="s in sourceOptions" :key="s" :label="s" :value="s" />
          </el-select>
          <el-button :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>

      <div v-if="health" class="alert-bar">
        <template v-if="health.alerts.length">
          <div v-for="(a, i) in health.alerts" :key="i" class="alert-item">
            <span class="alert-dot" />{{ a.message }}
          </div>
        </template>
        <div v-else class="alert-item ok">
          <span class="alert-dot ok" />近 24 小时四项指标全部在阈值内
        </div>
      </div>
    </el-card>

    <div v-loading="loading" class="metric-grid">
      <el-card shadow="never" class="metric-card">
        <span class="metric-label">事件延迟 P95</span>
        <strong :class="{ warn: overLatency }">{{ ms(health?.eventLatency?.p95) }}</strong>
        <span class="metric-sub muted">采集 → 落库（P50 {{ ms(health?.eventLatency?.p50) }} · {{ health?.eventLatency?.times ?? 0 }} 条样本）</span>
      </el-card>
      <el-card shadow="never" class="metric-card">
        <span class="metric-label">重建耗时 P95</span>
        <strong :class="{ warn: overRebuild }">{{ ms(health?.rebuildDuration?.p95) }}</strong>
        <span class="metric-sub muted">会话+日汇总（P50 {{ ms(health?.rebuildDuration?.p50) }} · 近 24h {{ health?.rebuildDuration?.times ?? 0 }} 次）</span>
      </el-card>
      <el-card shadow="never" class="metric-card">
        <span class="metric-label">任务失败率</span>
        <strong :class="{ warn: overFailRate }">{{ health?.tasks?.failRatePct ?? '0' }}%</strong>
        <span class="metric-sub muted">模型重算+全量重算（{{ health?.tasks?.failed ?? 0 }}/{{ health?.tasks?.total ?? 0 }} 失败）</span>
      </el-card>
      <el-card shadow="never" class="metric-card">
        <span class="metric-label">队列积压</span>
        <strong :class="{ warn: overBacklog }">{{ health?.backlog?.events ?? 0 }}</strong>
        <span class="metric-sub muted">待重建事件（上次重建 {{ fmt(health?.backlog?.lastRebuildAt) }}）</span>
      </el-card>
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

const sourceOptions = ['web', 'order', 'supply', 'dcs', 'risk'];
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
.hero-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; flex-wrap: wrap; }
.hero-copy h3 { margin: 0 0 4px; }
.hero-copy p { margin: 0; color: var(--el-text-color-secondary); font-size: 13px; max-width: 640px; }
.hero-actions { display: flex; gap: 8px; align-items: center; }
.alert-bar { margin-top: 14px; display: flex; flex-direction: column; gap: 6px; }
.alert-item { background: var(--el-color-danger-light-9); color: var(--el-color-danger); border-radius: 6px; padding: 6px 12px; font-size: 13px; }
.alert-item.ok { background: var(--el-color-success-light-9); color: var(--el-color-success); }
.alert-dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; background: currentColor; margin-right: 8px; vertical-align: middle; }
.metric-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 14px; margin-bottom: 14px; }
.metric-card { display: flex; flex-direction: column; gap: 4px; }
.metric-card :deep(.el-card__body) { display: flex; flex-direction: column; gap: 4px; }
.metric-label { font-size: 13px; color: var(--el-text-color-secondary); }
.metric-card strong { font-size: 26px; font-weight: 700; color: var(--el-color-primary); }
.metric-card strong.warn { color: var(--el-color-danger); }
.metric-sub { font-size: 12px; }
.panel-head { display: flex; justify-content: space-between; align-items: center; }
.muted { color: var(--el-text-color-secondary); }
.small { font-size: 12px; }
.bars { display: flex; align-items: flex-end; gap: 18px; height: 140px; padding: 8px 4px 0; }
.bar-col { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: flex-end; height: 100%; gap: 4px; }
.bar { width: 60%; max-width: 42px; background: linear-gradient(180deg, var(--el-color-primary-light-3), var(--el-color-primary)); border-radius: 4px 4px 0 0; }
.threshold-row { display: flex; gap: 24px; flex-wrap: wrap; font-size: 13px; color: var(--el-text-color-regular); }
</style>
