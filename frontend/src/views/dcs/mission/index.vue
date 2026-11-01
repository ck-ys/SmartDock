<template>
  <div class="app-container">
    <dcs-operations-page-header
      title="任务管理"
      subtitle="发布、编辑、关闭任务，并跟踪成员填报进度"
      eyebrow="任务中心"
      :metrics="headerMetrics"
    >
      <template #actions>
        <el-button v-hasPermi="['dcs:task:add']" type="primary" icon="Plus" @click="openCreate">新建任务</el-button>
      </template>
    </dcs-operations-page-header>

    <el-card shadow="never" class="mission-card">
      <el-form :inline="true" class="ui-toolbar mission-filter" @submit.prevent>
        <el-form-item label="标题">
          <el-input v-model="query.title" class="mission-filter__input" placeholder="按标题模糊" clearable @keyup.enter="onSearch" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" class="mission-filter__select" clearable>
            <el-option label="草稿" value="DRAFT" />
            <el-option label="收集中" value="COLLECTING" />
            <el-option label="已关闭" value="CLOSED" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button icon="Search" @click="onSearch">查询</el-button>
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
              <!-- 2026-10-30 题14（方案A）：详情入口全状态固定第一位，对齐手机端卡片 -->
              <el-button link type="primary" @click="goDetail(row.id)">详情</el-button>
              <el-button v-if="row.status === 'DRAFT'" v-hasPermi="['dcs:task:publish']" link type="primary" @click="publish(row)">发布</el-button>
              <el-button v-if="row.status === 'COLLECTING'" v-hasPermi="['dcs:task:publish']" link @click="close(row)">关闭</el-button>
              <el-button v-if="row.status === 'DRAFT'" v-hasPermi="['dcs:task:edit']" link @click="openEdit(row)">编辑</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="ui-empty">
              <p class="ui-empty__title">{{ hasFilter ? '没有符合条件的任务' : '暂无任务' }}</p>
              <p class="ui-empty__hint">
                {{ hasFilter ? `当前筛选 ${total} 条；全部任务还有 ${totalAll} 条。` : '还没有建过任务，新建一个就能开始分派。' }}
              </p>
              <el-button v-if="hasFilter" type="primary" plain @click="reset">清空筛选，看全部</el-button>
              <el-button v-else v-hasPermi="['dcs:task:add']" type="primary" @click="openCreate">新建任务</el-button>
            </div>
          </template>
        </el-table>
      </div>
      <!-- ≤992：表格改卡片（身份+状态+主操作；次要字段进 meta 行） -->
      <div class="dcs-mobile-list mission-mobile">
        <div v-if="rows.length === 0 && !loading" class="ui-empty">
          <p class="ui-empty__title">{{ hasFilter ? '没有符合条件的任务' : '暂无任务' }}</p>
          <p class="ui-empty__hint">
            {{ hasFilter ? `当前筛选 ${total} 条；全部任务还有 ${totalAll} 条。` : '还没有建过任务，新建一个就能开始分派。' }}
          </p>
          <el-button v-if="hasFilter" type="primary" plain @click="reset">清空筛选，看全部</el-button>
          <el-button v-else v-hasPermi="['dcs:task:add']" type="primary" @click="openCreate">新建任务</el-button>
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

    <el-dialog v-model="dialog.visible" :title="dialog.id ? '编辑任务' : '新建任务'" width="640px">
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
        <el-form-item label="目标成员" prop="assigneeUserIds" required>
          <!-- 2026-10-30 题7（方案A）：搜姓名勾人，不再让人背数字 ID；底层仍提交 userId 数组 -->
          <el-select
            v-model="assigneeIds"
            class="mission-assignee"
            multiple
            filterable
            remote
            clearable
            reserve-keyword
            :remote-method="searchUsers"
            :loading="userLoading"
            placeholder="搜姓名或账号，可多选"
          >
            <el-option v-for="u in userOptions" :key="u.value" :label="u.label" :value="u.value" />
          </el-select>
        </el-form-item>
        <el-form-item label=" ">
          <span class="tip">审核模式为发布人直审；只列你在本单位能看到的启用成员</span>
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
// 依赖 system:user:list / system:user:query（当前持 dcs:task:* 的角色 admin/tenant_admin 都带这两个权限；
// 若日后新增「只授 dcs:task:*」的角色，选人下拉会取不到数据）
import { listUser, optionSelect } from '@/api/system/user';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';

const { proxy } = getCurrentInstance() as any;
const router = useRouter();

const loading = ref(false);
const rows = ref<DcsTaskRow[]>([]);
const total = ref(0);
const draftCount = computed(() => rows.value.filter((r) => r.status === 'DRAFT').length);
const collectingCount = computed(() => rows.value.filter((r) => r.status === 'COLLECTING').length);
const query = reactive<any>({ pageNum: 1, pageSize: 10, title: '', status: '' });

/* 2026-11-01 Q6=乙：「任务总数」钉死全量。筛选后列表接口的 total 是筛选结果，
   另起一次 pageSize=1 的全量请求取总数；筛选结果单独显示「当前筛选」。 */
const totalAll = ref(0);
/* 2026-11-01 Q6 深挖席 P1-2：页头/空态按「已提交的查询」说话，不跟输入框实时值走 */
const appliedFilter = ref(false);
const hasFilter = computed(() => appliedFilter.value);
const headerMetrics = computed(() => {
  const list: Array<{ label: string; value: number; hint?: string }> = [
    { label: '任务总数', value: totalAll.value, hint: '不受筛选影响' }
  ];
  if (hasFilter.value) list.push({ label: '当前筛选', value: total.value });
  list.push({ label: '本页草稿', value: draftCount.value, hint: '本页范围' }, { label: '本页收集中', value: collectingCount.value, hint: '本页范围' });
  return list;
});

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
/**
 * 2026-10-30 题7（方案A）：成员改成「搜名字勾选」。
 * 下拉远程搜单位内启用成员，选中项保留在 userOptions 里当标签；提交仍只传 userId 数组。
 */
type AssigneeOption = { value: string | number; label: string };
const assigneeIds = ref<Array<string | number>>([]);
const userOptions = ref<AssigneeOption[]>([]);
const userLoading = ref(false);
const toOption = (u: { userId: string | number; nickName?: string; userName?: string }): AssigneeOption => ({
  value: u.userId,
  label: `${u.nickName || u.userName || u.userId}${u.userName ? `（${u.userName}）` : ''}`
});
const mergeOptions = (list: AssigneeOption[]) => {
  const known = new Set(userOptions.value.map((o) => String(o.value)));
  userOptions.value = [...userOptions.value, ...list.filter((o) => !known.has(String(o.value)))];
};
/**
 * 后端 userName / nickName 是两个 AND 条件，单传一个只按该字段搜；
 * 这里并发查两次取并集，才等于占位符承诺的「姓名或账号」。
 * remote 模式下 Element Plus 不做本地过滤，所以每轮要按本次结果重建列表（只保留已选项）。
 */
let searchSeq = 0;
const searchUsers = async (keyword: string) => {
  const seq = ++searchSeq;
  userLoading.value = true;
  try {
    const kw = keyword || undefined;
    const [byAccount, byName] = await Promise.all([
      listUser({ pageNum: 1, pageSize: 20, userName: kw, status: '0' }),
      listUser({ pageNum: 1, pageSize: 20, nickName: kw, status: '0' })
    ]);
    const hits = new Map<string, AssigneeOption>();
    [...(byAccount.rows || []), ...(byName.rows || [])].forEach((u) => {
      const opt = toOption(u);
      hits.set(String(opt.value), opt);
    });
    // 连打时旧请求可能后到：只有最后一次搜索能改列表，否则回退成上一轮的命中
    if (seq !== searchSeq) return;
    const picked = new Set(assigneeIds.value.map(String));
    const selected = userOptions.value.filter((o) => picked.has(String(o.value)));
    const rest = [...hits.values()].filter((o) => !picked.has(String(o.value)));
    userOptions.value = [...selected, ...rest];
  } catch {
    if (seq === searchSeq) proxy.$modal.msgError('成员列表加载失败，请重试');
  } finally {
    if (seq === searchSeq) userLoading.value = false;
  }
};
const loadAssigneeOptions = async (ids: Array<string | number>) => {
  if (!ids.length) return;
  try {
    const res = await optionSelect(ids);
    mergeOptions((res.data || []).map(toOption));
  } catch {
    // 只影响标签显示，提交仍用 ID 数组，不阻断编辑
  }
  // 停用/越权成员查不到名字时，至少给个可读占位（否则标签显示一串 19 位数字）
  const known = new Set(userOptions.value.map((o) => String(o.value)));
  const missing = ids.filter((id) => !known.has(String(id)));
  if (missing.length) {
    userOptions.value = [...userOptions.value, ...missing.map((id) => ({ value: id, label: `用户 ${id}（已停用或不可见）` }))];
  }
};
const rules = { title: [{ required: true, message: '请输入标题', trigger: 'blur' }] };

/* 2026-11-01 Q6 深挖席 P1-3：并发序号，过期响应直接丢 */
let loadSeq = 0;
const load = async () => {
  const seq = ++loadSeq;
  const title = String(query.title || '').trim();
  appliedFilter.value = !!title || !!query.status;
  loading.value = true;
  try {
    const res = await pageTasks({ ...query, title: title || undefined, status: query.status || undefined });
    if (seq !== loadSeq) return;
    rows.value = res.rows;
    total.value = res.total;
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
  if (seq !== loadSeq) return;
  if (!hasFilter.value) {
    // 无筛选时列表 total 即全量，直接用，省一次请求也避免页头 0→N 闪
    totalAll.value = Number(total.value) || 0;
  } else {
    // 筛选态每次校准全量：筛选中新建任务后总数不能停在旧值（深挖席 P1-1）
    const all = await pageTasks({ pageNum: 1, pageSize: 1 }).catch(() => undefined);
    if (seq === loadSeq && all) totalAll.value = Number(all.total) || 0;
  }
};
const onSearch = () => {
  query.pageNum = 1;
  load();
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
  assigneeIds.value = [];
  // 打开即给一屏可选成员，不强迫先打字
  void searchUsers('');
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
    // assigneeUserIds 后端出参是 JSON 文本("[1,2]")：19 位雪花 ID 走 JSON.parse 会被 JS 截断，
    // 这里直接抽数字串保住原值（批2 三审 P0）
    const raw: unknown = (t as any).assigneeUserIds;
    const idList: string[] = Array.isArray(raw) ? raw.map(String) : (String(raw ?? '').match(/\d+/g) ?? []);
    assigneeIds.value = idList;
    await loadAssigneeOptions(idList);
    dialog.visible = true;
  } catch {
    proxy.$modal.msgError('任务详情加载失败, 请重试');
  }
};
const assigneeCount = (row: DcsTaskRow) => {
  const v: unknown = (row as any).assigneeUserIds;
  return Array.isArray(v) ? v.length : (String(v ?? '').match(/\d+/g) || []).length;
};
const save = async () => {
  await formRef.value?.validate();
  // 选择器给的是字符串 ID（雪花 19 位转 Number 会丢精度，三审 P0）；只做合法性校验，不做类型转换
  const assigneeUserIds = assigneeIds.value.map(String).filter((v) => /^\d+$/.test(v));
  if (!assigneeUserIds.length) {
    proxy.$modal.msgError('请选择目标成员');
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
  --el-table-row-hover-bg-color: var(--app-raised);
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
}

/* 2026-10-30 题14（方案A）：标题是详情入口，必须看得出能点——恢复主色链接观感，hover 加下划线 */
.mission-title-link {
  color: var(--app-accent);
  font-weight: 500;
}

.mission-title-link:hover {
  text-decoration: underline;
  text-underline-offset: 3px;
}

/* 深色皮肤下 --app-accent(#4583ff) 对 #242424 只有 4.39:1（批1 快筛席实测），
   链接单独提亮一档到 5.8:1；只影响本页标题，不动全局主题令牌 */
html.dark .mission-title-link {
  color: #6f9dff;
}

html.dark .mission-title-link:hover {
  color: #86aeff;
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

/* 2026-10-30 题7：多选标签需要整行宽度，长名字不挤 */
.mission-assignee {
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
