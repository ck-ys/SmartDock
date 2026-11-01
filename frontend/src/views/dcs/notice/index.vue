<template>
  <div class="app-container">
    <dcs-operations-page-header
      title="我的消息"
      subtitle="任务分派、审核结果与系统提醒都在这里"
      eyebrow="任务中心"
      :metrics="headerMetrics"
    />

    <el-card shadow="never" class="notice-list-card">
      <div class="ui-toolbar notice-toolbar">
        <el-radio-group v-model="query.readFlag" :disabled="markingAll" @change="load">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="0">未读</el-radio-button>
          <el-radio-button value="1">已读</el-radio-button>
        </el-radio-group>
        <!-- 2026-10-30 题11（方案A）：批量出口显式化；只作用本页未读（按钮文案已写明「本页」），量大再换批量接口 -->
        <el-button plain icon="Check" title="仅标记当前页的未读消息" :disabled="unreadCount === 0 || markingAll" :loading="markingAll" @click="markAllRead">本页全部已读</el-button>
      </div>

      <div class="dcs-ops-table-wrap">
        <el-table v-loading="loading" class="dcs-table-only ui-table notice-table" :data="rows">
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
          <!-- 2026-10-30 题11（方案A）：标已是显式动作，不再绑在整行点击上（避免误触把没看的标掉） -->
          <el-table-column label="操作" width="90" align="right">
            <template #default="{ row }">
              <el-button v-if="row.readFlag === '0'" link type="primary" :disabled="markingAll" @click.stop="read(row)">标已读</el-button>
              <span v-else class="notice-read-done">已读</span>
            </template>
          </el-table-column>
          <template #empty>
            <div class="ui-empty">
              <p class="ui-empty__title">{{ hasFilter ? '没有符合条件的消息' : '暂无消息' }}</p>
              <p class="ui-empty__hint">
                {{ hasFilter ? `当前筛选 0 条；全部消息还有 ${totalAll} 条。` : '任务分派、审核结果与系统提醒都会显示在这里。' }}
              </p>
              <el-button v-if="hasFilter" type="primary" plain @click="clearFilter">清空筛选，看全部</el-button>
              <el-button v-else plain @click="load">刷新</el-button>
            </div>
          </template>
        </el-table>
      </div>
      <!-- ≤992：表格改卡片；标读同样改成显式按钮 -->
      <div class="dcs-mobile-list notice-mobile">
        <div v-if="rows.length === 0" class="ui-empty">
          <p class="ui-empty__title">{{ hasFilter ? '没有符合条件的消息' : '暂无消息' }}</p>
          <p class="ui-empty__hint">
            {{ hasFilter ? `当前筛选 0 条；全部消息还有 ${totalAll} 条。` : '任务分派、审核结果与系统提醒都会显示在这里。' }}
          </p>
          <el-button v-if="hasFilter" type="primary" plain @click="clearFilter">清空筛选，看全部</el-button>
          <el-button v-else plain @click="load">刷新</el-button>
        </div>
        <div v-for="row in rows" :key="row.id" class="dcs-mobile-card notice-card">
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
          <div v-if="row.readFlag === '0'" class="dcs-card-foot">
            <el-button link type="primary" :disabled="markingAll" @click.stop="read(row)">标已读</el-button>
          </div>
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

/* 2026-11-01 Q6=乙：「消息总数」必须是全量，不能跟着筛选走。
   筛选态下另起一次 pageSize=1 的请求取全量数（列表接口的 total 是筛选后的），
   筛选结果单独显示为「当前筛选」一个数。 */
const totalAll = ref(0);
const hasFilter = computed(() => !!query.readFlag);
const headerMetrics = computed(() => {
  const list: Array<{ label: string; value: number }> = [{ label: '消息总数', value: totalAll.value }];
  if (hasFilter.value) list.push({ label: '当前筛选', value: total.value });
  list.push({ label: '本页未读', value: unreadCount.value });
  return list;
});
const loadAllTotal = async () => {
  const res = await myNotices({ pageNum: 1, pageSize: 1 });
  totalAll.value = Number(res.total) || 0;
};
const clearFilter = () => {
  query.readFlag = '';
  query.pageNum = 1;
  load();
};

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
  // 全量数只在筛选态下才会被看到；首次进来也取一次，保证「消息总数」一上来就是全量
  if (hasFilter.value || !totalAll.value) await loadAllTotal().catch(() => undefined);
};

const read = async (row: DcsNoticeRow) => {
  if (row.readFlag !== '0') return;
  try {
    await markNoticeRead(row.id);
    row.readFlag = '1';
    // 本页标读与铃铛是两套状态, 顺手校准避免数字悬停(全审 P2)
    noticeStore.refreshUnreadCount().catch(() => undefined);
  } catch {
    // 请求层已弹错误提示；这里吞掉避免重复点击产生未捕获 rejection（批1 深挖席 P2-1）
  }
};

/** 2026-10-30 题11（方案A）：本页未读一键清零。逐条走既有单条接口，失败计数后提示，不假装成功。 */
const markingAll = ref(false);
const markAllRead = async () => {
  const unread = rows.value.filter((r) => r.readFlag === '0');
  if (!unread.length) return;
  markingAll.value = true;
  let failed = 0;
  try {
    for (const row of unread) {
      try {
        await markNoticeRead(row.id);
        row.readFlag = '1';
      } catch {
        failed += 1;
      }
    }
    // 列表按「未读优先」排序，重载后本页会补上后面的未读——不报一句数，用户会以为没生效（批1 复验）
    if (failed === 0) ElMessage.success(`已标记 ${unread.length} 条为已读`);
  } finally {
    markingAll.value = false;
    noticeStore.refreshUnreadCount().catch(() => undefined);
    if (failed > 0) ElMessage.warning(`${failed} 条未能标为已读，请稍后重试`);
    // 收尾重载：未读筛选下已读项应即时消失，total 与本页未读同步（glm53 P1-1 / dspro P2-2）
    await load();
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

.notice-card .is-unread {
  font-weight: 700;
}

.notice-mobile {
  padding: 12px 16px 0;
}

/* 2026-10-30 题11：筛选与「全部已读」同一行，批量出口靠右 */
.notice-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.notice-read-done {
  color: var(--app-faint);
  font-size: 12px;
}
</style>
