<template>
  <div class="app-container changelog-page">
    <support-page-head eyebrow="系统支持" title="更新日志" description="每次改进都用大白话记在这里，方便你快速知道系统变了什么。">
      <template #aside>
        <span v-if="latest" class="ui-pill ui-pill--pos" aria-label="最新版本">最新 · {{ latest.version }}</span>
        <el-button v-if="canManage" type="primary" :icon="Plus" @click="openCreate">新建版本</el-button>
      </template>
    </support-page-head>

    <div v-if="canManage" class="cl-admin-bar">
      <el-radio-group v-model="query.status" @change="reload">
        <el-radio-button value="">全部</el-radio-button>
        <el-radio-button value="1">已发布</el-radio-button>
        <el-radio-button value="0">草稿</el-radio-button>
      </el-radio-group>
      <span class="cl-admin-tip">草稿只有超级管理员可见；发布后按推送名单通知各单位管理员。</span>
    </div>

    <section v-loading="loading" class="cl-list" aria-label="版本记录">
      <article
        v-for="(entry, index) in entries"
        :key="entry.logId"
        class="cl-item"
        :class="{ 'is-featured': index === 0 && entry.logId === latestLogId, 'is-draft': entry.status === '0' }"
      >
        <div class="cl-card">
          <div class="cl-meta">
            <span class="cl-ver">{{ entry.version }}</span>
            <time class="cl-date" :datetime="entry.publishTime || entry.createTime">{{ formatDate(entry.publishTime || entry.createTime) }}</time>
            <span v-if="entry.logId === latestLogId" class="ui-pill ui-pill--pos">本次更新</span>
            <span v-if="entry.status === '0'" class="ui-pill ui-pill--warn">草稿</span>
            <span v-if="canManage && entry.status === '1'" class="cl-targets">推送 {{ entry.targetTenantList?.length || 0 }} 家</span>
            <span v-if="canManage" class="cl-actions">
              <el-button link type="primary" size="small" @click="openEdit(entry)">编辑</el-button>
              <el-button v-if="entry.status === '0'" link type="primary" size="small" @click="openEdit(entry, true)">发布</el-button>
              <el-button link type="danger" size="small" @click="remove(entry)">删除</el-button>
            </span>
          </div>
          <div class="cl-main">
            <h2 class="cl-title">{{ entry.title }}</h2>
            <ul class="cl-points">
              <li v-for="(item, idx) in entry.itemList" :key="idx">
                <span class="ui-pill cl-type" :class="typePill(item.type)">{{ itemTypeLabel(item.type) }}</span>
                <span class="cl-point-text">{{ item.text }}</span>
              </li>
            </ul>
          </div>
        </div>
      </article>

      <el-empty v-if="!loading && entries.length === 0" class="cl-empty" :description="emptyText" :image-size="72" />
    </section>

    <pagination
      v-show="total > query.pageSize"
      v-model:page="query.pageNum"
      v-model:limit="query.pageSize"
      :page-sizes="[20, 50, 100]"
      :total="total"
      @pagination="load"
    />

    <p class="cl-foot">想了解某次变化怎么用，可以直接问右下角的小助手。</p>

    <!-- 新建 / 编辑版本 -->
    <el-dialog v-model="editorVisible" :title="editing ? `编辑 ${form.version}` : '新建版本'" width="760px" append-to-body top="6vh">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="cl-form">
        <div class="cl-form__row">
          <el-form-item label="版本号" prop="version" class="cl-form__ver">
            <el-input v-model="form.version" maxlength="32" placeholder="v1.10.0" />
          </el-form-item>
          <el-form-item label="发布状态" class="cl-form__status">
            <el-radio-group v-model="form.status">
              <el-radio-button value="0">草稿</el-radio-button>
              <el-radio-button value="1">已发布</el-radio-button>
            </el-radio-group>
          </el-form-item>
        </div>

        <el-form-item label="一句话主题" prop="title">
          <el-input v-model="form.title" maxlength="200" show-word-limit placeholder="例如：钥匙与模型：三种接口格式、20 家厂商模板" />
        </el-form-item>

        <el-form-item label="推送单位">
          <div class="cl-tenants">
            <el-select
              v-model="form.targetTenantList"
              multiple
              filterable
              collapse-tags
              collapse-tags-tooltip
              placeholder="选择能看到这条日志的单位"
              class="cl-tenants__select"
            >
              <el-option
                v-for="tenant in tenantOptions"
                :key="tenant.tenantId"
                :label="tenant.companyName || tenant.tenantId"
                :value="tenant.tenantId"
              />
            </el-select>
            <el-button text @click="selectAllTenants">全选</el-button>
            <el-button text @click="form.targetTenantList = []">清空</el-button>
          </div>
          <p class="cl-form__hint">发布前至少选一家；发布时会给所选单位的管理员发一条站内通知。</p>
        </el-form-item>

        <el-form-item label="更新条目（每条一句话，说用户能感知到的变化）">
          <div class="cl-items">
            <div v-for="(item, idx) in form.itemList" :key="idx" class="cl-item-row">
              <el-select v-model="item.type" class="cl-item-row__type">
                <el-option label="新增" value="new" />
                <el-option label="优化" value="optimize" />
                <el-option label="修复" value="fix" />
              </el-select>
              <el-input v-model="item.text" maxlength="200" show-word-limit placeholder="用大白话写这一条改了什么" />
              <el-button :icon="Delete" circle text :disabled="form.itemList.length <= 1" @click="removeItem(idx)" />
            </div>
            <el-button :icon="Plus" text :disabled="form.itemList.length >= 50" @click="addItem">再加一条</el-button>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button v-if="!editing || editing.status === '0'" :loading="saving" @click="save('0')">保存草稿</el-button>
        <el-button type="primary" :loading="saving" @click="save('1')">{{ editing?.status === '1' ? '保存修改' : '保存并发布' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup name="DcsChangelog" lang="ts">
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { Delete, Plus } from '@element-plus/icons-vue';
import { addChangelog, deleteChangelog, listChangelog, updateChangelog } from '@/api/dcs/changelog';
import type { ChangelogItem, ChangelogRow, ChangelogSaveBody } from '@/api/dcs/changelog/types';
import { getTenantList } from '@/api/login';
import type { TenantVO } from '@/api/types';
import { checkPermi } from '@/utils/permission';
import SupportPageHead from '@/views/dcs/support/components/SupportPageHead.vue';

const canManage = computed(() => checkPermi(['dcs:changelog:add']));

const loading = ref(false);
const entries = ref<ChangelogRow[]>([]);
const total = ref(0);
const query = reactive({ pageNum: 1, pageSize: 50, status: '' });
/* 头部「最新 · vX」是系统级信息，翻页/切筛选后也要保留，不能跟着当前页丢 */
const latest = ref<ChangelogRow | null>(null);
const latestLogId = computed(() => latest.value?.logId);
const emptyText = computed(() => {
  if (query.status === '0') {
    return '没有草稿';
  }
  return query.status === '1' ? '还没有已发布的记录' : '还没有更新记录';
});

const TYPE_LABELS: Record<ChangelogItem['type'], string> = { new: '新增', optimize: '优化', fix: '修复' };
const itemTypeLabel = (type: ChangelogItem['type']) => TYPE_LABELS[type] || '改进';
const TYPE_PILLS: Record<ChangelogItem['type'], string> = { new: 'ui-pill--pos', optimize: 'ui-pill--muted', fix: 'ui-pill--warn' };
const typePill = (type: ChangelogItem['type']) => TYPE_PILLS[type] || 'ui-pill--muted';

/** 2026-10-19 12:00:00 → 2026年10月19日（业务口径，不用斜杠/ISO） */
const formatDate = (value?: string) => {
  if (!value) {
    return '—';
  }
  const [y, m, d] = value.split(' ')[0].split('-');
  return `${y}年${Number(m)}月${Number(d)}日`;
};

let loadSeq = 0;
const load = async () => {
  const currentSeq = ++loadSeq;
  loading.value = true;
  try {
    const res: any = await listChangelog({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      status: canManage.value && query.status ? query.status : undefined
    });
    if (currentSeq !== loadSeq) {
      return; // 快速切筛选时旧响应不得覆盖新结果
    }
    entries.value = res.rows || [];
    total.value = Number(res.total || 0);
    const published = entries.value.find((entry) => entry.status === '1');
    if (published) {
      latest.value = published;
    }
  } finally {
    if (currentSeq === loadSeq) {
      loading.value = false;
    }
  }
};

const reload = () => {
  query.pageNum = 1;
  load();
};

/* ---------- 编辑 ---------- */
const editorVisible = ref(false);
const saving = ref(false);
const editing = ref<ChangelogRow | null>(null);
const formRef = ref<FormInstance>();
const tenantOptions = ref<TenantVO[]>([]);
const form = reactive<ChangelogSaveBody>({
  version: '',
  title: '',
  itemList: [{ type: 'new', text: '' }],
  status: '0',
  targetTenantList: []
});

const rules: FormRules = {
  version: [
    { required: true, message: '请填写版本号', trigger: 'blur' },
    { pattern: /^v?\d+(\.\d+)*$/, message: '版本号格式应为 v1.2.3', trigger: 'blur' }
  ],
  title: [{ required: true, message: '请填写版本主题', trigger: 'blur' }]
};

const loadTenants = async () => {
  if (tenantOptions.value.length) {
    return;
  }
  try {
    const { data } = await getTenantList(true);
    tenantOptions.value = data.voList || [];
  } catch {
    tenantOptions.value = [];
  }
};

const resetForm = () => {
  form.version = '';
  form.title = '';
  form.itemList = [{ type: 'new', text: '' }];
  form.status = '0';
  form.targetTenantList = [];
  formRef.value?.clearValidate();
};

const openCreate = async () => {
  editing.value = null;
  resetForm();
  await loadTenants();
  editorVisible.value = true;
};

const openEdit = async (row: ChangelogRow, publishNow = false) => {
  editing.value = row;
  await loadTenants();
  form.version = row.version;
  form.title = row.title;
  form.itemList = (row.itemList || []).map((item) => ({ ...item }));
  if (!form.itemList.length) {
    form.itemList = [{ type: 'new', text: '' }];
  }
  form.status = publishNow ? '1' : row.status;
  form.targetTenantList = [...(row.targetTenantList || [])];
  editorVisible.value = true;
  formRef.value?.clearValidate();
};

const addItem = () => {
  if (form.itemList.length < 50) {
    form.itemList.push({ type: 'new', text: '' });
  }
};

const removeItem = (idx: number) => {
  if (form.itemList.length > 1) {
    form.itemList.splice(idx, 1);
  }
};

const selectAllTenants = () => {
  form.targetTenantList = tenantOptions.value.map((tenant) => tenant.tenantId);
};

const save = async (status: '0' | '1') => {
  await formRef.value?.validate();
  const items = form.itemList.map((item) => ({ type: item.type, text: item.text.trim() }));
  if (items.some((item) => !item.text)) {
    ElMessage.warning('每条更新都需要填写内容');
    return;
  }
  if (status === '1' && !(form.targetTenantList || []).length) {
    ElMessage.warning('发布前至少选择一家推送单位');
    return;
  }
  saving.value = true;
  try {
    const body: ChangelogSaveBody = {
      version: form.version.trim(),
      title: form.title.trim(),
      itemList: items,
      status,
      targetTenantList: form.targetTenantList
    };
    if (editing.value) {
      await updateChangelog({ ...body, logId: editing.value.logId });
    } else {
      const res: any = await addChangelog({ ...body, status: '0' });
      const newId = res?.data;
      if (status === '1' && newId) {
        await updateChangelog({ ...body, logId: newId, status: '1' });
      }
    }
    ElMessage.success(status === '1' ? '已发布，并通知所选单位的管理员' : '草稿已保存');
    editorVisible.value = false;
    await load();
  } finally {
    saving.value = false;
  }
};

const remove = async (row: ChangelogRow) => {
  await ElMessageBox.confirm(`删除版本 ${row.version}？删除后不可恢复。`, '删除更新日志', { type: 'warning' });
  await deleteChangelog([row.logId]);
  ElMessage.success('已删除');
  await load();
};

onMounted(load);
</script>

<style lang="scss" scoped>
.changelog-page {
  max-width: 1600px;
  margin: 0 auto;
}

.cl-admin-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  padding-bottom: 12px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--app-rule);

  .cl-admin-tip {
    font-size: 12px;
    line-height: 1.6;
    color: var(--app-faint);
  }
}

/* ---------- 版本列表 ---------- */
.cl-list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  min-height: 160px;
}

.cl-item {
  min-width: 0;
}

.cl-card {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 18px 20px;
  border: var(--app-card-border);
  border-radius: var(--app-card-radius);
  background: var(--app-surface);
  transition:
    border-color 0.18s ease,
    background-color 0.18s ease;
}

.cl-item:not(.is-featured):hover .cl-card {
  background: var(--app-raised);
  border-color: var(--app-control);
}

.cl-item.is-featured:hover .cl-card {
  border-color: var(--app-control);
}

.cl-item.is-draft .cl-card {
  border-style: dashed;
  background: var(--app-raised);
}

/* 最新一版占满整行，左侧版本信息做视觉锚点 */
.cl-item.is-featured {
  grid-column: 1 / -1;

  .cl-card {
    display: grid;
    grid-template-columns: 200px minmax(0, 1fr);
    padding: 0;
    overflow: hidden;
  }

  .cl-meta {
    flex-direction: column;
    align-items: flex-start;
    align-content: flex-start;
    gap: 8px;
    margin: 0;
    padding: 20px;
    background: var(--app-raised);
    border-right: 1px solid var(--app-rule);
  }

  .cl-main {
    padding: 20px 24px;
  }

  .cl-ver {
    font-size: var(--dcs-font-page);
    letter-spacing: -0.02em;
  }

  .cl-title {
    max-width: 30em;
    font-size: 19px;
    line-height: 1.45;
  }

  .cl-actions {
    width: 100%;
    margin: auto 0 0;
    padding-top: 14px;
    justify-content: flex-start;
  }

  .cl-points {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px 28px;
  }

  .cl-points li {
    margin: 0;
  }

  .cl-points li:only-child {
    grid-column: 1 / -1;
  }
}

.cl-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-width: 0;
  margin-bottom: 10px;
}

.cl-ver {
  font-size: 14px;
  font-weight: 600;
  color: var(--app-text);
  font-variant-numeric: tabular-nums;
}

.cl-date {
  font-size: 12px;
  color: var(--app-faint);
  font-variant-numeric: tabular-nums;
}

.cl-targets {
  font-size: 12px;
  color: var(--app-faint);
}

/* 操作栏常显 55%，悬停/聚焦升到 100%：不再依赖悬停才出现（可发现性） */
.cl-actions {
  display: inline-flex;
  align-items: center;
  margin-left: auto;
  opacity: 0.55;
  transition: opacity 0.15s ease;
}

.cl-item:hover .cl-actions,
.cl-item:focus-within .cl-actions {
  opacity: 1;
}

.cl-main {
  min-width: 0;
}

.cl-title {
  margin: 0 0 10px;
  font-size: 16px;
  line-height: 1.55;
  font-weight: 600;
  color: var(--app-text);
  overflow-wrap: anywhere;
}

.cl-points {
  margin: 0;
  padding: 0;
  list-style: none;

  li {
    display: flex;
    align-items: flex-start;
    gap: 8px;
    font-size: 13px;
    line-height: 1.72;
    color: var(--app-muted);
    overflow-wrap: anywhere;

    & + li {
      margin-top: 8px;
    }
  }
}

.cl-type {
  flex: none;
  margin-top: 1px;
}

.cl-point-text {
  min-width: 0;
}

.cl-empty {
  grid-column: 1 / -1;
  padding: 40px 0;
  border: var(--app-card-border);
  border-radius: var(--app-card-radius);
}

.cl-foot {
  margin: 14px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--app-faint);
}

/* ---------- 编辑器 ---------- */
.cl-form {
  :deep(.el-form-item) {
    margin-bottom: 16px;
  }

  :deep(.el-form-item__label) {
    padding-bottom: 4px;
    font-size: var(--dcs-font-md);
    color: var(--app-muted);
  }

  &__row {
    display: flex;
    gap: 16px;
  }

  &__ver {
    flex: 0 0 220px;
  }

  &__status {
    flex: none;
  }

  &__hint {
    margin: 6px 0 0;
    font-size: 12px;
    line-height: 1.6;
    color: var(--app-faint);
  }
}

.cl-tenants {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;

  &__select {
    flex: 1;
    min-width: 0;
  }
}

.cl-items {
  width: 100%;
}

.cl-item-row {
  display: flex;
  align-items: flex-start;
  gap: 8px;

  & + & {
    margin-top: 8px;
  }

  &__type {
    flex: 0 0 92px;
  }
}

/* ---------- 响应式 ---------- */
@media (max-width: 992px) {
  .cl-item.is-featured .cl-points {
    display: block;
  }

  .cl-item.is-featured .cl-points li + li {
    margin-top: 8px;
  }

  .cl-list {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (max-width: 768px) {
  .changelog-page {
    padding-left: 12px;
    padding-right: 12px;
  }

  .cl-item.is-featured .cl-card {
    display: flex;
    flex-direction: column;
  }

  .cl-item.is-featured .cl-meta {
    flex-direction: row;
    flex-wrap: wrap;
    align-items: center;
    gap: 8px;
    padding: 14px 16px;
    border-right: 0;
    border-bottom: 1px solid var(--app-rule);
  }

  .cl-item.is-featured .cl-main {
    padding: 16px;
  }

  .cl-item.is-featured .cl-ver {
    font-size: 18px;
  }

  .cl-item.is-featured .cl-actions {
    width: 100%;
    margin: 0;
    padding-top: 0;
    justify-content: flex-end;
  }

  .cl-card {
    padding: 16px;
    border-radius: var(--app-card-radius);
  }

  .cl-title {
    font-size: 15.5px;
  }

  .cl-points li {
    font-size: 14px;
    line-height: 1.7;
  }

  .cl-form__row {
    flex-direction: column;
    gap: 0;
  }

  .cl-form__ver {
    flex: 1;
  }
}
/* ---------- 超宽屏 ---------- */
@media (min-width: 1600px) {
  .cl-list {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
</style>
