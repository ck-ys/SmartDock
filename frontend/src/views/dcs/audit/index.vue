<template>
  <div class="dcs-ops-page audit-page">
    <dcs-operations-page-header
      title="审核队列"
      subtitle="逐条通过或退回成员提交，退回必须填写理由"
      eyebrow="任务中心"
      :metrics="[{ label: '待我审核', value: total }]"
    >
      <template #actions>
        <el-button icon="Refresh" @click="load">刷新</el-button>
      </template>
    </dcs-operations-page-header>

    <el-card shadow="never" class="audit-card">
      <div class="dcs-ops-table-wrap">
        <el-table class="dcs-table-only ui-table audit-table" v-loading="loading" :data="rows">
          <el-table-column prop="taskId" label="任务ID" min-width="170" />
          <el-table-column prop="submitterUserId" label="提交人" width="110" />
          <el-table-column prop="version" label="版本" width="70" align="right" />
          <el-table-column prop="submittedAt" label="提交时间" width="170" />
          <el-table-column label="数据" min-width="200">
            <template #default="{ row }">{{ dataPreview(row.data) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button v-hasPermi="['dcs:audit:audit']" link @click="decide(row, 'PASS')">通过</el-button>
              <el-button v-hasPermi="['dcs:audit:audit']" link type="danger" @click="decide(row, 'REJECT')">退回</el-button>
              <el-button v-hasPermi="['dcs:submission:submit']" link @click="showTrail(row)">轨迹</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="ui-empty">
              <p class="ui-empty__title">暂无待审核提交</p>
              <p class="ui-empty__hint">成员提交后，待审记录会出现在这里</p>
            </div>
          </template>
        </el-table>
      </div>
      <!-- ≤992：表格改卡片 -->
      <div class="dcs-mobile-list">
        <el-empty v-if="rows.length === 0" description="暂无数据" :image-size="64" />
        <div v-for="row in rows" :key="row.id" class="dcs-mobile-card">
          <div class="dcs-card-head">
            <span class="dcs-card-title">任务 #{{ String(row.taskId).slice(-6) }}</span>
            <span class="dcs-card-badge"
              ><span class="ui-pill ui-pill--muted">第 v{{ row.version }} 版</span></span
            >
          </div>
          <div class="dcs-card-meta">
            <span>提交人 {{ row.submitterUserId }}</span>
            <span>{{ row.submittedAt }}</span>
          </div>
          <div class="dcs-card-field">
            <span class="dcs-card-field__value">{{ dataPreview(row.data) }}</span>
          </div>
          <div class="dcs-card-foot">
            <div class="dcs-card-actions">
              <el-button v-hasPermi="['dcs:audit:audit']" link @click="decide(row, 'PASS')">通过</el-button>
              <el-button v-hasPermi="['dcs:audit:audit']" link type="danger" @click="decide(row, 'REJECT')">退回</el-button>
              <el-button v-hasPermi="['dcs:submission:submit']" link @click="showTrail(row)">轨迹</el-button>
            </div>
          </div>
        </div>
      </div>

      <div class="audit-pagination">
        <pagination v-show="total > 0" v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="load" />
      </div>
    </el-card>

    <el-dialog v-model="rejectDialog.visible" title="退回提交" width="min(480px, calc(100vw - 32px))">
      <el-input v-model="rejectDialog.comment" type="textarea" :rows="3" placeholder="退回理由（将通知提交人）" maxlength="500" />
      <template #footer>
        <el-button @click="rejectDialog.visible = false">取消</el-button>
        <el-button type="danger" :loading="deciding" @click="confirmReject">确认退回</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="trailDialog.visible" title="审核轨迹" width="min(640px, calc(100vw - 32px))">
      <el-timeline>
        <el-timeline-item v-for="t in trailDialog.rows" :key="t.id" :timestamp="t.createTime" :type="t.decision === 'PASS' ? 'success' : 'danger'">
          {{ t.decision === 'PASS' ? '通过' : '退回' }} · 审核人 {{ t.auditorUserId }}
          <div v-if="t.comment" style="color: var(--el-text-color-secondary)">{{ t.comment }}</div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-if="!trailDialog.rows.length" description="暂无轨迹" />
    </el-dialog>
  </div>
</template>

<script setup name="DcsAudit" lang="ts">
import { pendingAudits, decideAudit, auditTrail, type DcsSubmissionRow, type DcsAuditTrailRow } from '@/api/dcs/core/submission';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';

const { proxy } = getCurrentInstance() as any;

const loading = ref(false);
const rows = ref<DcsSubmissionRow[]>([]);
const total = ref(0);
const query = reactive<any>({ pageNum: 1, pageSize: 20 });
const deciding = ref(false);

const rejectDialog = reactive({ visible: false, row: null as DcsSubmissionRow | null, comment: '' });
const trailDialog = reactive({ visible: false, rows: [] as DcsAuditTrailRow[] });

// data 可能是对象(反序列化后)也可能是 JSON 字符串——字符串按字符 entries 会散架, 先归一
const dataPreview = (d?: unknown) => {
  if (!d) return '—';
  const obj = typeof d === 'string' ? safeParse(d) : d;
  if (obj && typeof obj === 'object') {
    return Object.entries(obj as Record<string, unknown>)
      .map(([k, v]) => `${k}=${Array.isArray(v) ? v.join('、') : String(v)}`)
      .join('；')
      .slice(0, 100);
  }
  return String(d).slice(0, 100);
};
const safeParse = (s: string): unknown => {
  try {
    return JSON.parse(s);
  } catch {
    return null;
  }
};

const load = async () => {
  loading.value = true;
  try {
    const res = await pendingAudits(query);
    rows.value = res.rows;
    total.value = res.total;
  } finally {
    loading.value = false;
  }
};

const decide = async (row: DcsSubmissionRow, decision: 'PASS' | 'REJECT') => {
  if (decision === 'REJECT') {
    rejectDialog.row = row;
    rejectDialog.comment = '';
    rejectDialog.visible = true;
    return;
  }
  await proxy.$modal.confirm('确认通过该提交？');
  deciding.value = true;
  try {
    await decideAudit(row.id, 'PASS');
    proxy.$modal.msgSuccess('已通过');
    load();
  } finally {
    deciding.value = false;
  }
};

const confirmReject = async () => {
  if (!rejectDialog.row) return;
  if (!rejectDialog.comment.trim()) {
    proxy.$modal.msgError('请填写退回理由');
    return;
  }
  deciding.value = true;
  try {
    await decideAudit(rejectDialog.row.id, 'REJECT', rejectDialog.comment.trim());
    proxy.$modal.msgSuccess('已退回');
    rejectDialog.visible = false;
    load();
  } finally {
    deciding.value = false;
  }
};

const showTrail = async (row: DcsSubmissionRow) => {
  trailDialog.rows = await auditTrail(row.id);
  trailDialog.visible = true;
};

onMounted(load);
</script>

<style scoped lang="scss">
.audit-card {
  min-width: 0;

  :deep(.el-card__body) {
    min-width: 0;
    padding: 0;
  }
}

/* 表格只覆盖 Element Plus 变量与行线，不重写组件内部结构 */
.audit-table {
  --el-table-border-color: var(--app-rule);
  --el-table-header-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-row-hover-bg-color: var(--app-raised);
}

.audit-pagination {
  padding: 4px 16px 8px;
}

/* 卡片档没有卡片自身外边距，由容器补 12px，避免贴住卡片边 */
@media (max-width: 992px) {
  .dcs-mobile-list {
    padding: 12px;
  }
}
</style>
