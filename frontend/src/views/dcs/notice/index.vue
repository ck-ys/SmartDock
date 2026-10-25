<template>
  <div class="app-container">
    <dcs-operations-page-header
      title="我的消息"
      subtitle="任务分派、审核结果与系统提醒都在这里"
      eyebrow="任务中心"
      :metrics="[
        { label: '消息总数', value: total },
        { label: '本页未读', value: unreadCount }
      ]"
    />

    <el-card shadow="never" class="notice-list-card">
      <div class="ui-toolbar notice-toolbar">
        <el-radio-group v-model="query.readFlag" @change="load">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="0">未读</el-radio-button>
          <el-radio-button value="1">已读</el-radio-button>
        </el-radio-group>
      </div>

      <div class="dcs-ops-table-wrap">
        <el-table v-loading="loading" class="dcs-table-only ui-table notice-table" :data="rows" @row-click="read">
          <el-table-column label="标题" min-width="260">
            <template #default="{ row }">
              <span class="notice-cell notice-cell--title" :class="{ 'is-unread': row.readFlag === '0' }">
                {{ typeBadge(row.noticeType) }} {{ row.title }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="内容" min-width="280">
            <template #default="{ row }">
              <span class="notice-cell">{{ row.content }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">
              <span class="ui-pill" :class="readPill(row)">{{ row.readFlag === '0' ? '未读' : '已读' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="时间" width="170" />
          <template #empty>
            <div class="ui-empty">
              <p class="ui-empty__title">暂无消息</p>
              <p class="ui-empty__hint">任务分派、审核结果与系统提醒都会显示在这里。</p>
            </div>
          </template>
        </el-table>
      </div>
      <!-- ≤992：表格改卡片；点卡片=标为已读（与行点击同口径） -->
      <div class="dcs-mobile-list notice-mobile">
        <div v-if="rows.length === 0" class="ui-empty">
          <p class="ui-empty__title">暂无消息</p>
          <p class="ui-empty__hint">任务分派、审核结果与系统提醒都会显示在这里。</p>
        </div>
        <div
          v-for="row in rows"
          :key="row.id"
          class="dcs-mobile-card notice-card"
          role="button"
          tabindex="0"
          @click="read(row)"
          @keydown.enter.prevent="read(row)"
          @keydown.space.prevent="read(row)"
        >
          <div class="dcs-card-head">
            <span class="dcs-card-title" :class="{ 'is-unread': row.readFlag === '0' }">{{ typeBadge(row.noticeType) }} {{ row.title }}</span>
            <span class="dcs-card-badge">
              <span class="ui-pill" :class="readPill(row)">{{ row.readFlag === '0' ? '未读' : '已读' }}</span>
            </span>
          </div>
          <div class="dcs-card-field">
            <span class="dcs-card-field__value">{{ row.content }}</span>
          </div>
          <div class="dcs-card-time">{{ row.createTime }}</div>
        </div>
      </div>

      <pagination v-show="total > 0" v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="load" />
    </el-card>
  </div>
</template>

<script setup name="DcsNotice" lang="ts">
import { myNotices, markNoticeRead, type DcsNoticeRow } from '@/api/dcs/core/notice';
import { useNoticeStore } from '@/store/modules/notice';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';

const noticeStore = useNoticeStore();

const loading = ref(false);
const rows = ref<DcsNoticeRow[]>([]);
const total = ref(0);
const query = reactive<any>({ pageNum: 1, pageSize: 20, readFlag: '' });
const unreadCount = computed(() => rows.value.filter((r) => r.readFlag === '0').length);

const TYPE_LABEL: Record<string, string> = {
  TASK_PUBLISHED: '[新任务]',
  SUBMITTED: '[新提交]',
  AUDIT_RESULT: '[审核结果]',
  FILE_REVIEW_REJECT: '[文件驳回]',
  WALLET_LOW_BALANCE: '[余额]',
  WALLET_EXHAUSTED: '[余额]',
  WALLET_RECONCILE_MISMATCH: '[对账]',
  CREDIT_SUBSCRIPTION_EXPIRING: '[订阅]',
  CREDIT_WEEK_EXHAUSTED: '[额度]',
  CREDIT_TANK_LOW: '[额度]'
};
const typeBadge = (t: string) => TYPE_LABEL[t] || '[通知]';
const readPill = (row: DcsNoticeRow) => (row.readFlag === '0' ? 'ui-pill--warn' : 'ui-pill--muted');

const load = async () => {
  loading.value = true;
  try {
    const res = await myNotices({ ...query, readFlag: query.readFlag || undefined });
    rows.value = res.rows;
    total.value = res.total;
  } finally {
    loading.value = false;
  }
};

const read = async (row: DcsNoticeRow) => {
  if (row.readFlag === '0') {
    await markNoticeRead(row.id);
    row.readFlag = '1';
    // 本页标读与铃铛是两套状态, 顺手校准避免数字悬停(全审 P2)
    noticeStore.refreshUnreadCount().catch(() => undefined);
  }
};

onMounted(load);
</script>

<style scoped>
/* 筛选条与表格同卡：卡体不留内边距，由 .ui-toolbar 自己管 */
.notice-list-card :deep(.el-card__body) {
  min-width: 0;
  padding: 0;
}

.notice-table {
  --el-table-header-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-border-color: var(--app-rule);
  --el-table-row-hover-bg-color: var(--app-raised);
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
}

/* 单行截断：消息正文再长也保持 44px 行高，完整内容进详情/移动卡片 */
.notice-cell {
  display: block;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 主体列（消息标题）用主文字色，未读再加重 */
.notice-cell--title {
  color: var(--app-text);
  font-weight: 500;
}

.notice-table .is-unread {
  font-weight: 600;
}

.notice-card {
  cursor: pointer;
}

.notice-card .is-unread {
  font-weight: 700;
}

.notice-mobile {
  padding: 12px 16px 0;
}
</style>
