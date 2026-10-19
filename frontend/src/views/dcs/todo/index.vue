<template>
  <div class="app-container">
    <el-card shadow="never">
      <template #header>
        <span>我的待办（未完成填报的任务）</span>
      </template>
      <el-table v-loading="loading" :data="rows">
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
      <pagination v-show="total > 0" v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="load" />
    </el-card>
  </div>
</template>

<script setup name="DcsTodo" lang="ts">
import { myTasks } from '@/api/dcs/core/task';
import type { DcsTaskRow } from '@/api/dcs/core/task';

const router = useRouter();

const loading = ref(false);
const rows = ref<(DcsTaskRow & { myStatus: string })[]>([]);
const total = ref(0);
const query = reactive<any>({ pageNum: 1, pageSize: 20 });

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
  } finally { loading.value = false; }
};

onMounted(load);
</script>
