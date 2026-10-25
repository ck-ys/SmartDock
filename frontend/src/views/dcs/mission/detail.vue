<template>
  <div class="app-container">
    <dcs-operations-page-header :title="detail?.task?.title || '任务详情'" subtitle="查看任务说明与我的提交，退回后可重新填报" eyebrow="任务中心">
      <template #actions>
        <span v-if="detail?.task" class="ui-pill" :class="statusPill(detail.task.status)">{{ statusLabel(detail.task.status) }}</span>
        <el-button @click="back">返回</el-button>
      </template>
    </dcs-operations-page-header>

    <el-card v-loading="loading" shadow="never" class="mission-detail-card">
      <el-descriptions class="detail-desc" :column="2">
        <el-descriptions-item label="说明" :span="2">{{ detail?.task?.description || '—' }}</el-descriptions-item>
        <el-descriptions-item label="截止日期">{{ detail?.task?.deadline || '—' }}</el-descriptions-item>
        <el-descriptions-item label="发布时间">{{ detail?.task?.publishTime || '未发布' }}</el-descriptions-item>
        <el-descriptions-item label="目标成员" :span="2">{{ (detail?.task?.assigneeNames || []).join('、') || '—' }}</el-descriptions-item>
        <el-descriptions-item label="我的提交">
          <template v-if="detail?.mySubmission">
            v{{ detail.mySubmission.version }} ·
            <span class="ui-pill" :class="subPill(detail.mySubmission.status)">{{ subLabel(detail.mySubmission.status) }}</span>
          </template>
          <span v-else>未提交</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-divider content-position="left">我的提交历史</el-divider>
      <div class="dcs-ops-table-wrap">
        <el-table :data="history" class="ui-table detail-table" max-height="220">
          <el-table-column label="版本" width="80" align="right">
            <template #default="{ row }">
              <span class="ui-num">{{ row.version }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <span class="ui-pill" :class="subPill(row.status)">{{ subLabel(row.status) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="submittedAt" label="提交时间" width="170" />
          <el-table-column label="数据" min-width="200">
            <template #default="{ row }">{{ dataPreview(row.data) }}</template>
          </el-table-column>
          <template #empty>
            <div class="ui-empty">
              <p class="ui-empty__title">暂无提交记录</p>
              <p class="ui-empty__hint">提交后会在这里留下版本历史。</p>
            </div>
          </template>
        </el-table>
      </div>

      <template v-if="canSubmit">
        <el-divider content-position="left">{{ isResubmit ? '重新提交（新版本）' : '填报' }}</el-divider>
        <el-alert
          v-if="isResubmit && rejectComment"
          class="detail-alert"
          type="error"
          :closable="false"
          show-icon
          :title="`退回意见：${rejectComment}`"
        />
        <el-form class="detail-form" label-width="120px">
          <el-form-item v-for="f in detail?.fields || []" :key="f.fieldCode" :label="f.fieldName" :required="f.required === '1'">
            <el-input v-if="f.fieldType === 'text'" v-model="formData[f.fieldCode]" maxlength="500" />
            <el-input-number v-else-if="f.fieldType === 'number'" v-model="formData[f.fieldCode]" class="detail-number" />
            <el-date-picker v-else-if="f.fieldType === 'date'" v-model="formData[f.fieldCode]" type="date" value-format="YYYY-MM-DD" />
            <el-select v-else-if="f.fieldType === 'select'" v-model="formData[f.fieldCode]" class="detail-select" clearable>
              <el-option v-for="o in optionList(f)" :key="o" :label="o" :value="o" />
            </el-select>
            <el-select
              v-else-if="f.fieldType === 'multi_select'"
              v-model="formData[f.fieldCode]"
              class="detail-select detail-select--multi"
              multiple
              clearable
            >
              <el-option v-for="o in optionList(f)" :key="o" :label="o" :value="o" />
            </el-select>
            <el-alert
              v-else
              :title="`「${f.fieldName}」类型(${f.fieldType})当前版本暂不支持在线填报, 请联系发布人`"
              type="warning"
              :closable="false"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="submitting" @click="submit">{{ isResubmit ? '提交新版本' : '提交' }}</el-button>
          </el-form-item>
        </el-form>
      </template>
      <el-alert v-else-if="detail" :title="cannotSubmitReason" type="info" :closable="false" />
    </el-card>
  </div>
</template>

<script setup name="DcsMissionDetail" lang="ts">
import { getTask, type DcsTaskDetail, type DcsTaskFieldDef } from '@/api/dcs/core/task';
import { submitData, mySubmissions, auditTrail, type DcsSubmissionRow } from '@/api/dcs/core/submission';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';

const route = useRoute();
const router = useRouter();
const { proxy } = getCurrentInstance() as any;

const taskId = computed(() => String(route.params.id));
const loading = ref(false);
const detail = ref<DcsTaskDetail | null>(null);
const history = ref<DcsSubmissionRow[]>([]);
const formData = reactive<Record<string, any>>({});
const submitting = ref(false);
// 最近一次退回的审核意见(全审 P1-4: 助手/待办跳转承诺「意见见任务详情页」在此兑现)
const rejectComment = ref('');

const isResubmit = computed(() => detail.value?.mySubmission?.status === 'REJECTED');
const canSubmit = computed(() => {
  if (!detail.value) return false;
  if (detail.value.task.status !== 'COLLECTING') return false;
  const st = detail.value.mySubmission?.status;
  return !st || st === 'REJECTED';
});
const cannotSubmitReason = computed(() => {
  if (!detail.value) return '';
  if (detail.value.task.status !== 'COLLECTING') return '任务不在收集状态，不可提交';
  if (detail.value.mySubmission?.status === 'PENDING_AUDIT') return '已有待审核的提交，请等待审核';
  if (detail.value.mySubmission?.status === 'APPROVED') return '提交已通过，无需重复提交';
  return '当前不可提交';
});

const statusLabel = (s?: string) => ({ DRAFT: '草稿', COLLECTING: '收集中', CLOSED: '已关闭' })[s || ''] || s;
const STATUS_PILLS: Record<string, string> = { DRAFT: 'ui-pill--muted', COLLECTING: 'ui-pill--pos', CLOSED: 'ui-pill--muted' };
const statusPill = (s?: string) => STATUS_PILLS[s || ''] || 'ui-pill--muted';
const subLabel = (s?: string) => ({ PENDING_AUDIT: '待审核', APPROVED: '已通过', REJECTED: '已退回' })[s || ''] || s;
const SUB_PILLS: Record<string, string> = { PENDING_AUDIT: 'ui-pill--warn', APPROVED: 'ui-pill--pos', REJECTED: 'ui-pill--neg' };
const subPill = (s?: string) => SUB_PILLS[s || ''] || 'ui-pill--muted';
const optionList = (f: DcsTaskFieldDef) => (Array.isArray(f.options) ? f.options : []).map((s) => String(s).trim()).filter(Boolean);
// data 可能是对象也可能是 JSON 字符串——字符串按字符 entries 会散架, 先归一
const safeParse = (s: string): unknown => {
  try {
    return JSON.parse(s);
  } catch {
    return null;
  }
};
const dataPreview = (d?: unknown) => {
  if (!d) return '—';
  const obj = typeof d === 'string' ? safeParse(d) : d;
  if (obj && typeof obj === 'object') {
    return Object.entries(obj as Record<string, unknown>)
      .map(([k, v]) => `${k}=${Array.isArray(v) ? v.join('、') : String(v)}`)
      .join('；')
      .slice(0, 120);
  }
  return String(d).slice(0, 120);
};
const back = () => router.back();

const loadRejectComment = async () => {
  rejectComment.value = '';
  const sub = detail.value?.mySubmission;
  if (!sub || sub.status !== 'REJECTED' || !sub.id) return;
  try {
    const trail = await auditTrail(sub.id);
    const rejects = trail.filter((t) => t.decision === 'REJECT' && t.comment);
    if (rejects.length) {
      // trail 按时间倒序返回时首条即最新; 无序时兜底取 createTime 最大
      const latest = rejects.reduce((a, b) => ((b.createTime || '') > (a.createTime || '') ? b : a));
      rejectComment.value = latest.comment || '';
    }
  } catch {
    // 轨迹拉取失败不阻塞填报
  }
};

const load = async () => {
  loading.value = true;
  try {
    detail.value = await getTask(taskId.value);
    Object.keys(formData).forEach((k) => delete formData[k]);
    const his = await mySubmissions({ taskId: taskId.value, pageNum: 1, pageSize: 20 });
    history.value = his.rows || [];
    await loadRejectComment();
  } finally {
    loading.value = false;
  }
};

const submit = async () => {
  for (const f of detail.value?.fields || []) {
    const v = formData[f.fieldCode];
    if (f.required === '1' && (v === undefined || v === null || v === '' || (Array.isArray(v) && !v.length))) {
      proxy.$modal.msgError(`「${f.fieldName}」为必填`);
      return;
    }
  }
  await proxy.$modal.confirm(isResubmit.value ? '提交新版本？旧版本保留在历史' : '确认提交？');
  submitting.value = true;
  try {
    await submitData(taskId.value, { ...formData });
    proxy.$modal.msgSuccess('已提交，等待审核');
    load();
  } finally {
    submitting.value = false;
  }
};

onMounted(load);
// 同组件内路由参数变化(列表页跳另一个任务详情)必须重载, 否则停留在旧任务
watch(
  () => route.params.id,
  (nv, ov) => {
    if (nv && nv !== ov) load();
  }
);
</script>

<style scoped>
.mission-detail-card :deep(.el-card__body) {
  min-width: 0;
  padding: 16px;
}

/* 去边框后的说明区：标签用弱字，正文用主字 */
.detail-desc :deep(.el-descriptions__label) {
  color: var(--app-faint);
  font-weight: 400;
}

.detail-desc :deep(.el-descriptions__content) {
  color: var(--app-text);
}

.detail-desc :deep(.el-descriptions__cell) {
  padding-bottom: 10px;
}

.detail-table {
  --el-table-header-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-border-color: var(--app-rule);
  --el-table-row-hover-bg-color: var(--app-raised);
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
}

.detail-alert {
  max-width: 640px;
  margin-bottom: 12px;
}

.detail-form {
  max-width: 640px;
}

.detail-number {
  width: 200px;
}

.detail-select {
  width: 240px;
}

.detail-select--multi {
  width: 320px;
}

@media (max-width: 768px) {
  .detail-number,
  .detail-select,
  .detail-select--multi {
    width: 100%;
  }
}
</style>
