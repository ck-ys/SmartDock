<template>
  <div class="app-container">
    <dcs-operations-page-header
      title="我的待办"
      subtitle="只显示还没提交、或被退回需要重提的任务"
      eyebrow="任务中心"
      :metrics="[
        { label: '待办任务', value: total },
        { label: '本页被退回', value: rejectedCount }
      ]"
    />

    <el-card shadow="never" class="todo-card">
      <div class="dcs-ops-table-wrap">
        <el-table v-loading="loading" class="dcs-table-only ui-table todo-table" :data="rows">
          <el-table-column label="任务" min-width="240">
            <template #default="{ row }">
              <el-link class="todo-title-link" @click="goFill(row)">{{ row.title }}</el-link>
            </template>
          </el-table-column>
          <el-table-column label="我的状态" width="130">
            <template #default="{ row }">
              <span class="ui-pill" :class="myStatusPill(row)">{{ row.myStatus }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="deadline" label="截止" width="120">
            <template #default="{ row }">
              <span :class="{ 'is-overdue': overdue(row) }">{{ row.deadline || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="publishTime" label="发布时间" width="170" />
          <el-table-column label="操作" width="110" fixed="right">
            <template #default="{ row }">
              <el-button link type="primary" @click="goFill(row)">去填报</el-button>
            </template>
          </el-table-column>
          <template #empty>
            <div class="ui-empty">
              <p class="ui-empty__title">暂无待办</p>
              <p class="ui-empty__hint">当前没有需要你填报的任务，有新任务会出现在这里。</p>
            </div>
          </template>
        </el-table>
      </div>
      <!-- ≤992：表格改卡片 -->
      <div class="dcs-mobile-list">
        <div v-if="rows.length === 0" class="ui-empty">
          <p class="ui-empty__title">暂无待办</p>
          <p class="ui-empty__hint">当前没有需要你填报的任务，有新任务会出现在这里。</p>
        </div>
        <div v-for="row in rows" :key="row.id" class="dcs-mobile-card">
          <div class="dcs-card-head">
            <span class="dcs-card-title" @click="goFill(row)">{{ row.title }}</span>
            <span class="dcs-card-badge"
              ><span class="ui-pill" :class="myStatusPill(row)">{{ row.myStatus }}</span></span
            >
          </div>
          <div class="dcs-card-meta">
            <span :class="{ 'is-overdue': overdue(row) }">截止 {{ row.deadline || '—' }}</span>
            <span>发布于 {{ row.publishTime || '—' }}</span>
          </div>
          <div class="dcs-card-foot">
            <div class="dcs-card-actions">
              <el-button link type="primary" @click="goFill(row)">去填报</el-button>
            </div>
          </div>
        </div>
      </div>

      <pagination v-show="total > 0" v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="load" />
    </el-card>
  </div>
</template>

<script setup name="DcsTodo" lang="ts">
import { myTasks } from '@/api/dcs/core/task';
import type { DcsTaskRow } from '@/api/dcs/core/task';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';

const router = useRouter();

const loading = ref(false);
const rows = ref<(DcsTaskRow & { myStatus: string })[]>([]);
const total = ref(0);
const query = reactive<any>({ pageNum: 1, pageSize: 20, todoOnly: true });
const rejectedCount = computed(() => rows.value.filter((r) => r.mySubmissionStatus === 'REJECTED').length);

// 被退回的跳转带上 action=revise-reason, 详情页据此高亮退回意见(全审 P1-4)
const goFill = (row: DcsTaskRow) =>
  router.push(`/dcs/mission/detail/${row.id}${row.mySubmissionStatus === 'REJECTED' ? '?action=revise-reason' : ''}`);
const overdue = (row: any) => row.deadline && new Date(row.deadline) < new Date(new Date().toDateString());
const myStatusPill = (row: DcsTaskRow & { myStatus: string }) => (row.mySubmissionStatus === 'REJECTED' ? 'ui-pill--neg' : 'ui-pill--muted');

const load = async () => {
  loading.value = true;
  try {
    const res = await myTasks(query);
    // 待办口径(未提交/被退回)由后端 todoOnly 服务端过滤(U-03 口径对齐): 行列表与 total 同口径,
    // 不再前端滤行而计数不滤——否则 KPI/分页说有 N 条、表体却是空态。后端 my 已回填 mySubmissionStatus(回修A)。
    rows.value = (res.rows || []).map((t) => ({
      ...t,
      myStatus: t.mySubmissionStatus === 'REJECTED' ? '被退回(可重提)' : '未提交'
    }));
    total.value = res.total;
  } finally {
    loading.value = false;
  }
};

onMounted(load);
</script>

<style scoped>
.todo-card :deep(.el-card__body) {
  min-width: 0;
  /* P6(2026-10-27 拍板B)：表格贴边、去掉卡片内缩，与反馈中心口径归一；overflow 裁直角跟卡片圆角 */
  padding: 0;
  overflow: hidden;
}

.todo-table {
  --el-table-header-bg-color: transparent;
  --el-table-header-text-color: var(--app-faint);
  --el-table-text-color: var(--app-muted);
  --el-table-border-color: var(--app-rule);
  --el-table-row-hover-bg-color: var(--app-raised);
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  /* 空态时表格根自带的上/左半框（右/下无线）呈「卡中框」假象，贴边后清掉，只留行分隔线 */
  border: none;
  border-radius: 0;
}

.todo-title-link {
  color: var(--app-text);
  font-weight: 500;
}

.todo-title-link:hover {
  color: var(--app-muted);
}

.is-overdue {
  color: var(--dcs-pill-neg-ink);
  font-variant-numeric: tabular-nums;
}
</style>
