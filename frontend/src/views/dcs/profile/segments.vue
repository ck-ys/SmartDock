<template>
  <div class="app-container segment-page">
    <el-card shadow="never" class="hero-card">
      <div class="hero-head">
        <div class="hero-copy">
          <h3>标签分群</h3>
          <p>动态分群按画像快照实时求值，静态分群按名单导入；成员与运行记录都可追溯。</p>
        </div>
        <div class="hero-actions">
          <el-select v-model="source" style="width: 150px" @change="loadAll">
            <el-option label="dcs 数据采集" value="dcs" />
            <el-option label="risk 风控" value="risk" />
          </el-select>
          <el-button v-hasPermi="['profile:segment:edit']" type="primary" @click="openCreate">新建分群</el-button>
          <el-button :loading="loading" @click="loadAll">刷新</el-button>
        </div>
      </div>
      <div class="stat-grid">
        <div class="stat">
          <span class="stat-label">分群总数</span>
          <strong>{{ segments.length }}</strong>
        </div>
        <div class="stat">
          <span class="stat-label">动态分群</span>
          <strong>{{ dynamicCount }}</strong>
        </div>
        <div class="stat">
          <span class="stat-label">静态分群</span>
          <strong>{{ staticCount }}</strong>
        </div>
        <div class="stat">
          <span class="stat-label">成员人次</span>
          <strong>{{ segmentMemberTotal }}</strong>
        </div>
      </div>
    </el-card>

    <div v-loading="loading" class="segment-grid">
      <el-card v-for="segment in segments" :key="segment.segmentId" shadow="hover" class="segment-card">
        <div class="segment-top">
          <div>
            <div class="segment-name">{{ segment.segmentName }}</div>
            <div class="segment-code mono">{{ segment.segmentCode }}</div>
          </div>
          <div class="segment-tags">
            <el-tag :type="segment.segmentType === 'DYNAMIC' ? 'primary' : 'info'" effect="plain" size="small">
              {{ segment.segmentType === 'DYNAMIC' ? '动态' : '静态' }}
            </el-tag>
            <el-tag :type="segment.status === 'ENABLED' ? 'success' : 'info'" effect="plain" size="small">
              {{ segment.status === 'ENABLED' ? '启用' : '停用' }}
            </el-tag>
            <el-tag v-if="segment.builtin === '1'" type="warning" effect="plain" size="small">内置</el-tag>
          </div>
        </div>

        <p class="segment-desc">{{ segment.description || '暂无说明' }}</p>

        <div class="segment-metrics">
          <div>
            <span>当前成员</span>
            <strong>{{ segment.memberCount ?? 0 }}</strong>
          </div>
          <div>
            <span>最近评估</span>
            <strong class="metric-time">{{ segment.lastEvalTime || '从未' }}</strong>
          </div>
        </div>

        <div class="segment-rule">
          <span class="rule-label">规则</span>
          <span class="rule-text">{{ ruleSummary(segment) }}</span>
        </div>

        <div class="segment-actions">
          <el-button v-hasPermi="['profile:segment:edit']"
            v-if="segment.segmentType === 'DYNAMIC'"
            size="small"
            type="primary"
            :loading="evaluatingId === segment.segmentId"
            @click="evaluate(segment)"
          >
            评估
          </el-button>
          <el-button v-hasPermi="['profile:segment:edit']" v-else size="small" type="primary" @click="openImport(segment)">导入名单</el-button>
          <el-button size="small" @click="openMembers(segment)">成员</el-button>
          <el-button size="small" @click="openRuns(segment)">运行记录</el-button>
          <el-button v-hasPermi="['profile:segment:edit']" size="small" @click="openEdit(segment)">编辑</el-button>
          <el-button v-hasPermi="['profile:segment:edit']" v-if="segment.builtin !== '1'" size="small" type="danger" plain @click="remove(segment)">删除</el-button>
        </div>
      </el-card>
      <el-empty v-if="!loading && segments.length === 0" description="当前业务源还没有分群">
        <el-button v-hasPermi="['profile:segment:edit']" type="primary" @click="openCreate">新建第一个分群</el-button>
      </el-empty>
    </div>

    <!-- 新建/编辑 -->
    <el-dialog v-model="dialogVisible" :title="form.segmentId ? '编辑分群' : '新建分群'" width="780px" destroy-on-close>
      <el-form label-width="92px">
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="分群编码">
              <el-input v-model="form.segmentCode" :disabled="!!form.segmentId" maxlength="64" placeholder="如 high_value_active" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="分群名称">
              <el-input v-model="form.segmentName" maxlength="64" placeholder="如 高价值活跃" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="分群类型">
              <el-select v-model="form.segmentType" style="width: 100%" @change="onTypeChange">
                <el-option label="动态（规则求值）" value="DYNAMIC" />
                <el-option label="静态（名单导入）" value="STATIC" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="状态">
              <el-select v-model="form.status" style="width: 100%">
                <el-option label="启用" value="ENABLED" />
                <el-option label="停用" value="DISABLED" />
              </el-select>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" placeholder="这个分群用来做什么" />
        </el-form-item>

        <template v-if="form.segmentType === 'DYNAMIC'">
          <el-divider content-position="left">规则条件</el-divider>
          <div class="rule-toolbar">
            <span>条件之间</span>
            <el-select v-model="form.match" style="width: 120px">
              <el-option label="全部满足" value="ALL" />
              <el-option label="任一满足" value="ANY" />
            </el-select>
            <el-button size="small" @click="addCondition">添加条件</el-button>
            <span class="muted small">字段支持 totalScore / eventCount / lastEventDays / property.xxx / dimension.xxx</span>
          </div>
          <div v-for="(cond, index) in form.conditions" :key="index" class="condition-row">
            <el-select v-model="cond.field" filterable allow-create default-first-option placeholder="字段" style="width: 220px">
              <el-option v-for="field in fieldOptions" :key="field.value" :label="field.label" :value="field.value" />
            </el-select>
            <el-select v-model="cond.op" style="width: 130px">
              <el-option v-for="op in opOptions" :key="op.value" :label="op.label" :value="op.value" />
            </el-select>
            <el-input
              v-model="cond.value"
              :disabled="cond.op === 'EXISTS' || cond.op === 'NOT_EXISTS'"
              placeholder="值（IN/NOT_IN 用逗号分隔）"
              style="flex: 1"
            />
            <el-button link type="danger" @click="removeCondition(index)">移除</el-button>
          </div>
          <el-alert
            v-if="previewResult"
            class="preview-alert"
            type="success"
            :closable="false"
            show-icon
            :title="`预览命中 ${previewResult.matched} / ${previewResult.totalCandidates}（${formatScore(previewResult.rate)}%）：${previewResult.summary}`"
          />
        </template>
        <el-alert
          v-else
          class="preview-alert"
          type="info"
          :closable="false"
          show-icon
          title="静态分群保存后，在卡片上点「导入名单」粘贴主体 ID 即可。"
        />
      </el-form>
      <template #footer>
        <el-button v-if="form.segmentType === 'DYNAMIC'" :loading="previewing" @click="preview">预览命中</el-button>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button v-hasPermi="['profile:segment:edit']" type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>

    <!-- 静态名单导入 -->
    <el-dialog v-model="importVisible" title="导入分群名单" width="560px" destroy-on-close>
      <el-form label-width="88px">
        <el-form-item label="导入方式">
          <el-radio-group v-model="importForm.mode">
            <el-radio value="REPLACE">覆盖为本批成员</el-radio>
            <el-radio value="APPEND">在当前成员上追加</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="主体类型">
          <el-select v-model="importForm.subjectType" clearable placeholder="匹配全部类型" style="width: 100%">
            <el-option label="用户" value="user" />
            <el-option label="订单" value="order" />
            <el-option label="访客" value="visitor" />
          </el-select>
        </el-form-item>
        <el-form-item label="主体 ID">
          <el-input v-model="importForm.text" type="textarea" :rows="9" placeholder="每行一个，或用逗号分隔；不存在的 ID 会自动跳过" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button v-hasPermi="['profile:segment:edit']" type="primary" :loading="importing" @click="doImport">导入</el-button>
      </template>
    </el-dialog>

    <!-- 成员 -->
    <el-drawer v-model="memberVisible" :title="`成员 · ${activeSegment?.segmentName || ''}`" size="720px" destroy-on-close>
      <div class="drawer-toolbar">
        <el-input v-model="memberQuery.keyword" placeholder="主体 ID / 名称" clearable style="width: 220px" @keyup.enter="loadMembers" />
        <el-button type="primary" @click="loadMembers">查询</el-button>
        <span class="muted small">当前分群 {{ activeSegment?.memberCount ?? 0 }} 人</span>
      </div>
      <el-table v-loading="memberLoading" :data="members" stripe size="small">
        <el-table-column label="主体" min-width="180">
          <template #default="{ row }">
            <div class="subject-cell">
              <span class="subject-name">{{ row.displayName || row.subjectId }}</span>
              <span class="subject-meta">{{ row.subjectType }} · {{ row.subjectId }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="总分" width="90">
          <template #default="{ row }">{{ formatScore(row.totalScore) }}</template>
        </el-table-column>
        <el-table-column prop="eventCount" label="事件数" width="80" />
        <el-table-column label="命中原因" min-width="200">
          <template #default="{ row }"
            ><span class="muted">{{ row.reason || '—' }}</span></template
          >
        </el-table-column>
        <el-table-column label="最近事件" width="170">
          <template #default="{ row }">{{ row.lastEventTime || '—' }}</template>
        </el-table-column>
      </el-table>
      <pagination
        v-show="memberTotal > 0"
        v-model:page="memberQuery.pageNum"
        v-model:limit="memberQuery.pageSize"
        :total="memberTotal"
        @pagination="loadMembers"
      />
    </el-drawer>

    <!-- 运行记录 -->
    <el-drawer v-model="runVisible" :title="`运行记录 · ${activeSegment?.segmentName || ''}`" size="720px" destroy-on-close>
      <el-table v-loading="runLoading" :data="runs" stripe size="small">
        <el-table-column label="运行 ID" prop="runId" min-width="180" />
        <el-table-column label="触发" width="90">
          <template #default="{ row }">{{ triggerLabel(row.triggerType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'DONE' ? 'success' : row.status === 'FAILED' ? 'danger' : 'warning'" size="small">
              {{ row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="totalCandidates" label="候选数" width="90" />
        <el-table-column prop="memberCount" label="命中数" width="90" />
        <el-table-column label="完成时间" width="170">
          <template #default="{ row }">{{ row.finishedAt || row.startedAt || '—' }}</template>
        </el-table-column>
      </el-table>
      <el-empty v-if="!runLoading && runs.length === 0" description="还没有运行记录" />
    </el-drawer>
  </div>
</template>

<script setup name="DcsProfileSegments" lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  createSegment,
  deleteSegment,
  evaluateSegment,
  importSegmentMembers,
  listSegmentRuns,
  listSegments,
  pageSegmentMembers,
  previewSegment,
  updateSegment,
  type ProfileSegment,
  type ProfileSegmentMemberRow,
  type ProfileSegmentPreview,
  type ProfileSegmentRun
} from '@/api/dcs/core/profile';

const source = ref('dcs');
const segments = ref<ProfileSegment[]>([]);
const loading = ref(false);
const evaluatingId = ref('');

const dynamicCount = computed(() => segments.value.filter((s) => s.segmentType === 'DYNAMIC').length);
const staticCount = computed(() => segments.value.filter((s) => s.segmentType === 'STATIC').length);
const segmentMemberTotal = computed(() => segments.value.reduce((sum, s) => sum + Number(s.memberCount || 0), 0));

const formatScore = (value?: number | string | null) => {
  if (value === null || value === undefined || value === '') return '—';
  const n = Number(value);
  return Number.isFinite(n) ? n.toFixed(2) : String(value);
};

const parseRule = (segment: ProfileSegment): { match?: string; conditions?: { field: string; op: string; value?: any }[] } => {
  try {
    return segment.ruleConfig ? JSON.parse(segment.ruleConfig) : {};
  } catch {
    return {};
  }
};
const ruleSummary = (segment: ProfileSegment) => {
  const rule = parseRule(segment);
  const conditions = rule.conditions || [];
  if (!conditions.length) return '未配置条件';
  return conditions
    .map((c) => {
      const value = c.value === undefined || c.value === null || c.value === '' ? '' : ` ${String(c.value)}`;
      return `${c.field} ${c.op}${value}`;
    })
    .join(rule.match === 'ANY' ? ' 或 ' : ' 且 ');
};

const loadAll = async () => {
  loading.value = true;
  try {
    segments.value = await listSegments(source.value);
  } finally {
    loading.value = false;
  }
};

const evaluate = async (segment: ProfileSegment) => {
  evaluatingId.value = segment.segmentId;
  try {
    const run = await evaluateSegment(segment.segmentId);
    if (run.status === 'DONE') {
      ElMessage.success(`评估完成：命中 ${run.memberCount} / ${run.totalCandidates}`);
    } else {
      ElMessage.error(run.errorMsg || `评估失败：${run.status}`);
    }
    await loadAll();
  } finally {
    evaluatingId.value = '';
  }
};

const remove = async (segment: ProfileSegment) => {
  await ElMessageBox.confirm(`删除分群「${segment.segmentName}」？历史运行记录会保留。`, '删除分群', { type: 'warning' });
  await deleteSegment(segment.segmentId);
  ElMessage.success('分群已删除');
  await loadAll();
};

// ---------------------------------------------------------------- 新建/编辑
const dialogVisible = ref(false);
const saving = ref(false);
const previewing = ref(false);
const previewResult = ref<ProfileSegmentPreview | null>(null);
const fieldOptions = [
  { label: '总分', value: 'totalScore' },
  { label: '事件数', value: 'eventCount' },
  { label: '最近事件天数', value: 'lastEventDays' },
  { label: '主体类型', value: 'subjectType' },
  { label: '主体 ID', value: 'subjectId' },
  { label: '显示名称', value: 'displayName' },
  { label: '维度分：完成率', value: 'dimension.completion' },
  { label: '维度分：响应时效', value: 'dimension.response' },
  { label: '维度分：填报质量', value: 'dimension.quality' }
];
const opOptions = [
  { label: '等于', value: 'EQ' },
  { label: '不等于', value: 'NEQ' },
  { label: '大于', value: 'GT' },
  { label: '大于等于', value: 'GTE' },
  { label: '小于', value: 'LT' },
  { label: '小于等于', value: 'LTE' },
  { label: '属于', value: 'IN' },
  { label: '不属于', value: 'NOT_IN' },
  { label: '包含', value: 'CONTAINS' },
  { label: '存在', value: 'EXISTS' },
  { label: '不存在', value: 'NOT_EXISTS' }
];

interface ConditionForm {
  field: string;
  op: string;
  value: string;
}
const form = reactive({
  segmentId: '',
  segmentCode: '',
  segmentName: '',
  segmentType: 'DYNAMIC',
  status: 'ENABLED',
  description: '',
  match: 'ALL',
  conditions: [] as ConditionForm[],
  revision: 0
});

const resetForm = () => {
  Object.assign(form, {
    segmentId: '',
    segmentCode: '',
    segmentName: '',
    segmentType: 'DYNAMIC',
    status: 'ENABLED',
    description: '',
    match: 'ALL',
    conditions: [{ field: 'totalScore', op: 'GTE', value: '75' }],
    revision: 0
  });
  previewResult.value = null;
};

const openCreate = () => {
  resetForm();
  dialogVisible.value = true;
};

const openEdit = (segment: ProfileSegment) => {
  resetForm();
  const rule = parseRule(segment);
  Object.assign(form, {
    segmentId: segment.segmentId,
    segmentCode: segment.segmentCode,
    segmentName: segment.segmentName,
    segmentType: segment.segmentType,
    status: segment.status,
    description: segment.description || '',
    match: rule.match || 'ALL',
    conditions: (rule.conditions || []).map((c) => ({
      field: c.field,
      op: c.op,
      value: c.value === undefined || c.value === null ? '' : String(c.value)
    })),
    revision: segment.revision
  });
  if (form.conditions.length === 0) form.conditions = [{ field: 'totalScore', op: 'GTE', value: '75' }];
  dialogVisible.value = true;
};

const onTypeChange = () => {
  if (form.segmentType === 'DYNAMIC' && form.conditions.length === 0) {
    form.conditions = [{ field: 'totalScore', op: 'GTE', value: '75' }];
  }
};
const addCondition = () => {
  if (form.conditions.length >= 20) return ElMessage.warning('最多 20 个条件');
  form.conditions.push({ field: 'totalScore', op: 'GTE', value: '' });
};
const removeCondition = (index: number) => form.conditions.splice(index, 1);

const buildRule = () => ({
  match: form.match,
  conditions: form.conditions
    .filter((c) => c.field && c.op)
    .map((c) => {
      const item: Record<string, any> = { field: c.field.trim(), op: c.op };
      if (c.op !== 'EXISTS' && c.op !== 'NOT_EXISTS') {
        item.value = c.value;
      }
      return item;
    })
});

const preview = async () => {
  previewing.value = true;
  try {
    previewResult.value = await previewSegment({
      source: source.value,
      segmentType: form.segmentType,
      ruleConfig: buildRule()
    });
  } finally {
    previewing.value = false;
  }
};

const save = async () => {
  if (!form.segmentName.trim()) return ElMessage.warning('分群名称不能为空');
  if (!form.segmentId && !form.segmentCode.trim()) return ElMessage.warning('分群编码不能为空');
  if (form.segmentType === 'DYNAMIC' && form.conditions.filter((c) => c.field && c.op).length === 0) {
    return ElMessage.warning('动态分群至少需要一个条件');
  }
  saving.value = true;
  try {
    const payload: Record<string, any> = {
      source: source.value,
      segmentName: form.segmentName.trim(),
      segmentType: form.segmentType,
      status: form.status,
      description: form.description,
      ruleConfig: form.segmentType === 'DYNAMIC' ? buildRule() : {}
    };
    if (form.segmentId) {
      payload.segmentId = form.segmentId;
      payload.revision = form.revision;
      await updateSegment(payload);
      ElMessage.success('分群已更新');
    } else {
      payload.segmentCode = form.segmentCode.trim();
      await createSegment(payload);
      ElMessage.success('分群已创建');
    }
    dialogVisible.value = false;
    await loadAll();
  } finally {
    saving.value = false;
  }
};

// ---------------------------------------------------------------- 名单导入
const importVisible = ref(false);
const importing = ref(false);
const importSegment = ref<ProfileSegment | null>(null);
const importForm = reactive({ mode: 'REPLACE', subjectType: '', text: '' });

const openImport = (segment: ProfileSegment) => {
  importSegment.value = segment;
  importForm.mode = 'REPLACE';
  importForm.subjectType = 'user';
  importForm.text = '';
  importVisible.value = true;
};
const doImport = async () => {
  if (!importSegment.value) return;
  if (!importForm.text.trim()) return ElMessage.warning('请粘贴主体 ID');
  importing.value = true;
  try {
    const result = await importSegmentMembers(importSegment.value.segmentId, {
      mode: importForm.mode,
      subjectType: importForm.subjectType || undefined,
      subjectIds: importForm.text
        .split(/[\s,;，；]+/)
        .map((v) => v.trim())
        .filter(Boolean)
    });
    ElMessage.success(`导入完成：成员 ${result.memberCount ?? 0} 人，未找到 ${result.notFound ?? 0} 个`);
    importVisible.value = false;
    await loadAll();
  } finally {
    importing.value = false;
  }
};

// ---------------------------------------------------------------- 成员 / 运行记录
const activeSegment = ref<ProfileSegment | null>(null);
const memberVisible = ref(false);
const memberLoading = ref(false);
const members = ref<ProfileSegmentMemberRow[]>([]);
const memberTotal = ref(0);
const memberQuery = reactive({ pageNum: 1, pageSize: 20, keyword: '' });

const openMembers = async (segment: ProfileSegment) => {
  activeSegment.value = segment;
  memberVisible.value = true;
  memberQuery.pageNum = 1;
  memberQuery.keyword = '';
  await loadMembers();
};
const loadMembers = async () => {
  if (!activeSegment.value) return;
  memberLoading.value = true;
  try {
    const res = await pageSegmentMembers(activeSegment.value.segmentId, {
      pageNum: memberQuery.pageNum,
      pageSize: memberQuery.pageSize,
      keyword: memberQuery.keyword || undefined
    });
    members.value = res.rows;
    memberTotal.value = res.total;
  } finally {
    memberLoading.value = false;
  }
};

const runVisible = ref(false);
const runLoading = ref(false);
const runs = ref<ProfileSegmentRun[]>([]);
const openRuns = async (segment: ProfileSegment) => {
  activeSegment.value = segment;
  runVisible.value = true;
  runLoading.value = true;
  try {
    runs.value = await listSegmentRuns(segment.segmentId);
  } finally {
    runLoading.value = false;
  }
};
const triggerLabel = (trigger?: string) => (trigger === 'IMPORT' ? '名单导入' : trigger === 'INIT' ? '初始化' : '手动');

onMounted(loadAll);
</script>

<style scoped>
.segment-page {
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
  flex-wrap: wrap;
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
  flex-wrap: wrap;
}
.stat-grid {
  margin-top: 14px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
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
.segment-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 14px;
  min-height: 120px;
}
.segment-card :deep(.el-card__body) {
  padding: 16px 18px;
}
.segment-top {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: flex-start;
}
.segment-name {
  font-size: 16px;
  font-weight: 650;
}
.segment-code {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.segment-tags {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
}
.segment-desc {
  margin: 10px 0;
  min-height: 20px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.segment-metrics {
  display: flex;
  gap: 28px;
  padding: 10px 0;
  border-top: 1px dashed var(--el-border-color-lighter);
  border-bottom: 1px dashed var(--el-border-color-lighter);
}
.segment-metrics > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.segment-metrics span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.segment-metrics strong {
  font-size: 18px;
  font-variant-numeric: tabular-nums;
}
.metric-time {
  font-size: 13px !important;
  font-weight: 600;
}
.segment-rule {
  margin: 10px 0;
  display: flex;
  gap: 8px;
  font-size: 12px;
}
.rule-label {
  flex: none;
  color: var(--el-text-color-secondary);
}
.rule-text {
  color: var(--el-text-color-regular);
  line-height: 1.5;
  word-break: break-all;
}
.segment-actions {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}
.rule-toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}
.condition-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
}
.preview-alert {
  margin-top: 10px;
}
.drawer-toolbar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin-bottom: 14px;
}
.mono {
  font-family: var(--el-font-family-mono, ui-monospace, SFMono-Regular, Consolas, monospace);
}
.muted {
  color: var(--el-text-color-secondary);
}
.small {
  font-size: 12px;
}
.subject-cell {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.subject-name {
  font-weight: 600;
}
.subject-meta {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
@media (max-width: 900px) {
  .stat-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
  .segment-grid {
    grid-template-columns: 1fr;
  }
}
</style>
