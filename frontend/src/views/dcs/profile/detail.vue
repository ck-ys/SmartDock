<template>
  <div class="app-container detail-page">
    <el-card v-loading="loading" shadow="never" class="head-card">
      <div class="head-row">
        <div class="head-main">
          <el-button text @click="back">← 返回列表</el-button>
          <div class="head-title">
            <h3>{{ subjectName }}</h3>
            <div class="head-meta">
              <el-tag size="small" effect="plain">{{ source }}</el-tag>
              <el-tag size="small" effect="plain" type="info">{{ subjectType }}</el-tag>
              <span class="mono">{{ subjectId }}</span>
            </div>
          </div>
        </div>
        <el-button type="primary" @click="load">刷新</el-button>
      </div>

      <template v-if="detail">
        <div class="summary-grid">
          <div class="summary-card primary">
            <span class="summary-label">综合分</span>
            <strong class="summary-value">{{ formatScore(detail.latest?.totalScore) }}</strong>
            <el-tag v-if="detail.risk" :type="riskTagType(detail.risk.color)" effect="light" size="small">
              {{ detail.risk.label }}
            </el-tag>
          </div>
          <div class="summary-card">
            <span class="summary-label">排名</span>
            <strong class="summary-value">{{ detail.rank ? `#${detail.rank}` : '—' }}</strong>
            <span class="summary-hint">同主体类型</span>
          </div>
          <div class="summary-card">
            <span class="summary-label">事件数</span>
            <strong class="summary-value">{{ detail.subject?.eventCount ?? '—' }}</strong>
            <span class="summary-hint">最近 {{ formatTime(detail.subject?.lastEventTime) }}</span>
          </div>
          <div class="summary-card">
            <span class="summary-label">维度覆盖</span>
            <strong class="summary-value">{{ detail.dataQuality?.coverageRate ?? '—' }}%</strong>
            <span class="summary-hint">{{ detail.dataQuality?.coveredCount ?? 0 }}/{{ detail.dataQuality?.dimensionCount ?? 0 }} 维有证据</span>
          </div>
          <div class="summary-card">
            <span class="summary-label">规则版本</span>
            <strong class="summary-value rule-version">{{ detail.latest?.ruleVersion || '—' }}</strong>
            <span class="summary-hint">算于 {{ formatTime(detail.latest?.calcTime) }}</span>
          </div>
        </div>
      </template>
    </el-card>

    <template v-if="detail && detail.latest">
      <div class="chart-grid">
        <el-card shadow="never">
          <template #header><span class="card-title">维度雷达</span></template>
          <v-chart v-if="radarDims.length >= 3" class="chart" :option="radarOption" autoresize />
          <el-empty v-else :image-size="70" description="维度不足 3 个，暂不绘制雷达图" />
        </el-card>
        <el-card shadow="never">
          <template #header><span class="card-title">总分趋势</span></template>
          <v-chart v-if="detail.trend.length > 1" class="chart" :option="trendOption" autoresize />
          <el-empty v-else :image-size="70" description="快照不足 2 条，暂无趋势" />
        </el-card>
      </div>

      <el-card shadow="never">
        <template #header>
          <div class="card-head">
            <span class="card-title">维度明细与证据</span>
            <span class="card-sub">分数按各维度 scoreMax 归一化到 100 后加权</span>
          </div>
        </template>
        <el-table :data="dimensions" row-key="dimCode">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="evidence-box">
                <div class="evidence-title">原始证据</div>
                <pre>{{ pretty(row.tags) }}</pre>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="维度" min-width="160">
            <template #default="{ row }">
              <div class="dim-cell">
                <span class="dim-name">{{ row.dimName }}</span>
                <span class="dim-code">{{ row.dimCode }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="分数" width="120">
            <template #default="{ row }">
              <span class="score-text">{{ formatScore(row.score) }}</span>
              <span class="score-max"> / {{ formatScore(row.scoreMax) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="归一化" width="180">
            <template #default="{ row }">
              <el-progress
                v-if="row.normalizedScore != null"
                :percentage="clamp(Number(row.normalizedScore))"
                :stroke-width="8"
                :color="scoreColor(Number(row.normalizedScore))"
              />
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column label="权重" width="110">
            <template #default="{ row }">
              <span>{{ row.weight != null ? `${(Number(row.weight) * 100).toFixed(0)}%` : '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="档位" width="100">
            <template #default="{ row }">
              <el-tag :type="levelTagType(levelOf(row))" size="small" effect="light">{{ levelOf(row) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="sampleCount" label="样本" width="80" />
          <el-table-column label="窗口/算子" min-width="150">
            <template #default="{ row }">
              <span class="dim-code">{{ row.tags?.window || '—' }} · {{ row.tags?.operator || row.tags?.evidence?.operator || '—' }}</span>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <div class="bottom-grid">
        <el-card shadow="never">
          <template #header><span class="card-title">事实标签</span></template>
          <div v-if="factTags.length" class="tag-wrap">
            <el-tag v-for="tag in factTags" :key="tag.key" size="small" effect="plain" :type="tag.type">
              {{ tag.label }}
            </el-tag>
          </div>
          <el-empty v-else :image-size="60" description="暂无事实标签" />
        </el-card>

        <el-card shadow="never">
          <template #header><span class="card-title">当前权重</span></template>
          <div v-if="ruleWeights.length" class="weight-list">
            <div v-for="item in ruleWeights" :key="item.code" class="weight-row">
              <span class="weight-name">{{ item.name }}</span>
              <el-progress :percentage="Math.round(item.weight * 100)" :show-text="false" :stroke-width="8" />
              <span class="weight-value">{{ (item.weight * 100).toFixed(0) }}%</span>
            </div>
            <div class="weight-foot">版本 {{ detail.rule?.ruleVersion }} · {{ detail.rule?.status }}</div>
          </div>
          <el-empty v-else :image-size="60" description="尚未发布权重规则，引擎按维度均分" />
        </el-card>

        <el-card shadow="never">
          <template #header><span class="card-title">主体资料</span></template>
          <el-descriptions :column="1" size="small" border>
            <el-descriptions-item label="首次事件">{{ formatTime(detail.subject?.firstEventTime) }}</el-descriptions-item>
            <el-descriptions-item label="最近事件">{{ formatTime(detail.subject?.lastEventTime) }}</el-descriptions-item>
            <el-descriptions-item v-for="(value, key) in detail.subjectProperties" :key="key" :label="String(key)">
              {{ value }}
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </div>

      <el-card shadow="never">
        <template #header><span class="card-title">快照链</span></template>
        <el-timeline>
          <el-timeline-item v-for="h in detail.history" :key="h.snapshotId" :timestamp="formatTime(h.calcTime)" placement="top">
            <div class="timeline-row">
              <strong>{{ formatScore(h.totalScore) }}</strong>
              <span class="dim-code">规则 {{ h.ruleVersion || '—' }} · 快照 {{ h.snapshotId }}</span>
            </div>
          </el-timeline-item>
        </el-timeline>
      </el-card>
    </template>

    <el-card v-else-if="!loading" shadow="never">
      <el-empty description="该主体暂无画像快照（先产生事件并触发重算）" />
    </el-card>
  </div>
</template>

<script setup name="DcsProfileDetail" lang="ts">
import { use } from 'echarts/core';
import { CanvasRenderer } from 'echarts/renderers';
import { LineChart, RadarChart } from 'echarts/charts';
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components';
import VChart from 'vue-echarts';
import { useChartTokens, alpha } from '@/utils/chart-theme';
import { getScore, type ProfileDetail, type ProfileDimensionRow } from '@/api/dcs/core/profile';

use([CanvasRenderer, LineChart, RadarChart, GridComponent, LegendComponent, TooltipComponent]);

const route = useRoute();
const router = useRouter();

const source = computed(() => String(route.params.source));
const subjectId = computed(() => String(route.params.subjectId));
const subjectType = computed(() => String(route.query.subjectType || 'user'));

const loading = ref(false);
const detail = ref<ProfileDetail | null>(null);
/** 图表色随主题切换 */
const ct = useChartTokens();

const subjectName = computed(() => detail.value?.subject?.displayName || subjectId.value);
const dimensions = computed<ProfileDimensionRow[]>(() =>
  [...(detail.value?.dimensions || [])].sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0))
);
const radarDims = computed(() => dimensions.value.filter((d) => !d.skipped && !d.missing && Number.isFinite(Number(d.normalizedScore ?? d.score))));

const radarOption = computed<any>(() => ({
  color: ct.value.series,
  tooltip: { trigger: 'item' },
  radar: {
    radius: '68%',
    indicator: radarDims.value.map((d) => ({ name: d.dimName, max: 100 })),
    splitArea: { areaStyle: { color: [alpha(ct.value.accent, 0.04), alpha(ct.value.accent, 0.1)] } },
    splitLine: { lineStyle: { color: ct.value.rule } },
    axisLine: { lineStyle: { color: ct.value.rule } },
    axisName: { color: ct.value.muted, fontSize: 12 }
  },
  series: [
    {
      type: 'radar',
      symbolSize: 5,
      data: [
        {
          value: radarDims.value.map((d) => Number(d.normalizedScore ?? d.score)),
          name: '维度得分',
          itemStyle: { color: ct.value.accent },
          areaStyle: { opacity: 0.22, color: ct.value.accent },
          lineStyle: { width: 2, color: ct.value.accent }
        }
      ]
    }
  ]
}));

const trendOption = computed<any>(() => {
  const trend = detail.value?.trend || [];
  return {
    color: ct.value.series,
    tooltip: { trigger: 'axis' },
    grid: { left: 42, right: 18, top: 20, bottom: 30 },
    xAxis: {
      type: 'category',
      boundaryGap: false,
      data: trend.map((t) => formatTime(t.calcTime)),
      axisLabel: { color: ct.value.faint },
      axisLine: { lineStyle: { color: ct.value.border } }
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      axisLabel: { color: ct.value.faint },
      splitLine: { lineStyle: { type: 'dashed', color: ct.value.rule } }
    },
    series: [
      {
        type: 'line',
        smooth: true,
        symbolSize: 6,
        data: trend.map((t) => Number(t.totalScore ?? 0)),
        itemStyle: { color: ct.value.accent },
        areaStyle: { opacity: 0.12, color: ct.value.accent },
        lineStyle: { width: 2.5, color: ct.value.accent }
      }
    ]
  };
});

const factTags = computed(() => {
  const out: { key: string; label: string; type: 'success' | 'warning' | 'danger' | 'info' }[] = [];
  for (const dim of dimensions.value) {
    const tags = dim.tags || {};
    Object.entries(tags).forEach(([key, value]) => {
      if (key === 'evidence' || value === null || value === undefined) return;
      if (typeof value === 'object') return;
      const text = String(value);
      if (!text || text === 'all-time') return;
      out.push({
        key: `${dim.dimCode}:${key}`,
        label: `${dim.dimName} · ${key}=${text}`,
        type: key === 'missing' ? 'warning' : key === 'lastText' ? 'info' : 'success'
      });
    });
  }
  return out.slice(0, 24);
});

const ruleWeights = computed(() => {
  const raw = detail.value?.rule?.weights;
  if (!raw) return [];
  let weights: Record<string, number>;
  try {
    weights = typeof raw === 'string' ? JSON.parse(raw) : raw;
  } catch {
    return [];
  }
  return Object.entries(weights)
    .map(([code, weight]) => ({
      code,
      weight: Number(weight),
      name: dimensions.value.find((d) => d.dimCode === code)?.dimName || code
    }))
    .sort((a, b) => b.weight - a.weight);
});

const back = () => router.back();
const clamp = (value: number) => Math.max(0, Math.min(100, Number.isFinite(value) ? value : 0));
const scoreColor = (value: number) =>
  value >= 75 ? 'var(--el-color-success)' : value >= 60 ? 'var(--el-color-warning)' : 'var(--el-color-danger)';
const formatScore = (value?: number | string | null) => {
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  return Number.isFinite(n) ? n.toFixed(1) : String(value);
};
const formatTime = (value?: string | null) => {
  if (!value) return '—';
  return String(value).replace('T', ' ').slice(5, 16);
};
const levelOf = (row: ProfileDimensionRow) => {
  if (row.skipped) return '已跳过';
  if (row.missing || row.tags?.missing === true) return '缺证据';
  const n = Number(row.normalizedScore ?? row.score);
  if (!Number.isFinite(n)) return '—';
  if (n >= 90) return '优秀';
  if (n >= 75) return '良好';
  if (n >= 60) return '待提升';
  return '偏弱';
};
const pretty = (value: unknown) => {
  try {
    return JSON.stringify(value ?? {}, null, 2);
  } catch {
    return String(value);
  }
};
const riskTagType = (color?: string) => (color === 'success' ? 'success' : color === 'warning' ? 'warning' : color === 'danger' ? 'danger' : 'info');
const levelTagType = (level?: string) =>
  level === '优秀'
    ? 'success'
    : level === '良好'
      ? 'success'
      : level === '待提升'
        ? 'warning'
        : level === '偏弱'
          ? 'danger'
          : level === '缺证据'
            ? 'info'
            : 'info';

const load = async () => {
  loading.value = true;
  try {
    detail.value = await getScore(source.value, subjectId.value, subjectType.value, 10);
  } finally {
    loading.value = false;
  }
};

onMounted(load);
watch([source, subjectId, subjectType], ([s, i, t], [os, oi, ot]) => {
  if (s !== os || i !== oi || t !== ot) load();
});
</script>

<style scoped>
.detail-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.head-card :deep(.el-card__body) {
  padding-bottom: 16px;
}
.head-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
}
.head-main {
  display: flex;
  align-items: flex-start;
  gap: 6px;
}
.head-title h3 {
  margin: 4px 0 4px;
  font-size: 20px;
  font-weight: 650;
}
.head-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.summary-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 10px;
  margin-top: 16px;
}
.summary-card {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 12px 14px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  background: var(--el-fill-color-blank);
}
.summary-card.primary {
  border-color: var(--el-color-primary-light-5);
  background: var(--el-color-primary-light-9);
}
.summary-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.summary-value {
  font-size: 24px;
  font-variant-numeric: tabular-nums;
  line-height: 1.15;
}
.summary-value.rule-version {
  font-size: 16px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
.summary-hint {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.chart-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}
.chart {
  height: 300px;
  width: 100%;
}
.card-title {
  font-weight: 600;
}
.card-sub {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.card-head {
  display: flex;
  justify-content: space-between;
  align-items: baseline;
  gap: 10px;
}
.dim-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.dim-name {
  font-weight: 600;
}
.dim-code {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.score-text {
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}
.score-max {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}
.muted {
  color: var(--el-text-color-placeholder);
}
.evidence-box {
  padding: 8px 18px 12px;
}
.evidence-title {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin-bottom: 6px;
}
.evidence-box pre {
  margin: 0;
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-fill-color-light);
  font-size: 12px;
  line-height: 1.55;
  max-height: 260px;
  overflow: auto;
}
.bottom-grid {
  display: grid;
  grid-template-columns: 1.1fr 1fr 1fr;
  gap: 14px;
}
.tag-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.weight-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.weight-row {
  display: grid;
  grid-template-columns: 90px 1fr 44px;
  gap: 10px;
  align-items: center;
}
.weight-name {
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.weight-value {
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-size: 13px;
}
.weight-foot {
  font-size: 12px;
  color: var(--el-text-color-placeholder);
  margin-top: 4px;
}
.timeline-row {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
@media (max-width: 1100px) {
  .summary-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
  .chart-grid,
  .bottom-grid {
    grid-template-columns: 1fr;
  }
}
@media (max-width: 720px) {
  .summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
