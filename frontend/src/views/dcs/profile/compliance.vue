<template>
  <div class="app-container compliance-page">
    <dcs-operations-page-header
      eyebrow="画像引擎"
      title="合规中心"
      subtitle="敏感字段打码规则、主体级删除、导出审计——三类合规动作一页管齐，全部留痕可查。"
    >
      <template #actions>
        <el-button :loading="exportLoading" @click="loadExportLogs">刷新导出审计</el-button>
      </template>
    </dcs-operations-page-header>

    <el-row :gutter="12">
      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="block-card">
          <template #header>
            <div class="panel-head">
              <span>PII 字段清单</span>
              <span class="muted small">命中键名的值在查询出参里打码；持有「看联系方式」权限者见原文</span>
            </div>
          </template>
          <div class="row-between" style="margin-bottom: 10px">
            <span class="muted small">规则 60 秒内全站生效</span>
            <el-button v-hasPermi="['profile:pii:edit']" type="primary" @click="openPiiEdit(null)">新增字段</el-button>
          </div>
          <el-table v-loading="piiLoading" class="ui-table" :data="piiFields">
            <template #empty>
              <div class="ui-empty">
                <p class="ui-empty__title">还没有 PII 字段规则</p>
                <p class="ui-empty__hint">新增字段后，命中键名的值会在查询出参里打码</p>
              </div>
            </template>
            <el-table-column label="键名" prop="fieldKey" min-width="110" />
            <el-table-column label="规则" width="100">
              <template #default="{ row }">
                <el-tag size="small" effect="plain">{{ row.maskRule }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="说明" prop="remark" min-width="120" show-overflow-tooltip />
            <el-table-column label="启用" width="70" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.enabled === '1' ? 'success' : 'info'" effect="light">
                  {{ row.enabled === '1' ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column v-if="hasPiiEdit" label="操作" width="130" align="center">
              <template #default="{ row }">
                <el-button v-hasPermi="['profile:pii:edit']" link type="primary" size="small" @click="openPiiEdit(row)">编辑</el-button>
                <el-button v-hasPermi="['profile:pii:edit']" link type="danger" size="small" @click="removePii(row)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="12">
        <el-card shadow="never" class="block-card">
          <template #header>
            <div class="panel-head">
              <span>主体级删除</span>
              <span class="muted small">预览波及面 → 回显主体 ID 确认 → 事务清理并重算收敛</span>
            </div>
          </template>
          <el-form inline label-width="70px" class="del-form">
            <el-form-item label="业务源">
              <el-select v-model="delForm.source" style="width: 130px">
                <el-option v-for="s in sourceOpts" :key="s.value" :label="s.label" :value="s.value" />
              </el-select>
            </el-form-item>
            <el-form-item label="主体类型">
              <el-select v-model="delForm.subjectType" style="width: 110px">
                <el-option label="用户" value="user" />
                <el-option label="访客" value="visitor" />
              </el-select>
            </el-form-item>
            <el-form-item label="主体 ID">
              <el-input v-model="delForm.subjectId" placeholder="如 user-42" style="width: 180px" clearable />
            </el-form-item>
            <el-form-item>
              <el-button :loading="previewing" @click="doPreview">预览波及面</el-button>
            </el-form-item>
          </el-form>

          <el-alert
            v-if="preview && !preview.deletable"
            type="warning"
            :closable="false"
            show-icon
            :title="preview.warning || '该主体无数据'"
            style="margin-bottom: 10px"
          />
          <template v-if="preview && preview.deletable">
            <div class="impact-grid">
              <div class="impact">
                <span>事件</span><strong>{{ preview.events }}</strong>
              </div>
              <div class="impact">
                <span>归档</span><strong>{{ preview.archives }}</strong>
              </div>
              <div class="impact">
                <span>会话</span><strong>{{ preview.sessions }}</strong>
              </div>
              <div class="impact">
                <span>身份链接</span><strong>{{ preview.links }}</strong>
              </div>
              <div class="impact">
                <span>评分快照</span><strong>{{ preview.snapshots }}</strong>
              </div>
              <div class="impact">
                <span>分群成员</span><strong>{{ preview.members }}</strong>
              </div>
              <div class="impact">
                <span>复核项</span><strong>{{ preview.reviews }}</strong>
              </div>
              <div class="impact">
                <span>重算天数</span><strong>{{ preview.rebuildDays }}</strong>
              </div>
            </div>
            <div v-if="preview.visitorKeys && preview.visitorKeys.length > 1" class="muted small" style="margin: 6px 0 10px">
              含 {{ preview.visitorKeys.length - 1 }} 个匿名标识的匿名期数据（共享浏览器会一并波及，请核对）
            </div>
            <div class="row-between">
              <span class="muted small">删除不可撤销，将整批清理并在窗口内重算派生数据</span>
              <el-button v-hasPermi="['profile:deletion:edit']" type="danger" @click="openConfirm">确认删除…</el-button>
            </div>
          </template>

          <el-divider content-position="left">最近删除审计</el-divider>
          <el-table v-loading="delLogLoading" class="ui-table" :data="deletionLogs" max-height="260">
            <template #empty>
              <div class="ui-empty">
                <p class="ui-empty__title">还没有删除审计</p>
              </div>
            </template>
            <el-table-column label="时间" width="140">
              <template #default="{ row }">{{ fmtTime(row.executedAt) }}</template>
            </el-table-column>
            <el-table-column label="主体" min-width="100" show-overflow-tooltip>
              <template #default="{ row }">{{ row.subjectId }}（{{ row.subjectType }}）</template>
            </el-table-column>
            <el-table-column label="结果" width="82" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'EXECUTED' ? 'success' : 'danger'" effect="light">{{ row.status }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="事件/会话" width="92" align="right">
              <template #default="{ row }">{{ row.eventsDeleted }}/{{ row.sessionsDeleted }}</template>
            </el-table-column>
            <el-table-column label="错误" prop="errorMsg" min-width="90" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="block-card">
      <template #header>
        <div class="panel-head">
          <span>导出审计</span>
          <span class="muted small">每次分群成员 CSV 导出留一行（行数/是否明文/文件指纹），明文导出会通知管理员</span>
        </div>
      </template>
      <el-table v-loading="exportLoading" class="ui-table" :data="exportLogs">
        <template #empty>
          <div class="ui-empty">
            <p class="ui-empty__title">还没有导出记录</p>
            <p class="ui-empty__hint">分群成员 CSV 导出会在这里留痕</p>
          </div>
        </template>
        <el-table-column label="时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.exportAt) }}</template>
        </el-table-column>
        <el-table-column label="分群" min-width="160">
          <template #default="{ row }">{{ row.segmentName || row.segmentId }}</template>
        </el-table-column>
        <el-table-column label="行数" prop="rowCount" width="80" align="right" />
        <el-table-column label="口径" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.masked === '1' ? 'success' : 'danger'" effect="light">
              {{ row.masked === '1' ? '脱敏' : '明文' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="文件指纹" prop="fileHash" width="130" />
        <el-table-column label="导出人" prop="exportBy" width="90" align="center" />
      </el-table>
    </el-card>

    <el-dialog v-model="piiDialog" :title="editingPii ? '编辑 PII 字段' : '新增 PII 字段'" width="min(460px, 94%)">
      <el-form :model="piiForm" label-width="80px">
        <el-form-item label="键名" required>
          <el-input v-model="piiForm.fieldKey" :disabled="editingPii" placeholder="如 phone / search_term" maxlength="64" />
        </el-form-item>
        <el-form-item label="打码规则" required>
          <el-select v-model="piiForm.maskRule" style="width: 100%">
            <el-option v-for="o in maskRules" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="piiForm.remark" maxlength="200" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="piiForm.enabled" active-value="1" inactive-value="0" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="piiDialog = false">取消</el-button>
        <el-button type="primary" :loading="piiSaving" @click="savePii">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="confirmDialog" title="确认删除主体" width="min(440px, 94%)">
      <el-alert
        type="error"
        :closable="false"
        show-icon
        title="此操作不可撤销"
        description="将删除该主体的全部事件（含归档与匿名期）、会话、身份链接、评分快照、分群成员与复核项，并重算派生数据。"
        style="margin-bottom: 12px"
      />
      <p style="margin: 0 0 8px">
        请输入主体 ID <strong>{{ delForm.subjectId }}</strong> 以确认：
      </p>
      <el-input v-model="confirmText" placeholder="回显主体 ID" />
      <template #footer>
        <el-button @click="confirmDialog = false">取消</el-button>
        <el-button type="danger" :loading="executing" :disabled="confirmText !== delForm.subjectId" @click="doExecute">执行删除</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import {
  listPiiFields,
  savePiiField,
  deletePiiField,
  previewDeletion,
  executeDeletion,
  listDeletionLogs,
  listExportLogs
} from '@/api/dcs/core/profile';
import { checkPermi } from '@/utils/permission';
import { useSourceOptions } from '@/composables/useProfileSources';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';
const { list: sourceOpts, ensureSources } = useSourceOptions('subject');
ensureSources();

defineOptions({ name: 'ProfileCompliance' });

const piiFields = ref<any[]>([]);
const piiLoading = ref(false);
const piiDialog = ref(false);
const piiSaving = ref(false);
const editingPii = ref(false);
const piiForm = reactive<any>({ fieldKey: '', maskRule: 'MOBILE', remark: '', enabled: '1' });
const hasPiiEdit = computed(() => checkPermi(['profile:pii:edit']));
const maskRules = [
  { value: 'MOBILE', label: '手机号（留前3后4）' },
  { value: 'EMAIL', label: '邮箱（留首字符与域名）' },
  { value: 'IP', label: 'IP（末两段打码）' },
  { value: 'TERM', label: '整段打码' },
  { value: 'EDGE', label: '留首尾各一字符' },
  { value: 'FULL', label: '全打码' }
];

const delForm = reactive({ source: 'dcs', subjectType: 'user', subjectId: '' });
const previewing = ref(false);
const preview = ref<any>(null);
const confirmDialog = ref(false);
const confirmText = ref('');
const executing = ref(false);
const deletionLogs = ref<any[]>([]);
const delLogLoading = ref(false);

const exportLogs = ref<any[]>([]);
const exportLoading = ref(false);

const fmtTime = (v?: string) => (v ? String(v).replace('T', ' ').slice(0, 19) : '-');

const loadPii = async () => {
  piiLoading.value = true;
  try {
    piiFields.value = await listPiiFields();
  } finally {
    piiLoading.value = false;
  }
};

const loadDeletionLogs = async () => {
  delLogLoading.value = true;
  try {
    deletionLogs.value = await listDeletionLogs();
  } finally {
    delLogLoading.value = false;
  }
};

const loadExportLogs = async () => {
  exportLoading.value = true;
  try {
    exportLogs.value = await listExportLogs();
  } finally {
    exportLoading.value = false;
  }
};

const openPiiEdit = (row: any | null) => {
  editingPii.value = !!row;
  Object.assign(piiForm, row ?? { fieldKey: '', maskRule: 'MOBILE', remark: '', enabled: '1' });
  piiDialog.value = true;
};

const savePii = async () => {
  piiSaving.value = true;
  try {
    await savePiiField({ ...piiForm });
    ElMessage.success('已保存，规则最迟 60 秒内生效');
    piiDialog.value = false;
    await loadPii();
  } finally {
    piiSaving.value = false;
  }
};

const removePii = async (row: any) => {
  await ElMessageBox.confirm(`删除字段「${row.fieldKey}」后，该键名将不再打码。`, '删除 PII 字段', { type: 'warning' });
  await deletePiiField(row.fieldKey);
  ElMessage.success('已删除');
  await loadPii();
};

const doPreview = async () => {
  if (!delForm.subjectId.trim()) {
    ElMessage.warning('请先填写主体 ID');
    return;
  }
  previewing.value = true;
  preview.value = null;
  try {
    preview.value = await previewDeletion(delForm.source, delForm.subjectType, delForm.subjectId.trim());
  } finally {
    previewing.value = false;
  }
};

const openConfirm = () => {
  confirmText.value = '';
  confirmDialog.value = true;
};

const doExecute = async () => {
  executing.value = true;
  try {
    const out = await executeDeletion({ ...delForm, subjectId: delForm.subjectId.trim(), confirmText: confirmText.value });
    ElMessage.success(`删除完成：事件 ${out.events ?? 0} 条、会话 ${out.sessions ?? 0} 条，重算 ${out.daysRebuilt ?? 0} 天`);
    confirmDialog.value = false;
    preview.value = null;
    await loadDeletionLogs();
  } finally {
    executing.value = false;
  }
};

onMounted(() => {
  loadPii();
  loadDeletionLogs();
  loadExportLogs();
});
</script>

<style scoped>
.compliance-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.compliance-page :deep(.dcs-ops-header) {
  margin-bottom: 4px;
}
.compliance-page :deep(.dcs-ops-header__actions) {
  flex-wrap: wrap;
}
/* el-table 走 CSS 变量：表头无底带、行线走令牌、正文灰阶（不重写组件） */
.compliance-page :deep(.el-table) {
  --el-table-border-color: var(--app-rule);
  --el-table-header-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-row-hover-bg-color: var(--app-raised);
}
/* 状态药丸：统一浅底深字，形状/字号与 .ui-pill 同口径 */
.compliance-page :deep(.el-tag) {
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
.compliance-page :deep(.el-tag--success) {
  --el-tag-bg-color: var(--dcs-pill-pos-bg);
  --el-tag-text-color: var(--dcs-pill-pos-ink);
}
.compliance-page :deep(.el-tag--warning) {
  --el-tag-bg-color: var(--dcs-pill-warn-bg);
  --el-tag-text-color: var(--dcs-pill-warn-ink);
}
.compliance-page :deep(.el-tag--danger) {
  --el-tag-bg-color: var(--dcs-pill-neg-bg);
  --el-tag-text-color: var(--dcs-pill-neg-ink);
}
.compliance-page :deep(.el-card__body) {
  min-width: 0;
}
.row-between {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.block-card {
  margin-bottom: 0;
}
.panel-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.impact-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(96px, 1fr));
  gap: 8px;
  margin-bottom: 8px;
}
.impact {
  border: 1px solid var(--app-rule);
  border-radius: var(--app-control-radius);
  padding: 6px 8px;
  text-align: center;
}
.impact span {
  display: block;
  font-size: 12px;
  color: var(--app-faint);
}
.impact strong {
  font-size: 16px;
  color: var(--app-text);
  font-variant-numeric: tabular-nums;
}
.del-form :deep(.el-form-item) {
  margin-bottom: 8px;
}
@media (max-width: 1199px) {
  .compliance-page :deep(.el-col:first-child) .block-card {
    margin-bottom: 12px;
  }
}
</style>
