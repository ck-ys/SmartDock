<template>
  <div class="app-container home">
    <el-card shadow="never">
      <div class="hello">
        <div>
          <div class="date">{{ today }}</div>
          <h1>{{ greeting }}，{{ userStore.name }}</h1>
        </div>
        <el-button icon="Refresh" circle @click="load" />
      </div>
    </el-card>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col v-if="isPublisher" :span="12">
        <el-card shadow="never">
          <template #header><span>我发布的任务</span></template>
          <div class="stat-row">
            <div class="stat"><div class="num">{{ stats?.publisher?.draft ?? 0 }}</div><div class="lbl">草稿</div></div>
            <div class="stat"><div class="num" style="color: var(--el-color-success)">{{ stats?.publisher?.collecting ?? 0 }}</div><div class="lbl">收集中</div></div>
            <div class="stat"><div class="num" style="color: var(--el-color-info)">{{ stats?.publisher?.closed ?? 0 }}</div><div class="lbl">已关闭</div></div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="isPublisher ? 12 : 24">
        <el-card shadow="never">
          <template #header><span>我的填报</span></template>
          <div class="stat-row">
            <div class="stat"><div class="num" style="color: var(--el-color-warning)">{{ stats?.student?.todo ?? 0 }}</div><div class="lbl">待完成</div></div>
            <div class="stat"><div class="num">{{ stats?.student?.pendingAudit ?? 0 }}</div><div class="lbl">待审核</div></div>
            <div class="stat"><div class="num" style="color: var(--el-color-success)">{{ stats?.student?.approved ?? 0 }}</div><div class="lbl">已通过</div></div>
            <div class="stat"><div class="num" style="color: var(--el-color-danger)">{{ stats?.student?.rejected ?? 0 }}</div><div class="lbl">被退回</div></div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" style="margin-top: 16px">
      <template #header><span>快捷入口</span></template>
      <div class="quick">
        <el-button v-if="isPublisher" size="large" @click="go('/dcs/mission')">任务管理</el-button>
        <el-button v-if="isPublisher" size="large" @click="go('/dcs/audit')">审核队列</el-button>
        <el-button size="large" @click="go('/dcs/todo')">我的待办</el-button>
        <el-button size="large" @click="go('/dcs/notice')">消息<span v-if="unread" class="badge">{{ unread }}</span></el-button>
        <el-button size="large" @click="go('/dcs/dashboard')">数据看板</el-button>
        <el-button size="large" @click="go('/dcs/profile')">用户画像</el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup name="Index" lang="ts">
import { statsOverview, type DcsStatsOverview } from '@/api/dcs/core/task';
import { useUserStore } from '@/store/modules/user';

const userStore = useUserStore();
const router = useRouter();

const stats = ref<DcsStatsOverview | null>(null);
const unread = computed(() => stats.value?.student?.unreadNotices ?? 0);
const isPublisher = computed(() => userStore.permissions?.includes('dcs:task:list') || userStore.permissions?.includes('*:*:*'));
const today = new Date().toLocaleDateString('zh-CN', { year: 'numeric', month: 'long', day: 'numeric', weekday: 'long' });
const greeting = new Date().getHours() < 12 ? '早上好' : new Date().getHours() < 18 ? '下午好' : '晚上好';

const go = (path: string) => router.push(path);
const load = async () => {
  stats.value = await statsOverview().catch(() => null);
};

onMounted(load);
</script>

<style scoped>
.hello { display: flex; justify-content: space-between; align-items: center; }
.date { color: var(--el-text-color-secondary); margin-bottom: 6px; }
h1 { margin: 0; font-size: 22px; }
.stat-row { display: flex; gap: 28px; }
.stat { text-align: center; }
.num { font-size: 30px; font-weight: 600; }
.lbl { color: var(--el-text-color-secondary); font-size: 13px; }
.quick { display: flex; flex-wrap: wrap; gap: 12px; }
.badge { margin-left: 4px; background: var(--el-color-danger); color: #fff; border-radius: 9px; padding: 0 6px; font-size: 12px; }
</style>
