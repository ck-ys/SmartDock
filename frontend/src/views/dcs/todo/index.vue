<template>
  <div class="app-container">
    <dcs-operations-page-header
      title="我的待办"
      subtitle="只显示还没提交、或被退回需要重提的任务"
      eyebrow="任务中心"
      :metrics="[
        { label: '待办任务', value: total },
        { label: '本页被退回', value: rejectedCount, tone: 'danger' }
      ]"
    />

    <el-card shadow="never">
      <el-table class="dcs-table-only" v-loading="loading" :data="rows">
        <el-table-column label="任务" min-width="240">
          <template #default="{ row }">
            <el-link type="primary" @click="goFill(row)">{{ row.title }}</el-link>
          </template>
        </el-table-column>
        <el-table-column label="我的状态" width="130">
          <template #default="{ row }">
            <el-tag :type="row.myStatus === '未提交' ? 'info' : 'danger'">{{ row.myStatus }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="deadline" label="截止" width="120">
          <template #default="{ row }">
            <span :style="overdue(row) ? 'color: var(--el-color-danger)' : ''">{{ row.deadline || '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="publishTime" label="发布时间" width="170" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goFill(row)">去填报</el-button>
          </template>
        </el-table-column>
      </el-table>
      <!-- ≤992：表格改卡片 -->
      <div class="dcs-mobile-list">
        <el-empty v-if="rows.length === 0" description="暂无数据" :image-size="64" />
        <div v-for="row in rows" :key="row.id" class="dcs-mobile-card">
          <div class="dcs-card-head">
            <span class="dcs-card-title" @click="goFill(row)">{{ row.title }}</span>
            <span class="dcs-card-badge"
              ><el-tag :type="row.myStatus === '未提交' ? 'info' : 'danger'">{{ row.myStatus }}</el-tag></span
            >
          </div>
          <div class="dcs-card-meta">
            <span :style="overdue(row) ? 'color: var(--el-color-danger)' : ''">截止 {{ row.deadline || '—' }}</span>
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
const query = reactive<any>({ pageNum: 1, pageSize: 20 });
const rejectedCount = computed(() => rows.value.filter((r) => r.mySubmissionStatus === 'REJECTED').length);

// 被退回的跳转带上 action=revise-reason, 详情页据此高亮退回意见(全审 P1-4)
const goFill = (row: DcsTaskRow) =>
  router.push(`/dcs/mission/detail/${row.id}${row.mySubmissionStatus === 'REJECTED' ? '?action=revise-reason' : ''}`);
const overdue = (row: any) => row.deadline && new Date(row.deadline) < new Date(new Date().toDateString());

const load = async () => {
  loading.value = true;
  try {
    const res = await myTasks(query);
    // 后端 my 已回填 mySubmissionStatus(回修A), 本页只留「未提交/被退回」, 不再逐任务 getTask(全审 P1: N+1)
    rows.value = (res.rows || [])
      .filter((t) => t.status === 'COLLECTING' && (!t.mySubmissionStatus || t.mySubmissionStatus === 'REJECTED'))
      .map((t) => ({ ...t, myStatus: t.mySubmissionStatus === 'REJECTED' ? '被退回(可重提)' : '未提交' }));
    total.value = res.total;
  } finally {
    loading.value = false;
  }
};

onMounted(load);
</script>
