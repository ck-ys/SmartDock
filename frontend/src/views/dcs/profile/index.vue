<template>
  <div class="app-container profile-page">
    <dcs-operations-page-header
      eyebrow="画像引擎"
      title="用户画像"
      subtitle="事件驱动的通用画像引擎：主体、维度、权重、排行与证据一条链。画像分析的是被服务的人（访客 / 接任务的人）——发布、提交、审核这些业务动作才进画像，配置与运维操作不进。"
      :metrics="overviewMetrics"
    >
      <template #actions>
        <el-select v-if="!noRealSource" v-model="source" class="source-select" @change="onSourceChange">
          <el-option v-for="s in sourceOpts" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
        <el-button v-hasPermi="['profile:review:query']" @click="reviewVisible = true">低分复核</el-button>
        <el-button type="primary" @click="noRealSource ? refreshSources() : loadAll()">刷新</el-button>
      </template>
    </dcs-operations-page-header>

    <el-card v-if="noRealSource" shadow="never" class="empty-guide">
      <!-- 三审 dspro P2: 全部源被停用 ≠ 没数据——数据都在库里, 指引去启用而不是去接入 -->
      <template v-if="sourceHiddenCount > 0">
        <div class="empty-guide__title">数据都在，{{ sourceHiddenCount }} 个来源被停用了</div>
        <p class="empty-guide__text">来源停用后从所有页面隐藏，事件与主体数据原样保留——启用后立刻回来。</p>
        <div class="empty-guide__foot">
          <el-button v-if="canEditSource" type="primary" @click="goEnableSource">去引擎管理 · 启用来源</el-button>
          <el-button :loading="!sourcesReady" @click="refreshSources">重新检测</el-button>
          <span v-if="!canEditSource" class="empty-guide__hint">来源启停由管理员在「引擎与运维 → 引擎运维 → 引擎管理 → 来源管理」操作</span>
        </div>
      </template>
      <template v-else>
        <div class="empty-guide__title">还没有任何数据</div>
        <p class="empty-guide__text">
          画像分析的是真实业务操作——在系统里发布/提交/审核任务，或照 dcs 的做法把你的业务数据接进来，这里会自动出现真实的主体与分数。
        </p>
        <AccessGuideCard class="empty-guide__card" />
        <div class="empty-guide__foot">
          <el-button :loading="!sourcesReady" @click="refreshSources">重新检测</el-button>
          <span v-if="!canTestSend" class="empty-guide__hint">数据接入由管理员在「引擎与运维 → 引擎运维」里配置</span>
        </div>
      </template>
    </el-card>

    <el-card v-if="sourcesReady && !noRealSource" shadow="never" class="table-card">
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <el-tab-pane name="list">
          <template #label>
            <span>最近活跃的人</span>
          </template>
          <!-- 题3 口径标注: 排序=最近事件优先, 行里都是系统里真实干过活的人 -->
          <div class="tab-caption muted">按最近事件排序 · 系统里真的发布 / 提交 / 审核过任务的人 · 配置与运维操作不进画像</div>
          <div class="filter-bar">
            <el-input v-model="query.keyword" placeholder="主体 ID / 名称" clearable style="width: 150px" @keyup.enter="search" />
            <el-select v-model="query.subjectType" clearable placeholder="主体类型" style="width: 110px">
              <el-option label="用户" value="user" />
              <el-option label="订单" value="order" />
              <el-option label="访客" value="visitor" />
            </el-select>
            <el-input-number v-model="query.minScore" :min="0" :max="100" placeholder="最低分" controls-position="right" style="width: 104px" />
            <el-input-number v-model="query.maxScore" :min="0" :max="100" placeholder="最高分" controls-position="right" style="width: 104px" />
            <el-select v-model="query.risk" clearable placeholder="风险档" style="width: 100px" @change="search">
              <el-option label="高风险" value="HIGH" />
              <el-option label="关注" value="MEDIUM" />
              <el-option label="低风险" value="LOW" />
            </el-select>
            <el-select v-model="query.sortBy" style="width: 110px">
              <el-option label="最近事件" value="lastEvent" />
              <el-option label="总分" value="score" />
              <el-option label="事件数" value="events" />
              <el-option label="名称" value="name" />
            </el-select>
            <el-select v-model="query.sortOrder" style="width: 90px">
              <el-option label="降序" value="desc" />
              <el-option label="升序" value="asc" />
            </el-select>
            <div class="filter-actions">
              <el-button @click="search">查询</el-button>
              <el-button @click="resetQuery">重置</el-button>
              <DcsTableColumnSettings v-model="hiddenSubjectColumns" :columns="subjectColumnOptions" storage-key="profile-subjects" />
            </div>
          </div>

          <el-table v-loading="loading" class="ui-table" :data="rows" row-key="id">
            <template #empty>
              <div class="ui-empty">
                <p class="ui-empty__title">没有符合条件的主体</p>
                <p class="ui-empty__hint">调整筛选条件，或先产生事件并触发重算</p>
              </div>
            </template>
            <el-table-column label="主体" min-width="190">
              <template #default="{ row }">
                <div class="subject-cell">
                  <span class="subject-name">{{ row.displayName || row.subjectId }}</span>
                  <span class="subject-meta">{{ row.subjectType }} · {{ row.subjectId }}</span>
                </div>
              </template>
            </el-table-column>
            <el-table-column v-if="canSeeScore && isSubjectColumnVisible('score')" label="总分" width="170">
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
            <template v-for="dim in dimensions" :key="dim.dimCode">
              <el-table-column v-if="canSeeScore && isSubjectColumnVisible('dim:' + dim.dimCode)" :label="dim.dimName" width="120" align="right">
                <template #default="{ row }">
                  <span :class="dimScoreClass(row, dim.dimCode)">{{ dimScore(row, dim.dimCode) }}</span>
                </template>
              </el-table-column>
            </template>
            <el-table-column v-if="isSubjectColumnVisible('eventCount')" prop="eventCount" label="事件数" width="90" align="right" />
            <!-- 时间列右对齐：与相邻的事件数同列气质，且表格横向滚动时固定操作列左边不会露出时间的前半个字 -->
            <el-table-column v-if="isSubjectColumnVisible('lastEvent')" label="最近事件" width="170" align="right">
              <template #default="{ row }">{{ row.lastEventTime || '—' }}</template>
            </el-table-column>
            <el-table-column v-if="canSeeScore && isSubjectColumnVisible('risk')" label="风险" width="100">
              <template #default="{ row }">
                <span v-if="row.totalScore != null" :class="['ui-pill', riskPillClass(row.totalScore)]">
                  {{ riskOf(row.totalScore).label }}
                </span>
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
          <!-- 题3 口径标注: 分数哪来的说清楚, 空了不再让用户猜 -->
          <div class="tab-caption muted">按当前来源的事件计算 · 分数来自最近一次重算快照 · 没有快照的维度不参与排行</div>
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
          <el-table v-loading="rankingLoading" class="ui-table" :data="rankingRows">
            <template #empty>
              <div class="ui-empty">
                <p class="ui-empty__title">该来源还没有这类数据</p>
                <p class="ui-empty__hint">排行要有评分快照——先在系统里产生真实操作，或到引擎运维触发一次重算</p>
              </div>
            </template>
            <el-table-column prop="rank" label="排名" width="70" align="right">
              <template #default="{ row }">
                <span class="ui-pill ui-pill--muted rank-pill">{{ row.rank }}</span>
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
            <el-table-column label="分数" width="130" align="right">
              <template #default="{ row }">
                <strong>{{ formatScore(ranking.dimension ? row.dimensionScore : row.totalScore) }}</strong>
              </template>
            </el-table-column>
            <el-table-column prop="eventCount" label="事件数" width="100" align="right" />
            <el-table-column label="最近事件" width="180">
              <template #default="{ row }">{{ row.lastEventTime || '—' }}</template>
            </el-table-column>
            <el-table-column label="操作" width="110" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="goDetail(row)">看画像</el-button>
              </template>
            </el-table-column>
          </el-table>
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
              <el-table class="ui-table" :data="basisDimensions">
                <template #empty>
                  <div class="ui-empty">
                    <p class="ui-empty__title">暂无维度得分</p>
                  </div>
                </template>
                <el-table-column label="维度" min-width="130">
                  <template #default="{ row }"
                    >{{ row.dimName || row.code }} <span class="mono muted">{{ row.code }}</span></template
                  >
                </el-table-column>
                <el-table-column label="得分" width="80" align="right">
                  <template #default="{ row }">{{ formatScore(row.score) }}</template>
                </el-table-column>
                <el-table-column label="权重" width="70" align="right">
                  <template #default="{ row }">{{ row.weight ?? '—' }}</template>
                </el-table-column>
                <el-table-column label="加权分" width="90" align="right">
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
    <el-drawer v-model="reviewVisible" title="低分复核" size="72%" destroy-on-close class="review-drawer">
      <ReviewPanel :source="source" />
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
import { formatScore } from '@/utils/score';
import { useSourceOptions, useSourceEmpty, useSourceHidden, autoPickSource, invalidateSources } from '@/composables/useProfileSources';
import ReviewPanel from './review/ReviewPanel.vue';
import AccessGuideCard from './AccessGuideCard.vue';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';
const { list: sourceOpts, ensureSources } = useSourceOptions('subject');
const sourceEmpty = useSourceEmpty('subject');
// 全部源被停用 ≠ 没数据(三审 dspro P2-1): 空态卡按 hidden 数分叉指引「去启用」而非「去接入」
const sourceHiddenCount = useSourceHidden('subject');
const canEditSource = checkPermi(['profile:source:edit']);
const noRealSource = computed(() => sourceEmpty.value);
// 三态(三审 dspro P2): 源清单没回来前既不渲染空态卡也不渲染主内容
const sourcesReady = ref(false);
/** 空态卡「重新检测」/空态下点「刷新」: 强制失效缓存重拉清单(三审 dspro P1), 空态解除则自动选源并加载 */
const refreshSources = async () => {
  invalidateSources();
  sourcesReady.value = false;
  await ensureSources();
  sourcesReady.value = true;
  if (noRealSource.value) return;
  autoPickSource(sourceOpts, source);
  loadAll();
};
// 低分复核并入画像页(2026-10-27 第一批): 抽屉入口(支持 ?review=1 深链自动打开)
const reviewVisible = ref(false);
// 空态引导卡里的测试发送入口由 AccessGuideCard 自带(按 profile:engine:query 显隐),
// 这里只判无权限者要不要显示「找管理员」脚注
const canTestSend = checkPermi(['profile:engine:query']);
import DcsTableColumnSettings from '@/components/DcsTableColumnSettings/index.vue';
import { readHiddenColumns, type TableColumnOption } from '@/components/DcsTableColumnSettings/table-columns';

const router = useRouter();
const route = useRoute();
const goEnableSource = () => router.push('/insight/engine-ops/profile-ops?tab=admin&sub=source');
// 批11 列级权限: 无 profile:pii:score 权限者不渲染分数/维度/风险三族列(后端同步置空)
const canSeeScore = checkPermi(['profile:pii:score']);

const source = ref('dcs');
// 测试发送闭环回跳(三审 dsflash P2): 带 ?source= 直达刚灌数据的源;
// 放在 initialSource 快照前, autoPick 只在"源不在真实清单"时才改选, 在清单内则保留
if (typeof route.query.source === 'string' && route.query.source) source.value = route.query.source;
// 真实数据优先: 清单加载后当前源不在真实清单里就自动切到唯一/首个真实源。
// 首挂单轮加载(三审 dspro P1): setup 不再"先拉一轮默认源", onMounted 等清单→autoPick→一次 loadAll,
// 消灭旧源/新源两轮并发、慢响应乱序覆盖的竞态。清单回来前用户已手选过源则不自动改选。
const initialSource = source.value;
const activeTab = ref('list');
const loading = ref(false);
const overviewLoading = ref(false);
const rankingLoading = ref(false);
const rows = ref<ProfileSubjectRow[]>([]);
const total = ref(0);
const dimensions = ref<ProfileDimension[]>([]);
const rankingRows = ref<ProfileRankingRow[]>([]);
const overview = ref<Partial<ProfileOverview>>({});

// 交互三件 ③：主体列表列显隐（按页面存 localStorage；主体/操作列常显）
const SUBJECT_COLUMN_STORAGE_KEY = 'profile-subjects';
const subjectColumnOptions = computed<TableColumnOption[]>(() => {
  const options: TableColumnOption[] = [];
  if (canSeeScore) {
    options.push({ key: 'score', label: '总分' });
    dimensions.value.forEach((dim) => options.push({ key: `dim:${dim.dimCode}`, label: dim.dimName || dim.dimCode }));
  }
  options.push({ key: 'eventCount', label: '事件数' }, { key: 'lastEvent', label: '最近事件' });
  if (canSeeScore) {
    options.push({ key: 'risk', label: '风险' });
  }
  return options;
});
const hiddenSubjectColumns = ref<string[]>(readHiddenColumns(SUBJECT_COLUMN_STORAGE_KEY));
const isSubjectColumnVisible = (key: string) => !hiddenSubjectColumns.value.includes(key);

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
/* P2(2026-10-27 拍板A)：分数口径收敛到共享 formatScore（一位小数），本地副本移除 */
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
const riskPillClass = (value?: number | string | null) =>
  ({ success: 'ui-pill--pos', warning: 'ui-pill--warn', danger: 'ui-pill--neg', info: 'ui-pill--muted' })[riskOf(value).color];

// 指标带只留 4 格：关注/高风险直接回答「谁要跟」，已算分是分母、低风险是结论的补集，
// 两者下方分布图里都有，不再各占一格（KPI 一容器 3–4 格）
// 2026-10-27 甲+A：关注/高风险带 tone，数字按「要人动手」的严重度上色（黄→红），其余保持主文字色。
// 有值才上色：0 和加载中的「—」不上色，否则零数据租户一进页面就像全线告警
const riskTone = (v: number | null | undefined, tone: 'warning' | 'danger') =>
  (Number(v ?? 0) > 0 ? tone : undefined);
const overviewMetrics = computed(() => [
  { label: '主体数', value: overview.value.subjectCount ?? '—' },
  { label: '平均分', value: formatScore(overview.value.avgScore) },
  { label: '关注', value: overview.value.mediumRisk ?? '—', tone: riskTone(overview.value.mediumRisk, 'warning') },
  { label: '高风险', value: overview.value.highRisk ?? '—', tone: riskTone(overview.value.highRisk, 'danger') }
]);

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
  loadedOnce = true;
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

onMounted(async () => {
  // 三审 dspro P2(三态): 清单没回来前不渲染主内容, 避免空库先闪一版空表再换空态卡
  await ensureSources();
  sourcesReady.value = true;
  if (source.value === initialSource) autoPickSource(sourceOpts, source);
  // 三审 glm53 P2: 空库跳过注定为空的请求(诚实空态卡此时已就位), 数据由 onActivated 自愈补拉
  if (!noRealSource.value) loadAll();
  // 旧「低分复核」菜单/总览页跳转的深链兜底: ?review=1 自动开抽屉
  if (route.query.review === '1' && checkPermi(['profile:review:query'])) reviewVisible.value = true;
});

// 三审 dspro P1(缓存自愈): 源清单是模块级缓存, 测试发送产生新源/失败重试成功后旧缓存不会失效——
// keep-alive 回页时轻量 ensureSources(缓存有效=共享 promise 空转); 空态解除或源被自动切走时补一轮加载
let loadedOnce = false;
onActivated(async () => {
  const before = source.value;
  await ensureSources();
  sourcesReady.value = true;
  if (noRealSource.value) return;
  if (source.value === before) autoPickSource(sourceOpts, source);
  if (source.value !== before || !loadedOnce) onSourceChange();
});

// 三审 dspro P2: keep-alive 下二次导航同路由只变 query, onMounted/watch 都不会再跑
watch(
  () => route.query.review,
  (v) => {
    if (v === '1' && checkPermi(['profile:review:query'])) reviewVisible.value = true;
  }
);
</script>

<style scoped>
.profile-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.profile-page :deep(.dcs-ops-header) {
  margin-bottom: 4px;
}
/* 语义色只在本页生效：关注/高风险是唯一「要人动手」的两个数。
   写成全局规则会连带染上所有复用页头的页面（累计调用、待支付这类中性量） */
.profile-page :deep(.dcs-ops-metric--warning .dcs-ops-metric__value) {
  color: var(--el-color-warning);
}
.profile-page :deep(.dcs-ops-metric--danger .dcs-ops-metric__value) {
  color: var(--el-color-danger);
}
.profile-page :deep(.dcs-ops-header__actions) {
  flex-wrap: wrap;
}
@media (min-width: 993px) {
  .source-select {
    width: 150px;
  }
}
.table-card :deep(.el-card__body) {
  padding-top: 6px;
  min-width: 0;
}
/* el-table 走 CSS 变量：表头无底带、行线走令牌、正文灰阶（不重写组件） */
.profile-page :deep(.el-table) {
  --el-table-border-color: var(--app-rule);
  --el-table-header-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: var(--app-raised);
}
/* 状态药丸：统一浅底深字，形状/字号与 .ui-pill 同口径 */
.profile-page :deep(.el-tag) {
  --el-tag-bg-color: var(--dcs-pill-off-bg);
  --el-tag-text-color: var(--dcs-pill-off-ink);
  --el-tag-border-color: transparent;
  height: 22px;
  padding: 0 10px;
  border: 0;
  border-radius: var(--dcs-pill-radius);
  font-size: var(--dcs-font-xs);
  font-weight: 500;
}
.profile-page :deep(.el-tag--success) {
  --el-tag-bg-color: var(--dcs-pill-pos-bg);
  --el-tag-text-color: var(--dcs-pill-pos-ink);
}
.profile-page :deep(.el-tag--warning) {
  --el-tag-bg-color: var(--dcs-pill-warn-bg);
  --el-tag-text-color: var(--dcs-pill-warn-ink);
}
.profile-page :deep(.el-tag--danger) {
  --el-tag-bg-color: var(--dcs-pill-neg-bg);
  --el-tag-text-color: var(--dcs-pill-neg-ink);
}
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
  min-width: 0;
}
/* 查询/重置/列设置作为一个整体换行：窄屏时要么整组在行尾，要么整组掉下一行，
   不允许把「重置」单独甩到下一行（侧栏 228→256 后 1440 宽就踩过这个坑） */
.filter-actions {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 8px;
  margin-left: auto;
}
/* P8⑤(2026-10-27 拍板A)：窄屏筛选项换行后动作组独占一行，不再被 auto 推右悬空 */
@media (max-width: 992px) {
  .filter-actions {
    margin-left: 0;
  }
}
.subject-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.35;
}
.subject-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
  line-height: 1.4;
}
.subject-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.35;
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
.rank-pill {
  min-width: 24px;
  justify-content: center;
}

.empty-guide {
  text-align: center;
  padding: 18px 0 6px;
}
/* 第二批: 空态卡里嵌接入示例卡(左对齐块), 脚注行恢复左对齐 */
.empty-guide :deep(.access-guide) {
  text-align: left;
  border: 0;
  box-shadow: none;
}
.empty-guide__foot {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  flex-wrap: wrap;
}
.tab-caption {
  font-size: 12px;
  margin-bottom: 8px;
}
.empty-guide__title {
  font-size: 16px;
  font-weight: 700;
  margin-bottom: 6px;
}
.empty-guide__hint {
  margin: 4px 0 0;
  color: var(--el-text-color-secondary);
  font-size: 12.5px;
}
.empty-guide__text {
  max-width: 560px;
  margin: 0 auto 14px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  line-height: 1.7;
}
</style>
