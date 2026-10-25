<template>
  <div class="app-container">
    <dcs-operations-page-header
      title="任务管理"
      subtitle="发布、编辑、关闭任务，并跟踪成员填报进度"
      eyebrow="任务中心"
      :metrics="[
        { label: '任务总数', value: total },
        { label: '本页草稿', value: draftCount },
        { label: '本页收集中', value: collectingCount }
      ]"
    >
      <template #actions>
        <el-button v-hasPermi="['dcs:task:add']" type="primary" icon="Plus" @click="openCreate">新建任务</el-button>
      </template>
    </dcs-operations-page-header>

    <el-card shadow="never" class="mission-card">
      <el-form :inline="true" class="ui-toolbar mission-filter" @submit.prevent>
        <el-form-item label="标题">
          <el-input v-model="query.title" class="mission-filter__input" placeholder="按标题模糊" clearable @keyup.enter="load" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" class="mission-filter__select" clearable>
            <el-option label="草稿" value="DRAFT" />
            <el-option label="收集中" value="COLLECTING" />
            <el-option label="已关闭" value="CLOSED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button icon="Search" @click="load">查询</el-button>
          <el-button icon="Refresh" @click="reset">重置</el-button>
        </el-form-item>
      </el-form>

      <div class="dcs-ops-table-wrap">
        <el-table v-loading="loading" class="dcs-table-only ui-table mission-table" :data="rows">
          <el-table-column label="任务" min-width="220">
            <template #default="{ row }">
              <el-link class="mission-title-link" @click="goDetail(row.id)">{{ row.title }}</el-link>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <span class="ui-pill" :class="statusPill(row.status)">{{ statusLabel(row.status) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="成员" width="110" align="right">
            <template #default="{ row }">
              <span class="ui-num">{{ assigneeCount(row) }} 人</span>
            </template>
          </el-table-column>
          <el-table-column prop="deadline" label="截止" width="110" />
          <el-table-column prop="createTime" label="创建时间" width="170" />
          <el-table-column label="操作" width="200" fixed="right">
            <template #default="{ row }">
              <el-button v-if="row.status === 'DRAFT'" v-hasPermi="['dcs:task:publish']" link type="primary" @click="publish(row)">发布</el-button>
              <el-button v-if="row.status === 'COLLECTING'" v-hasPermi="['dcs:task:publish']" link @click="close(row)">关闭</el-button>
              <el-button v-if="row.status === 'DRAFT'" v-hasPermi="['dcs:task:edit']" link @click="openEdit(row)">编辑</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="ui-empty">
              <p class="ui-empty__title">暂无任务</p>
              <p class="ui-empty__hint">换个筛选条件，或点右上角「新建任务」。</p>
            </div>
          </template>
        </el-table>
      </div>
      <!-- ≤992：表格改卡片（身份+状态+主操作；次要字段进 meta 行） -->
      <div class="dcs-mobile-list mission-mobile">
        <div v-if="rows.length === 0" class="ui-empty">
          <p class="ui-empty__title">暂无任务</p>
          <p class="ui-empty__hint">换个筛选条件，或点右上角「新建任务」。</p>
        </div>
        <div v-for="row in rows" :key="row.id" class="dcs-mobile-card">
          <div class="dcs-card-head">
            <span class="dcs-card-title" @click="goDetail(row.id)">{{ row.title }}</span>
            <span class="dcs-card-badge"
              ><span class="ui-pill" :class="statusPill(row.status)">{{ statusLabel(row.status) }}</span></span
            >
          </div>
          <div class="dcs-card-meta">
            <span>成员 {{ assigneeCount(row) }} 人</span>
            <span>截止 {{ row.deadline || '—' }}</span>
          </div>
          <div class="dcs-card-time">创建于 {{ row.createTime || '—' }}</div>
          <div class="dcs-card-foot">
            <div class="dcs-card-actions">
              <el-button v-if="row.status === 'DRAFT'" v-hasPermi="['dcs:task:publish']" link type="primary" @click="publish(row)">发布</el-button>
              <el-button v-if="row.status === 'COLLECTING'" v-hasPermi="['dcs:task:publish']" link @click="close(row)">关闭</el-button>
              <el-button v-if="row.status === 'DRAFT'" v-hasPermi="['dcs:task:edit']" link @click="openEdit(row)">编辑</el-button>
              <el-button link @click="goDetail(row.id)">详情</el-button>
            </div>
          </div>
        </div>
      </div>

      <pagination v-show="total > 0" v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="load" />
    </el-card>

    <el-dialog v-model="dialog.visible" :title="dialog.id ? '编辑任务' : '新建任务'" width="680px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="标题" prop="title">
          <el-input v-model="form.title" maxlength="128" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500" />
        </el-form-item>
        <el-form-item label="截止日期">
          <el-date-picker v-model="form.deadline" type="date" value-format="YYYY-MM-DD" class="form-full" />
        </el-form-item>
        <el-form-item label="目标成员" prop="assigneeUserIds">
          <el-input v-model="assigneeInput" placeholder="成员 userId, 逗号分隔" />
        </el-form-item>
        <el-form-item label=" ">
          <span class="tip">审核模式为发布人直审；填错的 userId 会被拒收</span>
        </el-form-item>
        <el-divider content-position="left">填报字段</el-divider>
        <div v-for="(f, i) in form.fields" :key="i" class="field-row">
          <el-input v-model="f.fieldName" class="field-row__name" placeholder="字段名" />
          <el-select v-model="f.fieldType" class="field-row__type">
            <el-option label="文本" value="text" />
            <el-option label="数字" value="number" />
            <el-option label="日期" value="date" />
            <el-option label="单选" value="select" />
            <el-option label="多选" value="multi_select" />
          </el-select>
          <el-input
            v-if="f.fieldType === 'select' || f.fieldType === 'multi_select'"
            v-model="f.options"
            class="field-row__options"
            placeholder="选项,逗号分隔"
          />
          <el-checkbox v-model="f.required" true-value="1" false-value="0" label="必填" />
          <el-button link type="danger" icon="Delete" aria-label="删除字段" @click="form.fields.splice(i, 1)" />
        </div>
        <el-button icon="Plus" @click="addField">加字段</el-button>
      </el-form>
      <template #footer>
        <el-button @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存草稿</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="DcsMission" lang="ts">
import { pageTasks, createTask, updateTask, publishTask, closeTask, getTask } from '@/api/dcs/core/task';
import type { DcsTaskRow, DcsTaskFieldDef } from '@/api/dcs/core/task';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';

const { proxy } = getCurrentInstance() as any;
const router = useRouter();

const loading = ref(false);
const rows = ref<DcsTaskRow[]>([]);
const total = ref(0);
const draftCount = computed(() => rows.value.filter((r) => r.status === 'DRAFT').length);
const collectingCount = computed(() => rows.value.filter((r) => r.status === 'COLLECTING').length);
const query = reactive<any>({ pageNum: 1, pageSize: 10, title: '', status: '' });

const dialog = reactive({ visible: false, id: '' });
const saving = ref(false);
const formRef = ref();
const form = reactive<{
  id?: string;
  title: string;
  description: string;
  deadline: string;
  fields: Array<Omit<DcsTaskFieldDef, 'options'> & { options?: string }>;
}>({
  title: '',
  description: '',
  deadline: '',
  fields: []
});
const assigneeInput = ref('');
const rules = { title: [{ required: true, message: '请输入标题', trigger: 'blur' }] };

const load = async () => {
  loading.value = true;
  try {
    const res = await pageTasks({ ...query, title: query.title || undefined, status: query.status || undefined });
    rows.value = res.rows;
    total.value = res.total;
  } finally {
    loading.value = false;
  }
};
const reset = () => {
  query.pageNum = 1;
  query.title = '';
  query.status = '';
  load();
};
const goDetail = (id: string) => router.push(`/dcs/mission/detail/${id}`);
const statusLabel = (s: string) => ({ DRAFT: '草稿', COLLECTING: '收集中', CLOSED: '已关闭' })[s] || s;
const STATUS_PILLS: Record<string, string> = { DRAFT: 'ui-pill--muted', COLLECTING: 'ui-pill--pos', CLOSED: 'ui-pill--muted' };
const statusPill = (s: string) => STATUS_PILLS[s] || 'ui-pill--muted';

const addField = () => form.fields.push({ fieldCode: '', fieldName: '', fieldType: 'text', required: '1', options: '' });
const openCreate = () => {
  dialog.id = '';
  form.id = undefined;
  form.title = '';
  form.description = '';
  form.deadline = '';
  form.fields = [{ fieldCode: '', fieldName: '填报内容', fieldType: 'text', required: '1', options: '' }];
  assigneeInput.value = '';
  dialog.visible = true;
};
const openEdit = async (row: DcsTaskRow) => {
  try {
    const det = await getTask(row.id);
    const t = det.task;
    dialog.id = row.id;
    form.id = row.id;
    form.title = t.title;
    form.description = t.description || '';
    form.deadline = (t.deadline || '').slice(0, 10);
    // options 出参可能是数组也可能是 JSON 字符串('["a","b"]')——先归一成数组再转逗号串
    form.fields = (det.fields || []).map((f: any) => {
      let opts: unknown = f.options;
      if (typeof opts === 'string') {
        try {
          const parsed = JSON.parse(opts);
          if (Array.isArray(parsed)) opts = parsed;
        } catch {
          /* 本就是逗号串 */
        }
      }
      return { ...f, options: Array.isArray(opts) ? (opts as string[]).join(',') : opts };
    });
    // assigneeUserIds 后端出参是 JSON 字符串("[1,2]")而非数组——归一后再用
    const ids: unknown = (t as any).assigneeUserIds;
    const idList: number[] = Array.isArray(ids) ? ids : typeof ids === 'string' ? JSON.parse(ids || '[]') : [];
    assigneeInput.value = idList.join(',');
    dialog.visible = true;
  } catch {
    proxy.$modal.msgError('任务详情加载失败, 请重试');
  }
};
const assigneeCount = (row: DcsTaskRow) => {
  const v: unknown = (row as any).assigneeUserIds;
  const list = Array.isArray(v) ? v : typeof v === 'string' ? JSON.parse(v || '[]') : [];
  return list.length;
};
const save = async () => {
  await formRef.value?.validate();
  const assigneeUserIds = assigneeInput.value
    .split(/[,，\s]+/)
    .filter(Boolean)
    .map(Number)
    .filter((n) => Number.isFinite(n));
  if (!assigneeUserIds.length) {
    proxy.$modal.msgError('请填写目标成员');
    return;
  }
  const fields = form.fields.map((f, i) => {
    const row: any = { fieldCode: f.fieldCode || `f${i + 1}`, fieldName: f.fieldName, fieldType: f.fieldType, required: f.required };
    if ((f.fieldType === 'select' || f.fieldType === 'multi_select') && f.options) {
      row.options = f.options
        .split(/[,，]/)
        .map((s) => s.trim())
        .filter(Boolean);
    }
    return row;
  });
  // 选择器给 yyyy-MM-DD, 后端 Date 反序列化要全格式——统一补当天 23:59:59 截止
  const deadline = form.deadline ? `${form.deadline.slice(0, 10)} 23:59:59` : undefined;
  saving.value = true;
  try {
    if (dialog.id) await updateTask({ id: dialog.id, title: form.title, description: form.description, deadline, assigneeUserIds, fields } as any);
    else await createTask({ title: form.title, description: form.description, deadline, assigneeUserIds, fields } as any);
    proxy.$modal.msgSuccess('已保存');
    dialog.visible = false;
    load();
  } finally {
    saving.value = false;
  }
};
const publish = async (row: DcsTaskRow) => {
  await proxy.$modal.confirm(`发布「${row.title}」？成员即可填报`);
  await publishTask(row.id);
  proxy.$modal.msgSuccess('已发布');
  load();
};
const close = async (row: DcsTaskRow) => {
  await proxy.$modal.confirm(`关闭「${row.title}」？需先审完在途提交`);
  await closeTask(row.id);
  proxy.$modal.msgSuccess('已关闭');
  load();
};

onMounted(load);
</script>

<style scoped>
/* 筛选条与表格同卡：卡体不留内边距，由 .ui-toolbar 自己管 */
.mission-card :deep(.el-card__body) {
  min-width: 0;
  padding: 0;
}

.mission-filter :deep(.el-form-item) {
  margin: 0;
}

.mission-filter__input {
  width: 200px;
}

.mission-filter__select {
  width: 140px;
}

/* 表格规格走 Element Plus 变量，不覆盖组件结构 */
.mission-table {
  --el-table-header-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-border-color: var(--app-rule);
  --el-table-row-hover-bg-color: var(--app-raised);
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
}

.mission-title-link {
  color: var(--app-text);
  font-weight: 500;
}

.mission-title-link:hover {
  color: var(--app-muted);
}

.mission-mobile {
  padding: 12px 16px 0;
}

.field-row {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}

.field-row__name {
  width: 130px;
}

.field-row__type {
  width: 100px;
}

.field-row__options {
  width: 160px;
}

.form-full {
  width: 100%;
}

.tip {
  color: var(--app-muted);
  font-size: var(--dcs-font-sm);
}

@media (max-width: 768px) {
  .mission-filter__input,
  .mission-filter__select {
    width: 100%;
  }

  .field-row {
    flex-wrap: wrap;
  }

  .field-row__name,
  .field-row__type,
  .field-row__options {
    width: 100%;
  }
}
</style>
