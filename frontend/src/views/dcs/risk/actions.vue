<template>
  <div class="dcs-ops-page risk-actions">
    <DcsOperationsPageHeader
      title="自动处置"
      subtitle="L3/L4 风险触发的可逆处置：增强验证 30 分钟、临时冻结 60 分钟，到期自动恢复；人工解除与复核必须填写理由留痕。"
      eyebrow="风控与审计"
    >
      <template #actions>
        <el-button :icon="Refresh" @click="load">刷新</el-button>
      </template>
    </DcsOperationsPageHeader>

    <el-card shadow="never" class="risk-list-card">
      <el-form :inline="true" :model="query" class="ui-toolbar filter-form">
        <el-form-item label="处置状态">
          <el-select v-model="query.status" clearable placeholder="全部状态" class="filter-w-140">
            <el-option label="生效中" value="ACTIVE" />
            <el-option label="已到期" value="EXPIRED" />
            <el-option label="已解除" value="RELEASED" />
          </el-select>
        </el-form-item>
        <el-form-item label="处置类型">
          <el-select v-model="query.actionType" clearable placeholder="全部类型" class="filter-w-160">
            <el-option label="增强验证" value="ENHANCED_VERIFICATION" />
            <el-option label="临时冻结" value="TEMP_FREEZE" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <template #label>用户ID<span class="filter-hint">(精确)</span></template>
          <el-input v-model="query.userId" clearable placeholder="输入完整ID" class="filter-w-160" @keyup.enter="onSearch" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="onSearch">查询</el-button>
          <el-button :icon="RefreshLeft" @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="rows.some((row) => row.status === 'ACTIVE' && row.enforcementEnabled === false)"
        class="risk-inline-alert"
        title="L3/L4 灰度执行当前已旁路：历史处置仍保留用于审计，但不会实际限制用户操作。"
        type="info"
        :closable="false"
        show-icon
      />

      <div v-if="selectedRows.length" class="batch-bar">
        <span>已选 {{ selectedRows.length }} 条生效中的处置</span>
        <div class="batch-actions">
          <el-button v-hasPermi="['dcs:risk:action:release']" type="danger" plain @click="openBatch('batch-release')"> 批量解除 </el-button>
          <el-button v-hasPermi="['dcs:risk:review']" plain @click="openBatch('batch-review')"> 批量复核 </el-button>
        </div>
      </div>

      <div class="dcs-ops-table-wrap">
        <el-table v-loading="loading" :data="rows" class="ui-table risk-table" @selection-change="onSelectionChange">
          <el-table-column type="selection" width="42" :selectable="(row: RiskActionVO) => row.status === 'ACTIVE'" />
          <el-table-column label="用户" min-width="160">
            <template #default="{ row }">
              <div class="user-cell">
                <b>{{ row.nickName || row.userName || `用户 ${row.userId}` }}</b>
                <span
                  >ID {{ row.userId }}<template v-if="row.ipMasked"> · {{ row.ipMasked }}</template></span
                >
              </div>
            </template>
          </el-table-column>
          <el-table-column label="风险" width="110" align="center">
            <template #default="{ row }">
              <span class="ui-pill" :class="row.riskLevel >= 4 ? 'ui-pill--neg' : 'ui-pill--warn'"> L{{ row.riskLevel }} · {{ row.riskScore }} </span>
            </template>
          </el-table-column>
          <el-table-column label="自动处置" min-width="180">
            <template #default="{ row }">
              <div class="action-cell">
                <b>{{ ACTION_LABELS[row.actionType] || row.actionType }}</b>
                <span>{{ ACTION_DESCRIPTIONS[row.actionType] }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="120" align="center">
            <template #default="{ row }">
              <span class="ui-pill" :class="statusPillClass(row.status, row.enforcementEnabled)">{{ actionStatusLabel(row) }}</span>
              <div v-if="row.status === 'ACTIVE' && row.enforcementEnabled !== false" class="remaining">
                剩余 {{ formatRemaining(row.expireTime, now) }}
              </div>
              <div v-else-if="row.status === 'ACTIVE'" class="remaining">当前不执行</div>
            </template>
          </el-table-column>
          <el-table-column label="人工复核" width="120" align="center">
            <template #default="{ row }">
              <span class="ui-pill" :class="reviewPillClass(row.reviewStatus)">{{ REVIEW_STATUS_LABELS[row.reviewStatus] || row.reviewStatus }}</span>
            </template>
          </el-table-column>
          <el-table-column label="开始时间" width="155">
            <template #default="{ row }">{{ formatDate(row.startTime) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="175">
            <template #default="{ row }">
              <el-button link @click="openDetail(row)">详情</el-button>
              <el-button v-if="row.reviewStatus === 'PENDING'" v-hasPermi="['dcs:risk:review']" link @click="openReview(row)">复核</el-button>
              <el-button v-if="row.status === 'ACTIVE'" v-hasPermi="['dcs:risk:action:release']" link type="danger" @click="openRelease(row)"
                >解除</el-button
              >
            </template>
          </el-table-column>
          <template #empty>
            <div class="ui-empty">
              <p class="ui-empty__title">当前筛选条件下没有自动处置记录</p>
              <p class="ui-empty__hint">L3/L4 风险触发后，处置记录会出现在这里</p>
            </div>
          </template>
        </el-table>
      </div>
      <div class="risk-list-pagination">
        <pagination v-show="total > 0" :total="total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="load" />
      </div>
    </el-card>

    <el-drawer v-model="detailVisible" title="处置详情" size="min(520px, calc(100vw - 16px))">
      <template v-if="selected">
        <el-alert
          :title="ACTION_LABELS[selected.actionType]"
          :description="ACTION_DESCRIPTIONS[selected.actionType]"
          type="warning"
          :closable="false"
          show-icon
        />
        <el-descriptions :column="1" border class="mt-4">
          <el-descriptions-item label="处置 ID">{{ selected.actionId }}</el-descriptions-item>
          <el-descriptions-item label="用户">{{ selected.nickName || selected.userName || '-' }}（{{ selected.userId }}）</el-descriptions-item>
          <el-descriptions-item label="风险快照">L{{ selected.riskLevel }} / {{ selected.riskScore }} 分</el-descriptions-item>
          <el-descriptions-item label="来源轨道">{{ selected.sourceTrack || '-' }}</el-descriptions-item>
          <el-descriptions-item label="触发原因">{{ selected.triggerReason }}</el-descriptions-item>
          <el-descriptions-item label="生效区间">{{ formatDate(selected.startTime) }} — {{ formatDate(selected.expireTime) }}</el-descriptions-item>
          <el-descriptions-item label="复核结论">{{ REVIEW_STATUS_LABELS[selected.reviewStatus] || selected.reviewStatus }}</el-descriptions-item>
          <el-descriptions-item v-if="selected.reviewReason" label="复核理由">{{ selected.reviewReason }}</el-descriptions-item>
          <el-descriptions-item v-if="selected.releaseReason" label="解除理由">{{ selected.releaseReason }}</el-descriptions-item>
        </el-descriptions>
      </template>
    </el-drawer>

    <el-dialog v-model="reviewDialog.visible" :title="dialogTitle" width="min(520px, calc(100vw - 32px))" append-to-body>
      <el-alert
        v-if="reviewDialog.mode === 'release' || reviewDialog.mode === 'batch-release'"
        title="解除后立即恢复相关操作，请确认已有充分依据。"
        type="warning"
        :closable="false"
        show-icon
        class="mb-4"
      />
      <el-form label-position="top">
        <el-form-item v-if="reviewDialog.mode === 'review' || reviewDialog.mode === 'batch-review'" label="复核结论" required>
          <el-radio-group v-model="reviewDialog.conclusion">
            <el-radio-button value="CONFIRMED">确认风险</el-radio-button>
            <el-radio-button value="DISMISSED">排除风险并解除</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="操作理由" required>
          <el-input
            v-model="reviewDialog.reason"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            placeholder="填写判断依据、核验结果或解除原因"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="reviewDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="reviewDialog.saving" @click="submitDecision">确认提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { Refresh, RefreshLeft, Search } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';
import {
  batchReleaseRiskActions,
  batchReviewRiskActions,
  listRiskActions,
  releaseRiskAction,
  reviewRiskAction,
  type RiskActionQuery,
  type RiskActionVO
} from '@/api/dcs/risk';
import { parseDcsServerTime } from './riskDashboardPresentation';
import { parseTime } from '@/utils/ruoyi';
import { ACTION_DESCRIPTIONS, ACTION_LABELS, ACTION_STATUS_LABELS, REVIEW_STATUS_LABELS, formatRemaining } from './riskActionPresentation';

defineOptions({ name: 'DcsRiskActions' });

const query = reactive<RiskActionQuery>({ pageNum: 1, pageSize: 20, status: 'ACTIVE', actionType: '', userId: '' });
const rows = ref<RiskActionVO[]>([]);
const total = ref(0);
const loading = ref(false);
const now = ref(Date.now());
let timer: ReturnType<typeof setInterval> | undefined;
const selected = ref<RiskActionVO>();
const detailVisible = ref(false);
const selectedRows = ref<RiskActionVO[]>([]);
const reviewDialog = reactive({
  visible: false,
  saving: false,
  mode: 'review' as 'review' | 'release' | 'batch-review' | 'batch-release',
  actionId: '' as number | string,
  conclusion: 'CONFIRMED' as 'CONFIRMED' | 'DISMISSED',
  reason: ''
});

const DIALOG_TITLES: Record<string, string> = {
  review: '人工复核',
  release: '解除自动处置',
  'batch-review': `批量复核 · 已选 ${selectedRows.value.length} 条`,
  'batch-release': `批量解除 · 已选 ${selectedRows.value.length} 条`
};
const dialogTitle = computed(() => DIALOG_TITLES[reviewDialog.mode] || '操作');

const formatDate = (value?: string) => {
  // G13a(2026-09-16): 统一日期格式为连字符
  const d = parseDcsServerTime(value);
  return d ? parseTime(d, '{y}-{m}-{d} {h}:{i}') : '-';
};
const statusPillClass = (status: string, enforcementEnabled?: boolean) => {
  if (status === 'ACTIVE') return enforcementEnabled === false ? 'ui-pill--muted' : 'ui-pill--warn';
  return status === 'RELEASED' ? 'ui-pill--pos' : 'ui-pill--muted';
};
const actionStatusLabel = (row: RiskActionVO) =>
  row.status === 'ACTIVE' && row.enforcementEnabled === false ? '灰度旁路' : ACTION_STATUS_LABELS[row.status] || row.status;
const reviewPillClass = (status: string) => (status === 'PENDING' ? 'ui-pill--warn' : status === 'CONFIRMED' ? 'ui-pill--neg' : 'ui-pill--pos');

const load = async () => {
  loading.value = true;
  try {
    const response = await listRiskActions({ ...query, userId: query.userId || undefined });
    rows.value = response.rows || [];
    total.value = response.total || 0;
  } finally {
    loading.value = false;
  }
};

const onSearch = () => {
  query.pageNum = 1;
  load();
};
const onReset = () => {
  Object.assign(query, { pageNum: 1, pageSize: 20, status: 'ACTIVE', actionType: '', userId: '' });
  load();
};
const openDetail = (row: RiskActionVO) => {
  selected.value = row;
  detailVisible.value = true;
};
const openReview = (row: RiskActionVO) => {
  Object.assign(reviewDialog, { visible: true, mode: 'review', actionId: row.actionId, conclusion: 'CONFIRMED', reason: '' });
};
const openRelease = (row: RiskActionVO) => {
  Object.assign(reviewDialog, { visible: true, mode: 'release', actionId: row.actionId, conclusion: 'DISMISSED', reason: '' });
};
const onSelectionChange = (vals: RiskActionVO[]) => {
  selectedRows.value = vals;
};
const openBatch = (mode: 'batch-review' | 'batch-release') => {
  if (mode === 'batch-review' && !selectedRows.value.some((r) => r.reviewStatus === 'PENDING')) {
    return ElMessage.warning('所选处置中没有待复核记录');
  }
  Object.assign(reviewDialog, { visible: true, mode, conclusion: 'CONFIRMED', reason: '' });
};
const submitDecision = async () => {
  const reason = reviewDialog.reason.trim();
  if (!reason) return ElMessage.warning('请填写操作理由');
  reviewDialog.saving = true;
  try {
    if (reviewDialog.mode === 'release') {
      await releaseRiskAction(reviewDialog.actionId, { reason });
      ElMessage.success('处置已解除');
    } else if (reviewDialog.mode === 'review') {
      await reviewRiskAction(reviewDialog.actionId, { conclusion: reviewDialog.conclusion, reason });
      ElMessage.success(reviewDialog.conclusion === 'CONFIRMED' ? '已确认风险' : '已排除风险并解除处置');
    } else if (reviewDialog.mode === 'batch-release') {
      const ids = selectedRows.value.filter((r) => r.status === 'ACTIVE').map((r) => r.actionId);
      const r: any = await batchReleaseRiskActions(ids, reason);
      // 聚合结果文案在 msg 字段(R.ok(String) 重载语义)
      ElMessage.success(r?.msg || '批量解除完成');
    } else {
      const ids = selectedRows.value.filter((r) => r.reviewStatus === 'PENDING').map((r) => r.actionId);
      const r: any = await batchReviewRiskActions(ids, reviewDialog.conclusion, reason);
      ElMessage.success(r?.msg || '批量复核完成');
    }
    reviewDialog.visible = false;
    load();
  } finally {
    reviewDialog.saving = false;
  }
};

onMounted(() => {
  load();
  timer = setInterval(() => (now.value = Date.now()), 1000);
});
onUnmounted(() => timer && clearInterval(timer));
</script>

<style scoped lang="scss">
.risk-list-card {
  min-width: 0;

  :deep(.el-card__body) {
    min-width: 0;
    padding: 0;
  }
}

/* 表格只覆盖 Element Plus 变量与行线，不重写组件内部结构 */
.risk-table {
  --el-table-border-color: var(--app-rule);
  --el-table-header-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-row-hover-bg-color: var(--app-raised);
}

.filter-form {
  :deep(.el-form-item) {
    margin-right: 0;
    margin-bottom: 0;
  }

  :deep(.el-form-item__label) {
    white-space: nowrap;
  }
}

.filter-hint {
  color: var(--app-faint);
  font-size: 12px;
}

.filter-w-140 {
  width: 140px;
}
.filter-w-160 {
  width: 160px;
}

.risk-inline-alert {
  margin: 12px 16px 0;
}

.batch-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  margin: 12px 16px 0;
  padding: 8px 12px;
  border: 1px solid var(--app-border);
  border-radius: var(--app-control-radius);
  background: var(--app-raised);
  color: var(--app-text);
  font-size: 13px;
}
.batch-actions {
  display: flex;
  gap: 8px;
}

.user-cell,
.action-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.user-cell span,
.action-cell span {
  color: var(--app-muted);
  font-size: 12px;
  line-height: 1.45;
}
.remaining {
  margin-top: 6px;
  color: var(--app-muted);
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.risk-list-pagination {
  padding: 4px 16px 8px;
}
</style>
