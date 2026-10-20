<template>
  <div class="app-container">
    <dcs-operations-page-header
      title="我的消息"
      subtitle="任务分派、审核结果与系统提醒都在这里"
      eyebrow="任务中心"
      :metrics="[
        { label: '消息总数', value: total },
        { label: '本页未读', value: unreadCount, tone: 'warning' }
      ]"
    >
      <template #actions>
        <el-radio-group v-model="query.readFlag" @change="load">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="0">未读</el-radio-button>
          <el-radio-button value="1">已读</el-radio-button>
        </el-radio-group>
      </template>
    </dcs-operations-page-header>

    <el-card shadow="never">
      <el-table class="dcs-table-only" v-loading="loading" :data="rows" @row-click="read">
        <el-table-column label="标题" min-width="260">
          <template #default="{ row }">
            <span :style="row.readFlag === '0' ? 'font-weight: 600' : ''"> {{ typeBadge(row.noticeType) }} {{ row.title }} </span>
          </template>
        </el-table-column>
        <el-table-column label="内容" min-width="280">
          <template #default="{ row }">{{ row.content }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag size="small" :type="row.readFlag === '0' ? 'warning' : 'info'">{{ row.readFlag === '0' ? '未读' : '已读' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="时间" width="170" />
      </el-table>
      <!-- ≤992：表格改卡片；点卡片=标为已读（与行点击同口径） -->
      <div class="dcs-mobile-list">
        <el-empty v-if="rows.length === 0" description="暂无数据" :image-size="64" />
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
            <span class="dcs-card-title" :style="row.readFlag === '0' ? 'font-weight: 700' : ''"
              >{{ typeBadge(row.noticeType) }} {{ row.title }}</span
            >
            <span class="dcs-card-badge">
              <el-tag size="small" :type="row.readFlag === '0' ? 'warning' : 'info'">{{ row.readFlag === '0' ? '未读' : '已读' }}</el-tag>
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
.notice-card {
  cursor: pointer;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
