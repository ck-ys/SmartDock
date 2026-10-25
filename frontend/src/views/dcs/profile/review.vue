<template>
  <div class="app-container review-page">
    <dcs-operations-page-header
      eyebrow="画像引擎"
      title="低分复核"
      subtitle="重算后低于阈值的主体自动进入队列；人工确认或驳回后，分数没有继续恶化就不再重复打扰。"
      :metrics="reviewMetrics"
    >
      <template #actions>
        <el-select v-model="source" class="source-select" @change="onSourceChange">
          <el-option label="dcs 数据采集" value="dcs" />
          <el-option label="risk 风控" value="risk" />
        </el-select>
        <el-button v-hasPermi="['profile:review:edit']" :loading="scanning" type="primary" @click="scan">扫描当前快照</el-button>
        <el-button :loading="loading" @click="loadAll">刷新</el-button>
      </template>
    </dcs-operations-page-header>

    <el-card shadow="never" class="config-card">
      <template #header>
        <div class="panel-head">
          <span>引擎策略</span>
          <span class="muted small">修改后立即生效（配置缓存 30 秒）</span>
        </div>
      </template>
      <div class="config-row" v-loading="configLoading">
        <div class="config-item">
          <span class="config-label">事件后自动重算</span>
          <el-switch v-model="config.autoRecalc" active-value="1" inactive-value="0" />
          <span class="muted small">事件写入后只重算该主体</span>
        </div>
        <div class="config-item">
          <span class="config-label">低分自动入队</span>
          <el-switch v-model="config.reviewEnabled" active-value="1" inactive-value="0" />
          <span class="muted small">关闭后不再自动创建复核项</span>
        </div>
        <div class="config-item">
          <span class="config-label">复核阈值</span>
          <el-input-number :model-value="Number(config.reviewThreshold)" :min="0" :max="100" :precision="2" controls-position="right" style="width: 130px" @update:model-value="(v: number) => (config.reviewThreshold = v)" />
          <span class="muted small">低于该总分入队</span>
        </div>
        <div class="config-item">
          <span class="config-label">合并窗口</span>
          <el-input-number v-model="config.debounceMs" :min="0" :max="5000" :step="500" controls-position="right" style="width: 140px" />
          <span class="muted small">毫秒，连续事件只算一次</span>
        </div>
        <div class="config-item">
          <span class="config-label">复核 SLA</span>
          <el-input-number v-model="config.reviewSlaDays" :min="1" :max="365" controls-position="right" style="width: 120px" />
          <span class="muted small">天，超时每天 09:10 提醒管理员</span>
        </div>
        <el-button v-hasPermi="['profile:review:edit']" :loading="configSaving" @click="saveConfig">保存策略</el-button>
      </div>
    </el-card>

    <el-card shadow="never" class="table-card">
      <div class="filter-bar">
        <el-select v-model="query.status" style="width: 140px" @change="search">
          <el-option label="待复核" value="PENDING" />
          <el-option label="已确认" value="CONFIRMED" />
          <el-option label="已驳回" value="DISMISSED" />
          <el-option label="自动销案" value="SUPERSEDED" />
          <el-option label="全部状态" value="" />
        </el-select>
        <el-select v-model="query.riskLevel" clearable placeholder="风险级别" style="width: 130px" @change="search">
          <el-option label="高" value="HIGH" />
          <el-option label="中" value="MEDIUM" />
          <el-option label="低" value="LOW" />
        </el-select>
        <el-input v-model="query.keyword" placeholder="主体 ID / 名称" clearable style="width: 200px" @keyup.enter="search" />
        <el-button @click="search">查询</el-button>
        <el-button @click="resetQuery">重置</el-button>
      </div>

      <el-table v-loading="loading" class="ui-table" :data="rows" row-key="reviewId">
        <template #empty>
          <div class="ui-empty">
            <p class="ui-empty__title">当前筛选下没有复核项</p>
            <p class="ui-empty__hint">调整筛选条件，或先扫描当前快照</p>
          </div>
        </template>
        <el-table-column label="主体" min-width="200">
          <template #default="{ row }">
            <div class="subject-cell">
              <span class="subject-name">{{ row.displayName || row.subjectId }}</span>
              <span class="subject-meta">{{ row.subjectType }} · {{ row.subjectId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="总分" width="110" align="right">
          <template #default="{ row }">
            <strong :class="scoreClass(Number(row.totalScore))">{{ formatScore(row.totalScore) }}</strong>
          </template>
        </el-table-column>
        <el-table-column label="风险" width="90">
          <template #default="{ row }">
            <el-tag :type="riskTagType(row.riskLevel)" effect="light">{{ riskLabel(row.riskLevel) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="触发原因" min-width="240">
          <template #default="{ row }"
            ><span class="muted">{{ row.triggerReason || '—' }}</span></template
          >
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="plain">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="结论说明" min-width="180">
          <template #default="{ row }"
            ><span class="muted">{{ row.reviewNote || row.conclusion || '—' }}</span></template
          >
        </el-table-column>
        <el-table-column label="入队时间" width="170">
          <template #default="{ row }">{{ row.createTime || '—' }}</template>
        </el-table-column>
        <el-table-column label="SLA" width="110">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'PENDING' && Number(row.overdueDays) > 0" type="danger" effect="light" size="small">
              超时 {{ row.overdueDays }} 天
            </el-tag>
            <span v-else-if="row.status === 'PENDING' && row.slaDays" class="muted small">{{ row.slaDays }} 天内</span>
            <span v-else class="muted">—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <template v-if="row.status === 'PENDING'">
              <el-button v-hasPermi="['profile:review:edit']" link type="success" @click="openAction(row, 'confirm')">确认</el-button>
              <el-button v-hasPermi="['profile:review:edit']" link type="warning" @click="openAction(row, 'dismiss')">驳回</el-button>
            </template>
            <el-button link @click="goProfile(row)">看画像</el-button>
            <el-button link @click="openDetail(row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-show="total > 0" v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="loadList" />
    </el-card>

    <!-- 复核处理 -->
    <el-dialog v-model="actionVisible" :title="actionMode === 'confirm' ? '确认低分' : '驳回复核'" width="520px" destroy-on-close>
      <div v-if="activeReview" class="action-summary">
        <div>
          <span class="muted">主体</span>
          <strong>{{ activeReview.displayName || activeReview.subjectId }}</strong>
        </div>
        <div>
          <span class="muted">总分</span>
          <strong>{{ formatScore(activeReview.totalScore) }}</strong>
        </div>
        <div>
          <span class="muted">原因</span>
          <span>{{ activeReview.triggerReason }}</span>
        </div>
      </div>
      <el-form label-width="80px">
        <el-form-item label="风险级别">
          <el-select v-model="actionForm.riskLevel" style="width: 100%">
            <el-option label="高" value="HIGH" />
            <el-option label="中" value="MEDIUM" />
            <el-option label="低" value="LOW" />
          </el-select>
        </el-form-item>
        <el-form-item label="处理说明">
          <el-input v-model="actionForm.note" type="textarea" :rows="4" maxlength="500" placeholder="记录判断依据，便于后续复盘" />
        </el-form-item>
      </el-form>
      <el-alert
        :type="actionMode === 'confirm' ? 'warning' : 'info'"
        :closable="false"
        show-icon
        :title="
          actionMode === 'confirm'
            ? '确认后该主体保持低分结论；若后续分数继续下降，会重新入队。'
            : '驳回表示本次低分属于误报；分数不继续恶化时不再重复入队。'
        "
      />
      <template #footer>
        <el-button @click="actionVisible = false">取消</el-button>
        <el-button v-hasPermi="['profile:review:edit']" :type="actionMode === 'confirm' ? 'success' : 'warning'" :loading="actionSaving" @click="submitAction">
          {{ actionMode === 'confirm' ? '确认低分' : '驳回' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 复核详情 -->
    <el-drawer v-model="detailVisible" title="复核详情" size="560px" destroy-on-close>
      <template v-if="activeReview">
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="主体">{{ activeReview.displayName || activeReview.subjectId }}</el-descriptions-item>
          <el-descriptions-item label="主体类型">{{ activeReview.subjectType }}</el-descriptions-item>
          <el-descriptions-item label="总分">{{ formatScore(activeReview.totalScore) }}</el-descriptions-item>
          <el-descriptions-item label="风险级别">{{ riskLabel(activeReview.riskLevel) }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ statusLabel(activeReview.status) }}</el-descriptions-item>
          <el-descriptions-item label="快照 ID">{{ activeReview.snapshotId || '—' }}</el-descriptions-item>
          <el-descriptions-item label="入队时间" :span="2">{{ activeReview.createTime || '—' }}</el-descriptions-item>
          <el-descriptions-item label="触发原因" :span="2">{{ activeReview.triggerReason || '—' }}</el-descriptions-item>
          <el-descriptions-item label="结论" :span="2">{{ activeReview.conclusion || '—' }}</el-descriptions-item>
          <el-descriptions-item label="处理说明" :span="2">{{ activeReview.reviewNote || '—' }}</el-descriptions-item>
          <el-descriptions-item label="处理时间" :span="2">{{ activeReview.reviewedAt || '—' }}</el-descriptions-item>
        </el-descriptions>
        <div class="detail-actions">
          <el-button @click="goProfile(activeReview)">打开完整画像</el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<script setup name="DcsProfileReview" lang="ts">
import { ElMessage } from 'element-plus';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';
import {
  confirmReview,
  dismissReview,
  getEngineConfig,
  pageReviews,
  scanReviews,
  updateEngineConfig,
  type ProfileEngineConfig,
  type ProfileReview
} from '@/api/dcs/core/profile';

const router = useRouter();

const source = ref('dcs');
const rows = ref<ProfileReview[]>([]);
const total = ref(0);
const loading = ref(false);
const scanning = ref(false);
const counts = reactive({ pending: 0, confirmed: 0, dismissed: 0, highPending: 0 });
const reviewMetrics = computed(() => [
  { label: '待复核', value: counts.pending },
  { label: '已确认', value: counts.confirmed },
  { label: '已驳回', value: counts.dismissed },
  { label: '待复核·高风险', value: counts.highPending }
]);
const query = reactive({ pageNum: 1, pageSize: 20, status: 'PENDING', riskLevel: '', keyword: '' });

const formatScore = (value?: number | string | null) => {
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  return Number.isFinite(n) ? n.toFixed(2) : String(value);
};
const scoreClass = (value: number) => (value >= 60 ? 'score-mid' : 'score-low');
const statusTagType = (status?: string) => {
  if (status === 'PENDING') return 'warning' as const;
  if (status === 'CONFIRMED') return 'success' as const;
  if (status === 'DISMISSED') return 'info' as const;
  return 'info' as const;
};
const statusLabel = (status?: string) => {
  if (status === 'PENDING') return '待复核';
  if (status === 'CONFIRMED') return '已确认';
  if (status === 'DISMISSED') return '已驳回';
  if (status === 'SUPERSEDED') return '自动销案';
  return status || '—';
};
const riskTagType = (risk?: string) => {
  if (risk === 'HIGH') return 'danger' as const;
  if (risk === 'MEDIUM') return 'warning' as const;
  return 'info' as const;
};
const riskLabel = (risk?: string) => {
  if (risk === 'HIGH') return '高';
  if (risk === 'MEDIUM') return '中';
  if (risk === 'LOW') return '低';
  return risk || '—';
};

const loadCounts = async () => {
  const [pending, confirmed, dismissed, highPending] = await Promise.all([
    pageReviews({ source: source.value, status: 'PENDING', pageNum: 1, pageSize: 1 }),
    pageReviews({ source: source.value, status: 'CONFIRMED', pageNum: 1, pageSize: 1 }),
    pageReviews({ source: source.value, status: 'DISMISSED', pageNum: 1, pageSize: 1 }),
    pageReviews({ source: source.value, status: 'PENDING', riskLevel: 'HIGH', pageNum: 1, pageSize: 1 })
  ]);
  counts.pending = pending.total;
  counts.confirmed = confirmed.total;
  counts.dismissed = dismissed.total;
  counts.highPending = highPending.total;
};

const loadList = async () => {
  loading.value = true;
  try {
    const res = await pageReviews({
      source: source.value,
      status: query.status || undefined,
      riskLevel: query.riskLevel || undefined,
      keyword: query.keyword || undefined,
      pageNum: query.pageNum,
      pageSize: query.pageSize
    });
    rows.value = res.rows;
    total.value = res.total;
  } finally {
    loading.value = false;
  }
};
const search = () => {
  query.pageNum = 1;
  loadList();
};
const resetQuery = () => {
  query.status = 'PENDING';
  query.riskLevel = '';
  query.keyword = '';
  query.pageNum = 1;
  loadList();
};

const scan = async () => {
  scanning.value = true;
  try {
    const result = await scanReviews(source.value);
    ElMessage.success(`扫描 ${result.scanned} 个主体，新入队 ${result.created} 个，当前待复核 ${result.pending} 个`);
    await loadAll();
  } finally {
    scanning.value = false;
  }
};

// ---------------------------------------------------------------- 引擎配置
const configLoading = ref(false);
const configSaving = ref(false);
const config = reactive<Partial<ProfileEngineConfig>>({
  autoRecalc: '1',
  reviewEnabled: '1',
  reviewThreshold: 60,
  debounceMs: 2000,
  reviewSlaDays: 7
});

const loadConfig = async () => {
  configLoading.value = true;
  try {
    const res = await getEngineConfig(source.value);
    Object.assign(config, {
      autoRecalc: res.autoRecalc,
      reviewEnabled: res.reviewEnabled,
      reviewThreshold: Number(res.reviewThreshold),
      debounceMs: res.debounceMs,
      reviewSlaDays: res.reviewSlaDays ?? 7
    });
  } finally {
    configLoading.value = false;
  }
};
const saveConfig = async () => {
  configSaving.value = true;
  try {
    await updateEngineConfig({
      source: source.value,
      autoRecalc: config.autoRecalc,
      reviewEnabled: config.reviewEnabled,
      reviewThreshold: config.reviewThreshold,
      debounceMs: config.debounceMs,
      reviewSlaDays: config.reviewSlaDays
    });
    ElMessage.success('策略已保存');
  } finally {
    configSaving.value = false;
  }
};

// ---------------------------------------------------------------- 处理
const actionVisible = ref(false);
const actionSaving = ref(false);
const actionMode = ref<'confirm' | 'dismiss'>('confirm');
const activeReview = ref<ProfileReview | null>(null);
const actionForm = reactive({ note: '', riskLevel: 'HIGH' });

const openAction = (row: ProfileReview, mode: 'confirm' | 'dismiss') => {
  activeReview.value = row;
  actionMode.value = mode;
  actionForm.note = '';
  actionForm.riskLevel = row.riskLevel || 'HIGH';
  actionVisible.value = true;
};
const submitAction = async () => {
  if (!activeReview.value) return;
  actionSaving.value = true;
  try {
    const payload = { note: actionForm.note, riskLevel: actionForm.riskLevel };
    if (actionMode.value === 'confirm') {
      await confirmReview(activeReview.value.reviewId, payload);
      ElMessage.success('已确认低分');
    } else {
      await dismissReview(activeReview.value.reviewId, payload);
      ElMessage.success('已驳回');
    }
    actionVisible.value = false;
    await loadAll();
  } finally {
    actionSaving.value = false;
  }
};

const detailVisible = ref(false);
const openDetail = (row: ProfileReview) => {
  activeReview.value = row;
  detailVisible.value = true;
};
const goProfile = (row: ProfileReview) =>
  router.push(`/insight/profile-detail/${source.value}/${row.subjectId}?subjectType=${encodeURIComponent(row.subjectType || 'user')}`);

const loadAll = async () => {
  await Promise.all([loadConfig(), loadList(), loadCounts()]);
};
const onSourceChange = () => {
  query.pageNum = 1;
  loadAll();
};

onMounted(loadAll);
</script>

<style scoped>
.review-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.review-page :deep(.dcs-ops-header) {
  margin-bottom: 4px;
}
.review-page :deep(.dcs-ops-header__actions) {
  flex-wrap: wrap;
}
@media (min-width: 993px) {
  .source-select {
    width: 150px;
  }
}
/* el-table 走 CSS 变量：表头无底带、行线走令牌、正文灰阶（不重写组件） */
.review-page :deep(.el-table) {
  --el-table-border-color: var(--app-rule);
  --el-table-header-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: var(--app-raised);
}
/* 状态药丸：统一浅底深字，形状/字号与 .ui-pill 同口径 */
.review-page :deep(.el-tag) {
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
.review-page :deep(.el-tag--success) {
  --el-tag-bg-color: var(--dcs-pill-pos-bg);
  --el-tag-text-color: var(--dcs-pill-pos-ink);
}
.review-page :deep(.el-tag--warning) {
  --el-tag-bg-color: var(--dcs-pill-warn-bg);
  --el-tag-text-color: var(--dcs-pill-warn-ink);
}
.review-page :deep(.el-tag--danger) {
  --el-tag-bg-color: var(--dcs-pill-neg-bg);
  --el-tag-text-color: var(--dcs-pill-neg-ink);
}
.config-card :deep(.el-card__body) {
  padding-top: 12px;
}
.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-weight: 600;
}
.config-row {
  display: flex;
  align-items: center;
  gap: 22px;
  flex-wrap: wrap;
}
.config-item {
  display: flex;
  align-items: center;
  gap: 8px;
}
.config-label {
  font-size: 13px;
  font-weight: 600;
}
.table-card :deep(.el-card__body) {
  padding-top: 16px;
  min-width: 0;
}
.filter-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-bottom: 12px;
  min-width: 0;
}
.subject-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
  line-height: 1.35;
}
.subject-name {
  font-weight: 600;
  line-height: 1.4;
}
.subject-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.35;
}
.score-mid {
  color: var(--el-color-warning);
}
.score-low {
  color: var(--el-color-danger);
}
.muted {
  color: var(--el-text-color-secondary);
}
.small {
  font-size: 12px;
}
.action-summary {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px 18px;
  padding: 12px 14px;
  margin-bottom: 14px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-lighter);
}
.action-summary > div {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 13px;
}
.action-summary > div:last-child {
  grid-column: 1 / -1;
}
.detail-actions {
  margin-top: 16px;
}
</style>
