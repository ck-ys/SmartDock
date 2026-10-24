<template>
  <div class="app-container feedback-page">
    <support-page-head title="反馈中心" description="用户提交的缺陷、体验与建议都在这里；处理结果会通知提交人。">
      <template #aside>
        <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        <el-button type="primary" :icon="EditPen" @click="dialogVisible = true">提交反馈</el-button>
      </template>
    </support-page-head>

    <section class="fb-stats" aria-label="反馈状态概览">
      <button
        v-for="item in statCards"
        :key="item.key"
        type="button"
        class="fb-stat"
        :class="[`is-${(item.key || 'all').toLowerCase()}`, { 'is-active': query.status === item.key }]"
        @click="pickStatus(item.key)"
      >
        <span class="fb-stat__label">
          <i class="fb-stat__dot" aria-hidden="true" />
          {{ item.label }}
        </span>
        <b class="fb-stat__value">{{ item.key ? (summary[item.key] ?? 0) : totalAll }}</b>
        <span class="fb-stat__hint">{{ item.hint }}</span>
      </button>
    </section>

    <section class="fb-filters">
      <el-input
        v-model="query.title"
        class="fb-filters__search"
        clearable
        placeholder="搜索标题"
        :prefix-icon="Search"
        @clear="reload"
        @keyup.enter="reload"
      />
      <el-select v-model="query.feedbackType" class="fb-filters__select" clearable placeholder="全部类型" @change="reload">
        <el-option v-for="item in typeOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-select v-model="query.status" class="fb-filters__select" clearable placeholder="全部状态" @change="reload">
        <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-button @click="reload">查询</el-button>
      <el-button text @click="resetFilters">重置</el-button>
    </section>

    <el-card shadow="never" class="fb-card">
      <el-table v-loading="loading" class="dcs-table-only" :data="rows" row-key="feedbackId" @row-click="(row) => openDetail(row)">
        <el-table-column label="反馈" min-width="320">
          <template #default="{ row }">
            <div class="fb-cell">
              <div class="fb-cell__line">
                <span class="fb-type" :class="`is-${(row.feedbackType || 'other').toLowerCase()}`">{{ typeLabel(row.feedbackType) }}</span>
                <span class="fb-cell__title">{{ row.title }}</span>
                <span v-if="row.ossIdList?.length" class="fb-attach">{{ row.ossIdList.length }} 图</span>
              </div>
              <p class="fb-cell__excerpt">{{ row.content }}</p>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="提交人" width="120">
          <template #default="{ row }">{{ row.createByName || row.createBy || '—' }}</template>
        </el-table-column>
        <el-table-column prop="createTime" label="提交时间" width="170" />
        <el-table-column label="状态" width="96">
          <template #default="{ row }">
            <span class="fb-status" :class="`is-${(row.status || 'pending').toLowerCase()}`">{{ statusLabel(row.status) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="处理人" width="120">
          <template #default="{ row }">{{ row.handlerName || '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click.stop="openDetail(row)">查看</el-button>
            <el-button link type="primary" v-hasPermi="['dcs:feedback:handle']" @click.stop="openDetail(row, true)">处理</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <div class="fb-empty">
            <p class="fb-empty__title">{{ hasFilter ? '没有匹配的反馈' : '还没有反馈' }}</p>
            <p class="fb-empty__desc">
              {{
                hasFilter ? '换个关键词或状态试试，也可以清空筛选查看全部反馈。' : '遇到问题或有改进建议，点右上角「提交反馈」；处理进度会回到这里。'
              }}
            </p>
            <el-button v-if="hasFilter" @click="resetFilters">清空筛选</el-button>
            <el-button v-else type="primary" plain @click="dialogVisible = true">提交第一条反馈</el-button>
          </div>
        </template>
      </el-table>

      <!-- ≤992：表格改卡片（与其它业务页同一套全局类）；v-loading 挂在列表上，表格隐藏时仍可见 -->
      <div v-loading="loading" class="dcs-mobile-list">
        <div v-if="!loading && rows.length === 0" class="fb-empty fb-empty--mobile">
          <p class="fb-empty__title">{{ hasFilter ? '没有匹配的反馈' : '还没有反馈' }}</p>
          <p class="fb-empty__desc">
            {{
              hasFilter ? '换个关键词或状态试试，也可以清空筛选查看全部反馈。' : '遇到问题或有改进建议，点右上角「提交反馈」；处理进度会回到这里。'
            }}
          </p>
          <el-button v-if="hasFilter" @click="resetFilters">清空筛选</el-button>
          <el-button v-else type="primary" plain @click="dialogVisible = true">提交第一条反馈</el-button>
        </div>
        <div
          v-for="row in rows"
          :key="row.feedbackId"
          class="dcs-mobile-card fb-mcard"
          role="button"
          tabindex="0"
          @click="openDetail(row)"
          @keydown.enter.prevent="openDetail(row)"
        >
          <div class="dcs-card-head">
            <span class="dcs-card-title">{{ row.title }}</span>
            <span class="fb-status" :class="`is-${(row.status || 'pending').toLowerCase()}`">{{ statusLabel(row.status) }}</span>
          </div>
          <p class="fb-mcard__excerpt">{{ row.content }}</p>
          <div class="dcs-card-time">{{ typeLabel(row.feedbackType) }} · {{ row.createByName || row.createBy }} · {{ row.createTime }}</div>
        </div>
      </div>

      <pagination v-show="total > 0" v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="load" />
    </el-card>

    <!-- 详情 / 处理抽屉 -->
    <el-drawer v-model="drawerVisible" :size="drawerSize" :title="drawerTitle" destroy-on-close>
      <div v-if="current" v-loading="detailLoading" class="fb-detail">
        <div class="fb-detail__head">
          <span class="fb-type" :class="`is-${(current.feedbackType || 'other').toLowerCase()}`">{{ typeLabel(current.feedbackType) }}</span>
          <span class="fb-status" :class="`is-${(current.status || 'pending').toLowerCase()}`">{{ statusLabel(current.status) }}</span>
          <span class="fb-detail__id">#{{ current.feedbackId }}</span>
        </div>
        <h2 class="fb-detail__title">{{ current.title }}</h2>
        <p class="fb-detail__meta">
          提交人 {{ current.createByName || current.createBy || '—' }} · {{ current.createTime }}
          <template v-if="current.contact"> · 联系方式 {{ current.contact }}</template>
        </p>

        <section class="fb-block">
          <h3 class="fb-block__title">问题描述</h3>
          <p class="fb-block__text">{{ current.content }}</p>
        </section>

        <section v-if="images.length" class="fb-block">
          <h3 class="fb-block__title">截图（{{ images.length }}）</h3>
          <div class="fb-images">
            <el-image
              v-for="img in images"
              :key="img.ossId"
              :src="img.url"
              :preview-src-list="imageUrls"
              :initial-index="imageUrls.indexOf(img.url)"
              fit="cover"
              class="fb-images__item"
              preview-teleported
            />
          </div>
        </section>

        <section v-if="contextEntries.length" class="fb-block">
          <h3 class="fb-block__title">提交环境</h3>
          <dl class="fb-context">
            <template v-for="entry in contextEntries" :key="entry.label">
              <dt>{{ entry.label }}</dt>
              <dd>{{ entry.value }}</dd>
            </template>
          </dl>
        </section>

        <section v-if="current.handleNote" class="fb-block">
          <h3 class="fb-block__title">上次处理</h3>
          <div class="fb-reply">
            <p class="fb-reply__who">{{ current.handlerName || '—' }} · {{ current.handleTime }}</p>
            <p class="fb-reply__text">{{ current.handleNote }}</p>
          </div>
        </section>

        <section v-if="canHandle" class="fb-block fb-handle">
          <h3 class="fb-block__title">处理这条反馈</h3>
          <el-form label-position="top">
            <el-form-item label="处理结果">
              <el-radio-group v-model="handleForm.status">
                <el-radio-button value="PROCESSING">处理中</el-radio-button>
                <el-radio-button value="RESOLVED">已解决</el-radio-button>
                <el-radio-button value="CLOSED">关闭</el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label="处理意见（会通知提交人，至少 5 个字）">
              <el-input
                v-model="handleForm.handleNote"
                type="textarea"
                :rows="4"
                maxlength="1000"
                show-word-limit
                placeholder="说明已定位到的原因、已做的处理或需要用户补充的信息"
              />
            </el-form-item>
          </el-form>
          <el-button type="primary" :loading="handling" @click="submitHandle">保存处理结果</el-button>
        </section>
        <p v-else class="fb-detail__readonly">当前账号没有处理权限，只能查看。</p>
      </div>
    </el-drawer>

    <feedback-dialog v-model:visible="dialogVisible" />
  </div>
</template>

<script setup name="DcsFeedback" lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus';
import { EditPen, Refresh, Search } from '@element-plus/icons-vue';
import { feedbackStatusSummary, getFeedback, handleFeedback, listFeedback } from '@/api/dcs/feedback';
import type { FeedbackRow, FeedbackStatus, FeedbackType } from '@/api/dcs/feedback/types';
import { listByIds } from '@/api/system/oss';
import type { OssVO } from '@/api/system/oss/types';
import { checkPermi } from '@/utils/permission';
import SupportPageHead from '@/views/dcs/support/components/SupportPageHead.vue';
import FeedbackDialog from './components/FeedbackDialog.vue';

const loading = ref(false);
const rows = ref<FeedbackRow[]>([]);
const total = ref(0);
const summary = ref<Record<string, number>>({});
const dialogVisible = ref(false);

const query = reactive({ pageNum: 1, pageSize: 10, title: '', feedbackType: '', status: '' });

const typeOptions: { label: string; value: FeedbackType }[] = [
  { label: '缺陷', value: 'BUG' },
  { label: '体验', value: 'EXPERIENCE' },
  { label: '建议', value: 'SUGGEST' },
  { label: '其它', value: 'OTHER' }
];
const statusOptions: { label: string; value: FeedbackStatus }[] = [
  { label: '待处理', value: 'PENDING' },
  { label: '处理中', value: 'PROCESSING' },
  { label: '已解决', value: 'RESOLVED' },
  { label: '已关闭', value: 'CLOSED' }
];
const statCards: { key: '' | FeedbackStatus; label: string; hint: string }[] = [
  { key: '', label: '全部', hint: '本租户累计' },
  { key: 'PENDING', label: '待处理', hint: '等待处理' },
  { key: 'PROCESSING', label: '处理中', hint: '正在处理' },
  { key: 'RESOLVED', label: '已解决', hint: '问题已修复' },
  { key: 'CLOSED', label: '已关闭', hint: '无需跟进' }
];

const TYPE_LABELS: Record<FeedbackType, string> = { BUG: '缺陷', EXPERIENCE: '体验', SUGGEST: '建议', OTHER: '其它' };
const STATUS_LABELS: Record<FeedbackStatus, string> = { PENDING: '待处理', PROCESSING: '处理中', RESOLVED: '已解决', CLOSED: '已关闭' };
const typeLabel = (type?: string) => TYPE_LABELS[(type as FeedbackType) || 'OTHER'] || '其它';
const statusLabel = (status?: string) => STATUS_LABELS[(status as FeedbackStatus) || 'PENDING'] || '待处理';

const totalAll = computed(() => Object.values(summary.value).reduce((sum, value) => sum + Number(value || 0), 0));
const hasFilter = computed(() => Boolean(query.title || query.feedbackType || query.status));

const drawerVisible = ref(false);
const drawerSize = computed(() => (window.innerWidth < 768 ? '100%' : '560px'));
const current = ref<FeedbackRow | null>(null);
const detailLoading = ref(false);
const images = ref<OssVO[]>([]);
const imageUrls = computed(() => images.value.map((img) => img.url));
const canHandle = computed(() => checkPermi(['dcs:feedback:handle']));
const drawerTitle = computed(() => (canHandle.value ? '反馈详情与处理' : '反馈详情'));
const handling = ref(false);
const handleForm = reactive({ status: 'PROCESSING' as Exclude<FeedbackStatus, 'PENDING'>, handleNote: '' });

const CONTEXT_LABELS: Record<string, string> = {
  route: '页面',
  title: '页面标题',
  ua: '浏览器',
  screen: '屏幕',
  lang: '语言',
  at: '提交时间'
};

/**
 * 上下文里的 at 是浏览器 ISO（UTC）；按全站口径显示 GMT+8 墙钟，
 * 避免与列表里的后端时间相差 8 小时被误读成数据错误。
 */
const formatGmt8 = (value: string) => {
  const time = new Date(value).getTime();
  if (Number.isNaN(time)) {
    return value;
  }
  const d = new Date(time + 8 * 3600 * 1000);
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}:${pad(d.getUTCSeconds())}`;
};
const contextEntries = computed(() => {
  const raw = current.value?.contextJson;
  if (!raw) {
    return [];
  }
  try {
    const parsed = JSON.parse(raw);
    return Object.keys(CONTEXT_LABELS)
      .filter((key) => parsed[key])
      .map((key) => ({ label: CONTEXT_LABELS[key], value: key === 'at' ? formatGmt8(parsed[key]) : String(parsed[key]) }));
  } catch {
    return [{ label: '原始信息', value: raw }];
  }
});

let loadSeq = 0;
const load = async () => {
  const currentSeq = ++loadSeq;
  loading.value = true;
  try {
    const res: any = await listFeedback({
      pageNum: query.pageNum,
      pageSize: query.pageSize,
      title: query.title || undefined,
      feedbackType: query.feedbackType || undefined,
      status: query.status || undefined
    });
    if (currentSeq !== loadSeq) {
      return; // 快速切筛选时旧响应不得覆盖新结果
    }
    rows.value = res.rows || [];
    total.value = Number(res.total || 0);
    void loadSummary();
  } finally {
    if (currentSeq === loadSeq) {
      loading.value = false;
    }
  }
};

const loadSummary = async () => {
  try {
    const res: any = await feedbackStatusSummary();
    summary.value = res.data || {};
  } catch {
    // 概览拉取失败时保留上一次快照，避免五个数全变 0 与列表打架
  }
};

const reload = () => {
  query.pageNum = 1;
  load();
};

const pickStatus = (status: '' | FeedbackStatus) => {
  query.status = query.status === status ? '' : status;
  reload();
};

const resetFilters = () => {
  query.title = '';
  query.feedbackType = '';
  query.status = '';
  reload();
};

let detailSeq = 0;
const openDetail = async (row: FeedbackRow, startHandle = false) => {
  const seq = ++detailSeq;
  drawerVisible.value = true;
  detailLoading.value = true;
  images.value = [];
  current.value = row;
  handleForm.status = row.status && row.status !== 'PENDING' ? (row.status as Exclude<FeedbackStatus, 'PENDING'>) : 'PROCESSING';
  handleForm.handleNote = row.handleNote || '';
  if (startHandle) {
    handleForm.handleNote = '';
  }
  try {
    const res: any = await getFeedback(row.feedbackId);
    if (seq !== detailSeq) {
      return; // 连点两行时，先发的慢响应不得覆盖后选中的一条
    }
    if (res.data) {
      current.value = res.data;
      handleForm.status = res.data.status && res.data.status !== 'PENDING' ? res.data.status : 'PROCESSING';
      handleForm.handleNote = startHandle ? '' : res.data.handleNote || '';
    }
    if (current.value?.ossIdList?.length) {
      const oss: any = await listByIds(current.value.ossIdList.join(','));
      if (seq !== detailSeq) {
        return;
      }
      images.value = oss.data || [];
    }
  } finally {
    if (seq === detailSeq) {
      detailLoading.value = false;
    }
  }
};

const submitHandle = async () => {
  if (!current.value) {
    return;
  }
  const note = handleForm.handleNote.trim();
  if (note.length < 5) {
    ElMessage.warning('处理意见至少 5 个字，方便提交人理解');
    return;
  }
  await ElMessageBox.confirm(`将状态改为「${statusLabel(handleForm.status)}」并通知提交人？`, '确认处理', { type: 'warning' });
  handling.value = true;
  try {
    await handleFeedback({ feedbackId: current.value.feedbackId, status: handleForm.status, handleNote: note });
    ElMessage.success('已保存，提交人会收到通知');
    drawerVisible.value = false;
    await load();
  } finally {
    handling.value = false;
  }
};

onMounted(load);
</script>

<style lang="scss" scoped>
.feedback-page {
  max-width: 1600px;
  margin: 0 auto;
}

/* ---------- 状态概览 ---------- */
.fb-stats {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  margin-bottom: 16px;
  border: 1px solid var(--dcs-border-color-lighter);
  border-radius: 16px;
  background: var(--el-bg-color);
  overflow: hidden;
}

.fb-stat {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  min-width: 0;
  padding: 15px 18px 14px;
  border: 0;
  border-right: 1px solid var(--dcs-border-color-lighter);
  border-radius: 0;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s ease;

  &::after {
    content: '';
    position: absolute;
    right: 0;
    bottom: 0;
    left: 0;
    height: 2px;
    background: transparent;
    transition: background 0.15s ease;
  }

  &:last-child {
    border-right: 0;
  }

  &:hover {
    background: var(--el-fill-color-lighter);
  }

  &.is-active {
    background: var(--el-color-primary-light-9);

    &::after {
      background: var(--el-color-primary);
    }

    .fb-stat__value {
      color: var(--el-color-primary);
    }
  }

  &__label {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: var(--dcs-text-secondary);
  }

  &__dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--dcs-text-placeholder);
  }

  &__value {
    font-size: 26px;
    line-height: 1.15;
    font-weight: 700;
    letter-spacing: -0.02em;
    font-variant-numeric: tabular-nums;
    color: var(--dcs-text-primary);
  }

  &__hint {
    font-size: 11.5px;
    color: var(--dcs-text-placeholder);
  }

  &.is-all .fb-stat__dot {
    background: var(--el-color-primary);
  }

  &.is-pending {
    .fb-stat__dot {
      background: var(--el-color-warning);
    }

    .fb-stat__value {
      color: var(--el-color-warning);
    }
  }

  &.is-processing .fb-stat__dot {
    background: var(--el-color-primary);
  }

  &.is-resolved {
    .fb-stat__dot {
      background: var(--el-color-success);
    }

    .fb-stat__value {
      color: var(--el-color-success);
    }
  }

  &.is-closed .fb-stat__dot {
    background: var(--dcs-text-placeholder);
  }
}

/* ---------- 筛选工具条 ---------- */
.fb-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border-radius: 14px;
  background: var(--el-fill-color-lighter);

  &__search {
    flex: 1 1 240px;
    max-width: 380px;
  }

  &__select {
    width: 136px;
  }

  > .el-button:first-of-type {
    margin-left: auto;
  }
}

/* ---------- 列表 ---------- */
.fb-card {
  border: 1px solid var(--dcs-border-color-lighter);
  border-radius: 16px;

  :deep(.el-card__body) {
    padding: 2px 16px 14px;
  }

  /* 表头底色/行高由全局 ruoyi.scss 的 !important 统一定义，这里只调字重与字色 */
  :deep(.el-table th.el-table__cell) {
    color: var(--dcs-text-secondary);
    font-size: 12.5px;
    font-weight: 600;
  }

  :deep(.el-table td.el-table__cell) {
    padding: 13px 0;
  }

  :deep(.el-table__row) {
    cursor: pointer;
  }

  :deep(.el-table__body tr:hover > td.el-table__cell) {
    background: var(--el-color-primary-light-9);
  }
}

.fb-cell {
  min-width: 0;

  &__line {
    display: flex;
    align-items: center;
    gap: 8px;
    min-width: 0;
  }

  &__title {
    flex: 1;
    min-width: 0;
    font-size: 14px;
    font-weight: 600;
    color: var(--dcs-text-primary);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__excerpt {
    margin: 5px 0 0;
    font-size: 12.5px;
    line-height: 1.6;
    color: var(--dcs-text-placeholder);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.fb-attach {
  flex: none;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--el-fill-color-light);
  font-size: 11px;
  line-height: 18px;
  color: var(--dcs-text-placeholder);
}

.fb-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 48px 16px 42px;
  text-align: center;

  &__title {
    margin: 0;
    font-size: 15px;
    font-weight: 600;
    color: var(--dcs-text-primary);
  }

  &__desc {
    margin: 0;
    max-width: 440px;
    font-size: 13px;
    line-height: 1.7;
    color: var(--dcs-text-secondary);
  }

  .el-button {
    margin-top: 6px;
    margin-left: 0;
  }

  &--mobile {
    width: 100%;
    /* 641–992 时全局列表是两列网格，空态必须整行占满 */
    grid-column: 1 / -1;
    padding: 32px 16px;
  }
}

.fb-type,
.fb-status {
  flex: none;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 18px;
  white-space: nowrap;
}

.fb-type {
  background: var(--el-fill-color-light);
  color: var(--dcs-text-secondary);

  &.is-bug {
    background: var(--el-color-danger-light-9);
    color: var(--el-color-danger);
  }

  &.is-suggest {
    background: var(--el-color-primary-light-9);
    color: var(--el-color-primary);
  }

  &.is-experience {
    background: var(--el-color-warning-light-9);
    color: var(--el-color-warning);
  }
}

.fb-status {
  &.is-pending {
    background: var(--el-color-warning-light-9);
    color: var(--el-color-warning);
  }

  &.is-processing {
    background: var(--el-color-primary-light-9);
    color: var(--el-color-primary);
  }

  &.is-resolved {
    background: var(--el-color-success-light-9);
    color: var(--el-color-success);
  }

  &.is-closed {
    background: var(--el-fill-color-light);
    color: var(--dcs-text-placeholder);
  }
}

.fb-mcard {
  &__excerpt {
    margin: 6px 0 0;
    font-size: 13px;
    line-height: 1.6;
    color: var(--dcs-text-secondary);
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    overflow: hidden;
  }
}

/* ---------- 抽屉 ---------- */
.fb-detail {
  padding-bottom: 20px;

  &__head {
    display: flex;
    align-items: center;
    gap: 7px;
  }

  &__id {
    margin-left: auto;
    font-size: 12px;
    color: var(--dcs-text-placeholder);
    font-variant-numeric: tabular-nums;
  }

  &__title {
    margin: 10px 0 6px;
    font-size: 19px;
    line-height: 1.45;
    font-weight: 650;
    color: var(--dcs-text-primary);
    overflow-wrap: anywhere;
  }

  &__meta {
    margin: 0;
    font-size: 12.5px;
    line-height: 1.7;
    color: var(--dcs-text-placeholder);
  }

  &__readonly {
    margin: 18px 0 0;
    padding: 10px 12px;
    border-radius: 8px;
    background: var(--el-fill-color-lighter);
    font-size: 12.5px;
    color: var(--dcs-text-placeholder);
  }
}

.fb-block {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--dcs-border-color-lighter);

  &__title {
    margin: 0 0 8px;
    font-size: 13px;
    font-weight: 600;
    color: var(--dcs-text-secondary);
  }

  &__text {
    margin: 0;
    font-size: 14px;
    line-height: 1.8;
    color: var(--dcs-text-regular);
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }
}

.fb-images {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;

  &__item {
    width: 96px;
    height: 96px;
    border-radius: 8px;
    border: 1px solid var(--dcs-border-color-lighter);
    cursor: zoom-in;
  }
}

.fb-context {
  display: grid;
  grid-template-columns: 88px minmax(0, 1fr);
  gap: 6px 12px;
  margin: 0;
  font-size: 12.5px;
  line-height: 1.65;

  dt {
    color: var(--dcs-text-placeholder);
  }

  dd {
    margin: 0;
    color: var(--dcs-text-regular);
    overflow-wrap: anywhere;
  }
}

.fb-reply {
  padding: 10px 12px;
  border-radius: 8px;
  background: var(--el-color-primary-light-9);
  border-left: 3px solid var(--el-color-primary-light-5);

  &__who {
    margin: 0;
    font-size: 12px;
    color: var(--el-color-primary);
  }

  &__text {
    margin: 5px 0 0;
    font-size: 13.5px;
    line-height: 1.75;
    color: var(--dcs-text-regular);
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }
}

.fb-handle {
  :deep(.el-form-item) {
    margin-bottom: 14px;
  }
}

/* ---------- 响应式 ---------- */
@media (max-width: 992px) {
  .fb-stats {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    gap: 10px;
    border: 0;
    background: transparent;
    overflow: visible;
  }

  .fb-stat {
    border: 1px solid var(--dcs-border-color-lighter);
    border-radius: 12px;

    &:last-child {
      border-right: 1px solid var(--dcs-border-color-lighter);
    }

    &::after {
      display: none;
    }
  }

  .fb-filters {
    &__search {
      flex-basis: 100%;
      width: 100%;
      max-width: none;
    }

    &__select {
      flex: 1;
      width: auto;
    }

    > .el-button:first-of-type {
      margin-left: 0;
    }
  }
}

@media (max-width: 768px) {
  .feedback-page {
    padding-left: 12px;
    padding-right: 12px;
  }
}

@media (max-width: 640px) {
  .fb-stats {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .fb-stat {
    padding: 10px 12px;

    &:last-child {
      grid-column: auto;
    }

    &__value {
      font-size: 20px;
    }
  }

  .fb-filters {
    padding: 8px;

    &__select {
      flex: 0 1 calc(50% - 4px);
      width: auto;
    }

    > .el-button {
      flex: 1;
      margin-left: 0;
    }
  }

  .fb-card {
    :deep(.el-card__body) {
      padding: 0 10px 10px;
    }
  }
}
</style>
