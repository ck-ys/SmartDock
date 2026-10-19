<template>
  <div class="app-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>审核队列（待我审核的提交）</span>
          <el-button icon="Refresh" @click="load">刷新</el-button>
        </div>
      </template>
      <el-table v-loading="loading" :data="rows">
        <el-table-column prop="taskId" label="任务ID" min-width="170" />
        <el-table-column prop="submitterUserId" label="提交人" width="110" />
        <el-table-column prop="version" label="版本" width="70" />
        <el-table-column prop="submittedAt" label="提交时间" width="170" />
        <el-table-column label="数据" min-width="200">
          <template #default="{ row }">{{ dataPreview(row.data) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button v-hasPermi="['dcs:audit:audit']" link type="success" @click="decide(row, 'PASS')">通过</el-button>
            <el-button v-hasPermi="['dcs:audit:audit']" link type="danger" @click="decide(row, 'REJECT')">退回</el-button>
            <el-button v-hasPermi="['dcs:submission:submit']" link @click="showTrail(row)">轨迹</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-show="total > 0" v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="load" />
    </el-card>

    <el-dialog v-model="rejectDialog.visible" title="退回提交" width="480px">
      <el-input v-model="rejectDialog.comment" type="textarea" :rows="3" placeholder="退回理由（将通知提交人）" maxlength="500" />
      <template #footer>
        <el-button @click="rejectDialog.visible = false">取消</el-button>
        <el-button type="danger" :loading="deciding" @click="confirmReject">确认退回</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="trailDialog.visible" title="审核轨迹" width="640px">
      <el-timeline>
        <el-timeline-item v-for="t in trailDialog.rows" :key="t.id" :timestamp="t.createTime"
          :type="t.decision === 'PASS' ? 'success' : 'danger'">
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
      .join('；').slice(0, 100);
  }
  return String(d).slice(0, 100);
};
const safeParse = (s: string): unknown => { try { return JSON.parse(s); } catch { return null; } };

const load = async () => {
  loading.value = true;
  try {
    const res = await pendingAudits(query);
    rows.value = res.rows;
    total.value = res.total;
  } finally { loading.value = false; }
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
  } finally { deciding.value = false; }
};

const confirmReject = async () => {
  if (!rejectDialog.row) return;
  if (!rejectDialog.comment.trim()) { proxy.$modal.msgError('请填写退回理由'); return; }
  deciding.value = true;
  try {
    await decideAudit(rejectDialog.row.id, 'REJECT', rejectDialog.comment.trim());
    proxy.$modal.msgSuccess('已退回');
    rejectDialog.visible = false;
    load();
  } finally { deciding.value = false; }
};

const showTrail = async (row: DcsSubmissionRow) => {
  trailDialog.rows = await auditTrail(row.id);
  trailDialog.visible = true;
};

onMounted(load);
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>
