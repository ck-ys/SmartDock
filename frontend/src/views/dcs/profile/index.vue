<template>
  <div class="app-container profile-page">
    <el-card shadow="never" class="hero-card">
      <div class="hero-head">
        <div class="hero-copy">
          <h3>用户画像</h3>
          <p>事件驱动的通用画像引擎：主体、维度、权重、排行与证据一条链。</p>
        </div>
        <div class="hero-actions">
          <el-select v-model="source" style="width: 150px" @change="onSourceChange">
            <el-option label="dcs 数据采集" value="dcs" />
            <el-option label="risk 风控" value="risk" />
          </el-select>
          <el-button @click="goStudio">画像工作室</el-button>
          <el-button type="primary" @click="loadAll">刷新</el-button>
        </div>
      </div>
      <div class="stat-grid" v-loading="overviewLoading">
        <div class="stat">
          <span class="stat-label">主体数</span>
          <strong>{{ overview.subjectCount ?? '—' }}</strong>
        </div>
        <div class="stat">
          <span class="stat-label">已算分</span>
          <strong>{{ overview.scoredCount ?? '—' }}</strong>
        </div>
        <div class="stat">
          <span class="stat-label">平均分</span>
          <strong>{{ formatScore(overview.avgScore) }}</strong>
        </div>
        <div class="stat stat-low">
          <span class="stat-label">低风险</span>
          <strong>{{ overview.lowRisk ?? '—' }}</strong>
        </div>
        <div class="stat stat-medium">
          <span class="stat-label">关注</span>
          <strong>{{ overview.mediumRisk ?? '—' }}</strong>
        </div>
        <div class="stat stat-high">
          <span class="stat-label">高风险</span>
          <strong>{{ overview.highRisk ?? '—' }}</strong>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <el-tab-pane label="主体列表" name="list">
          <div class="filter-bar">
            <el-input v-model="query.keyword" placeholder="主体 ID / 名称" clearable style="width: 200px" @keyup.enter="search" />
            <el-select v-model="query.subjectType" clearable placeholder="主体类型" style="width: 130px">
              <el-option label="用户" value="user" />
              <el-option label="订单" value="order" />
              <el-option label="访客" value="visitor" />
            </el-select>
            <el-input-number v-model="query.minScore" :min="0" :max="100" placeholder="最低分" controls-position="right" style="width: 120px" />
            <el-input-number v-model="query.maxScore" :min="0" :max="100" placeholder="最高分" controls-position="right" style="width: 120px" />
            <el-select v-model="query.risk" clearable placeholder="风险档" style="width: 120px" @change="search">
              <el-option label="高风险" value="HIGH" />
              <el-option label="关注" value="MEDIUM" />
              <el-option label="低风险" value="LOW" />
            </el-select>
            <el-select v-model="query.sortBy" style="width: 130px">
              <el-option label="最近事件" value="lastEvent" />
              <el-option label="总分" value="score" />
              <el-option label="事件数" value="events" />
              <el-option label="名称" value="name" />
            </el-select>
            <el-select v-model="query.sortOrder" style="width: 100px">
              <el-option label="降序" value="desc" />
              <el-option label="升序" value="asc" />
            </el-select>
            <el-button type="primary" @click="search">查询</el-button>
            <el-button @click="resetQuery">重置</el-button>
          </div>

          <el-table v-loading="loading" :data="rows" row-key="id" stripe>
            <el-table-column label="主体" min-width="190">
              <template #default="{ row }">
                <div class="subject-cell">
                  <span class="subject-name">{{ row.displayName || row.subjectId }}</span>
                  <span class="subject-meta">{{ row.subjectType }} · {{ row.subjectId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column v-if="canSeeScore" label="总分" width="170">
              <template #default="{ row }">
                <div v-if="row.totalScore != null" class="score-cell">
                  <el-progress
                    :percentage="clamp(Number(row.totalScore))"
                    :stroke-width="8"
                    :show-text="false"
                    :color="scoreColor(Number(row.totalScore))"
                  />
                  <span class="score-number">{{ formatScore(row.totalScore) }}</span>
                </div>
                <span v-else class="muted">未算分</span>
              </template>
            </el-table-column>
            <el-table-column v-if="canSeeScore" v-for="dim in dimensions" :key="dim.dimCode" :label="dim.dimName" width="120">
              <template #default="{ row }">
                <span :class="dimScoreClass(row, dim.dimCode)">{{ dimScore(row, dim.dimCode) }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="eventCount" label="事件数" width="90" />
            <el-table-column label="最近事件" width="170">
              <template #default="{ row }">{{ row.lastEventTime || '—' }}</template>
            </el-table-column>
            <el-table-column v-if="canSeeScore" label="风险" width="100">
              <template #default="{ row }">
                <el-tag v-if="row.totalScore != null" :type="riskOf(row.totalScore).color" effect="light">
                  {{ riskOf(row.totalScore).label }}
                </el-tag>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="160" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="goDetail(row)">看画像</el-button>
                <el-button link @click="openBasis(row)">依据</el-button>
              </template>
            </el-table-column>
          </el-table>
          <pagination v-show="total > 0" v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="loadList" />
        </el-tab-pane>

        <el-tab-pane label="维度排行" name="ranking">
          <div class="filter-bar">
            <el-select v-model="ranking.dimension" clearable placeholder="按维度排行" style="width: 180px" @change="loadRanking">
              <el-option label="总分" value="" />
              <el-option v-for="dim in dimensions" :key="dim.dimCode" :label="dim.dimName" :value="dim.dimCode" />
            </el-select>
            <el-select v-model="ranking.limit" style="width: 120px" @change="loadRanking">
              <el-option label="Top 10" :value="10" />
              <el-option label="Top 20" :value="20" />
              <el-option label="Top 50" :value="50" />
            </el-select>
            <el-button @click="loadRanking">刷新排行</el-button>
          </div>
          <el-table v-loading="rankingLoading" :data="rankingRows" stripe>
            <el-table-column prop="rank" label="排名" width="70">
              <template #default="{ row }">
                <span :class="['rank-badge', row.rank <= 3 ? `rank-${row.rank}` : '']">{{ row.rank }}</span>
              </template>
            </el-table-column>
            <el-table-column label="主体" min-width="220">
              <template #default="{ row }">
                <div class="subject-cell">
                  <span class="subject-name">{{ row.displayName || row.subjectId }}</span>
                  <span class="subject-meta">{{ row.subjectType }} · {{ row.subjectId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="分数" width="130">
              <template #default="{ row }">
                <strong>{{ formatScore(ranking.dimension ? row.dimensionScore : row.totalScore) }}</strong>
              </template>
            </el-table-column>
            <el-table-column prop="eventCount" label="事件数" width="100" />
            <el-table-column label="最近事件" width="180">
              <template #default="{ row }">{{ row.lastEventTime || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="goDetail(row)">看画像</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-if="!rankingLoading && rankingRows.length === 0" description="暂无排行数据（先产生事件并触发重算）" />
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <!-- 查看依据（单主体画像摘要） -->
    <el-drawer v-model="basisVisible" title="画像依据" size="560px" destroy-on-close>
      <div v-loading="basisLoading" class="drawer-body">
        <template v-if="basisData">
          <template v-if="basisData.found">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="主体">{{ basisData.displayName || basisData.subjectId }}</el-descriptions-item>
              <el-descriptions-item label="主体类型">{{ basisData.subjectType }}</el-descriptions-item>
              <el-descriptions-item label="业务源">{{ basisData.source || '—' }}</el-descriptions-item>
              <el-descriptions-item label="事件数">{{ basisData.eventCount ?? '—' }}</el-descriptions-item>
              <el-descriptions-item label="最近事件" :span="2">{{ basisData.lastEventTime || '—' }}</el-descriptions-item>
            </el-descriptions>
            <template v-if="basisData.scored">
              <div class="basis-score">
                <div>
                  <span class="muted">总分</span>
                  <strong :class="scoreClass(Number(basisData.totalScore))">{{ formatScore(basisData.totalScore) }}</strong>
                </div>
                <div>
                  <span class="muted">规则版本</span>
                  <strong class="mono">{{ basisData.ruleVersion || '—' }}</strong>
                </div>
                <div>
                  <span class="muted">计算时间</span>
                  <strong>{{ basisData.calcTime || '—' }}</strong>
                </div>
              </div>
              <h4 class="basis-title">维度贡献</h4>
              <el-table :data="basisDimensions" size="small" stripe>
                <el-table-column label="维度" min-width="130">
                  <template #default="{ row }"
                    >{{ row.dimName || row.code }} <span class="mono muted">{{ row.code }}</span></template
                  >
                </el-table-column>
                <el-table-column label="得分" width="80">
                  <template #default="{ row }">{{ formatScore(row.score) }}</template>
                </el-table-column>
                <el-table-column label="权重" width="70">
                  <template #default="{ row }">{{ row.weight ?? '—' }}</template>
                </el-table-column>
                <el-table-column label="加权分" width="90">
                  <template #default="{ row }">{{ formatScore(row.weightedScore) }}</template>
                </el-table-column>
              </el-table>
            </template>
            <el-empty v-else :image-size="60" description="该主体还没有评分快照（等待事件触发重算）" />
          </template>
          <el-empty v-else :image-size="60" description="没有找到该主体（可能已被清理）" />
        </template>
      </div>
    </el-drawer>
  </div>
</template>

<script setup name="DcsProfile" lang="ts">
import {
  getOverview,
  getRanking,
  getRiskBands,
  getSubjectSummary,
  listDimensions,
  pageSubjects,
  type ProfileDimension,
  type ProfileDimensionDetail,
  type ProfileOverview,
  type ProfileRankingRow,
  type ProfileSubjectRow,
  type ProfileSubjectSummary
} from '@/api/dcs/core/profile';
import { checkPermi } from '@/utils/permission';

const router = useRouter();
// 批11 列级权限: 无 profile:pii:score 权限者不渲染分数/维度/风险三族列(后端同步置空)
const canSeeScore = checkPermi(['profile:pii:score']);

const source = ref('dcs');
const activeTab = ref('list');
const loading = ref(false);
const overviewLoading = ref(false);
const rankingLoading = ref(false);
const rows = ref<ProfileSubjectRow[]>([]);
const total = ref(0);
const dimensions = ref<ProfileDimension[]>([]);
const rankingRows = ref<ProfileRankingRow[]>([]);
const overview = ref<Partial<ProfileOverview>>({});

const query = reactive({
  pageNum: 1,
  pageSize: 20,
  subjectType: '',
  keyword: '',
  minScore: undefined as number | undefined,
  maxScore: undefined as number | undefined,
  risk: '',
  sortBy: 'lastEvent',
  sortOrder: 'desc'
});
const ranking = reactive({ dimension: '', limit: 10 });

/** 风险分档阈值（批9 Q7-A 单源：后端下发，前端不再硬编码） */
const riskBands = reactive({ mediumMin: 60, highBelow: 60, lowMin: 75 });
const loadRiskBands = async () => {
  try {
    const bands = await getRiskBands();
    riskBands.mediumMin = Number(bands.mediumMin);
    riskBands.highBelow = Number(bands.highBelow);
    riskBands.lowMin = Number(bands.lowMin);
  } catch {
    // 接口不可用时退回默认 60/75
  }
};

const clamp = (value: number) => Math.max(0, Math.min(100, Number.isFinite(value) ? value : 0));
const formatScore = (value?: number | string | null) => {
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  return Number.isFinite(n) ? n.toFixed(1) : String(value);
};
const scoreColor = (value: number) =>
  value >= riskBands.lowMin ? 'var(--el-color-success)' : value >= riskBands.highBelow ? 'var(--el-color-warning)' : 'var(--el-color-danger)';
const scoreClass = (value: number) => (value >= riskBands.lowMin ? 'dim-good' : value >= riskBands.highBelow ? 'dim-mid' : 'dim-low');
const riskOf = (value?: number | string | null) => {
  const n = Number(value);
  if (!Number.isFinite(n)) return { label: '未算分', color: 'info' as const };
  if (n >= riskBands.lowMin) return { label: '低风险', color: 'success' as const };
  if (n >= riskBands.highBelow) return { label: '关注', color: 'warning' as const };
  return { label: '高风险', color: 'danger' as const };
};

const parseDetail = (row: ProfileSubjectRow): Record<string, ProfileDimensionDetail> => {
  const raw = row.dimensionDetail;
  if (!raw) return {};
  try {
    return typeof raw === 'string' ? JSON.parse(raw) : raw;
  } catch {
    return {};
  }
};
const dimValue = (row: ProfileSubjectRow, code: string): ProfileDimensionDetail | undefined => parseDetail(row)[code];
const dimScore = (row: ProfileSubjectRow, code: string) => {
  const entry = dimValue(row, code);
  if (!entry || entry.skipped) return '—';
  if (entry.tags?.missing === true) return '缺证据';
  return formatScore(entry.normalizedScore ?? entry.score);
};
const dimScoreClass = (row: ProfileSubjectRow, code: string) => {
  const entry = dimValue(row, code);
  if (!entry || entry.skipped || entry.tags?.missing === true) return 'muted';
  const n = Number(entry.normalizedScore ?? entry.score);
  return n >= 75 ? 'dim-good' : n >= 60 ? 'dim-mid' : 'dim-low';
};

const loadOverview = async () => {
  overviewLoading.value = true;
  try {
    overview.value = await getOverview(source.value);
  } finally {
    overviewLoading.value = false;
  }
};
const loadDimensions = async () => {
  dimensions.value = await listDimensions(source.value);
};
const loadList = async () => {
  loading.value = true;
  try {
    const res = await pageSubjects({
      source: source.value,
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      subjectType: query.subjectType || undefined,
      keyword: query.keyword || undefined,
      minScore: query.minScore,
      maxScore: query.maxScore,
      risk: query.risk || undefined,
      sortBy: query.sortBy,
      sortOrder: query.sortOrder
    });
    rows.value = res.rows;
    total.value = res.total;
  } finally {
    loading.value = false;
  }
};
const loadRanking = async () => {
  rankingLoading.value = true;
  try {
    rankingRows.value = await getRanking({
      source: source.value,
      dimension: ranking.dimension || undefined,
      limit: ranking.limit
    });
  } finally {
    rankingLoading.value = false;
  }
};
const loadAll = async () => {
  await Promise.all([loadOverview(), loadDimensions(), loadList(), loadRiskBands()]);
  if (activeTab.value === 'ranking') await loadRanking();
};
const onSourceChange = () => {
  query.pageNum = 1;
  loadAll();
};
const onTabChange = (name: string | number) => {
  if (name === 'ranking') loadRanking();
};
const search = () => {
  query.pageNum = 1;
  loadList();
};
const resetQuery = () => {
  query.subjectType = '';
  query.keyword = '';
  query.minScore = undefined;
  query.maxScore = undefined;
  query.risk = '';
  query.sortBy = 'lastEvent';
  query.sortOrder = 'desc';
  query.pageNum = 1;
  loadList();
};
const goDetail = (row: ProfileSubjectRow | ProfileRankingRow) =>
  router.push(`/insight/profile-detail/${source.value}/${row.subjectId}?subjectType=${encodeURIComponent(row.subjectType || 'user')}`);
const goStudio = () => router.push('/insight/profile-studio');

// ---------------------------------------------------------------- 查看依据（批9）
const basisVisible = ref(false);
const basisLoading = ref(false);
const basisData = ref<ProfileSubjectSummary | null>(null);
const basisDimensions = computed(() => {
  const detail = basisData.value?.dimensionDetail;
  if (!detail) return [];
  return Object.entries(detail).map(([code, entry]) => ({
    code,
    dimName: entry.dimName,
    score: entry.normalizedScore ?? entry.score,
    weight: entry.weight,
    weightedScore: entry.weightedScore,
    skipped: entry.skipped
  }));
});
const openBasis = async (row: ProfileSubjectRow) => {
  basisVisible.value = true;
  basisLoading.value = true;
  basisData.value = null;
  try {
    basisData.value = await getSubjectSummary(row.subjectId, row.subjectType || 'user', source.value);
  } finally {
    basisLoading.value = false;
  }
};

onMounted(loadAll);
</script>

<style scoped>
.profile-page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.hero-card :deep(.el-card__body) {
  padding: 18px 20px;
}
.hero-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
.hero-copy h3 {
  margin: 0 0 4px;
  font-size: 18px;
  font-weight: 650;
}
.hero-copy p {
  margin: 0;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.hero-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}
.stat-grid {
  margin-top: 16px;
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 10px;
}
.stat {
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  padding: 10px 12px;
  background: var(--el-fill-color-blank);
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.stat-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.stat strong {
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}
.stat-low strong {
  color: var(--el-color-success);
}
.stat-medium strong {
  color: var(--el-color-warning);
}
.stat-high strong {
  color: var(--el-color-danger);
}
.table-card :deep(.el-card__body) {
  padding-top: 6px;
}
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
}
.subject-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.subject-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.subject-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.score-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.score-cell :deep(.el-progress) {
  flex: 1;
  min-width: 56px;
}
.score-number {
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}
.muted {
  color: var(--el-text-color-placeholder);
}
.mono {
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Consolas, monospace);
}
.drawer-body {
  min-height: 160px;
}
.basis-score {
  display: flex;
  gap: 28px;
  margin: 14px 0;
}
.basis-score > div {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.basis-score strong {
  font-size: 20px;
  font-variant-numeric: tabular-nums;
}
.basis-title {
  margin: 12px 0 8px;
  font-size: 14px;
}
.dim-good {
  color: var(--el-color-success);
  font-weight: 600;
}
.dim-mid {
  color: var(--el-color-warning);
  font-weight: 600;
}
.dim-low {
  color: var(--el-color-danger);
  font-weight: 600;
}
.rank-badge {
  display: inline-flex;
  width: 24px;
  height: 24px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: var(--el-fill-color);
  color: var(--el-text-color-regular);
  font-weight: 600;
  font-size: 12px;
}
.rank-1 {
  background: #f7d774;
  color: #6b4b00;
}
.rank-2 {
  background: #dfe4ea;
  color: #4a5563;
}
.rank-3 {
  background: #e8c39e;
  color: #6b3f1c;
}
@media (max-width: 1100px) {
  .stat-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
@media (max-width: 720px) {
  .hero-head {
    flex-direction: column;
  }
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
