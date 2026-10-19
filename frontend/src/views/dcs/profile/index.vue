<template>
  <div class="app-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>用户画像（事件源引擎）</span>
          <el-select v-model="source" style="width: 140px" @change="load">
            <el-option label="dcs 数据采集" value="dcs" />
            <el-option label="risk 风控" value="risk" />
          </el-select>
        </div>
      </template>
      <el-table v-loading="loading" :data="rows">
        <el-table-column prop="subjectId" label="主体 ID" min-width="150" />
        <el-table-column label="名称" min-width="120">
          <template #default="{ row }">{{ row.displayName || '—' }}</template>
        </el-table-column>
        <el-table-column prop="eventCount" label="事件数" width="90" />
        <el-table-column prop="firstEventTime" label="首事件" width="170" />
        <el-table-column prop="lastEventTime" label="最近事件" width="170" />
        <el-table-column label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDetail(row)">看画像</el-button>
          </template>
        </el-table-column>
      </el-table>
      <pagination v-show="total > 0" v-model:page="query.pageNum" v-model:limit="query.pageSize" :total="total" @pagination="load" />
    </el-card>
  </div>
</template>

<script setup name="DcsProfile" lang="ts">
import { pageSubjects, type ProfileSubjectRow } from '@/api/dcs/core/profile';

const router = useRouter();
const loading = ref(false);
const rows = ref<ProfileSubjectRow[]>([]);
const total = ref(0);
const source = ref('dcs');
const query = reactive<any>({ pageNum: 1, pageSize: 20 });

const goDetail = (row: ProfileSubjectRow) =>
  router.push(`/dcs/profile-detail/${row.source}/${row.subjectId}?subjectType=${encodeURIComponent(row.subjectType || 'user')}`);
const load = async () => {
  loading.value = true;
  try {
    const res = await pageSubjects({ ...query, source: source.value });
    rows.value = res.rows;
    total.value = res.total;
  } finally { loading.value = false; }
};

onMounted(load);
</script>

<style scoped>
.card-header { display: flex; justify-content: space-between; align-items: center; }
</style>
