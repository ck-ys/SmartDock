<template>
  <div class="app-container">
    <dcs-operations-page-header
      title="数据看板"
      subtitle="任务采集的发布与填报总量（内置示例能力的统计）"
      eyebrow="任务中心"
      :metrics="[
        { label: '我发布的', value: (stats?.publisher?.draft ?? 0) + (stats?.publisher?.collecting ?? 0) + (stats?.publisher?.closed ?? 0) },
        { label: '待我完成', value: stats?.member?.todo ?? 0, tone: 'warning' },
        { label: '已通过', value: stats?.member?.approved ?? 0, tone: 'success' }
      ]"
    />

    <el-row :gutter="16">
      <el-col v-if="isPublisher" :span="12">
        <el-card shadow="never">
          <template #header><span>我发布的任务</span></template>
          <div class="stat-row">
            <div class="stat">
              <div class="num">{{ stats?.publisher?.draft ?? 0 }}</div>
              <div class="lbl">草稿</div>
            </div>
            <div class="stat">
              <div class="num" style="color: var(--el-color-success)">{{ stats?.publisher?.collecting ?? 0 }}</div>
              <div class="lbl">收集中</div>
            </div>
            <div class="stat">
              <div class="num" style="color: var(--el-color-info)">{{ stats?.publisher?.closed ?? 0 }}</div>
              <div class="lbl">已关闭</div>
            </div>
          </div>
          <el-button style="margin-top: 12px" @click="go('/dcs/mission')">任务管理</el-button>
          <el-button style="margin-top: 12px" @click="go('/dcs/audit')">审核队列</el-button>
        </el-card>
      </el-col>
      <el-col :span="isPublisher ? 12 : 24">
        <el-card shadow="never">
          <template #header><span>我的填报</span></template>
          <div class="stat-row">
            <div class="stat">
              <div class="num" style="color: var(--el-color-warning)">{{ stats?.member?.todo ?? 0 }}</div>
              <div class="lbl">待完成</div>
            </div>
            <div class="stat">
              <div class="num">{{ stats?.member?.pendingAudit ?? 0 }}</div>
              <div class="lbl">待审核</div>
            </div>
            <div class="stat">
              <div class="num" style="color: var(--el-color-success)">{{ stats?.member?.approved ?? 0 }}</div>
              <div class="lbl">已通过</div>
            </div>
            <div class="stat">
              <div class="num" style="color: var(--el-color-danger)">{{ stats?.member?.rejected ?? 0 }}</div>
              <div class="lbl">被退回</div>
            </div>
          </div>
          <el-button style="margin-top: 12px" @click="go('/dcs/todo')">我的待办</el-button>
          <el-button style="margin-top: 12px" @click="go('/dcs/notice')">
            消息<span v-if="stats?.member?.unreadNotices" class="badge">{{ stats.member.unreadNotices }}</span>
          </el-button>
        </el-card>
      </el-col>
    </el-row>
    <el-card shadow="never" style="margin-top: 16px">
      <template #header><span>口径说明</span></template>
      <p class="tip">
        统计来自服务端全量分组（/dcs/stats/overview）：发布人视角按任务状态计数；成员视角按本人提交状态计数。发布人若无 dcs:task:list
        权限则只显示成员视角。
      </p>
    </el-card>
  </div>
</template>

<script setup name="DcsDashboard" lang="ts">
import { statsOverview, type DcsStatsOverview } from '@/api/dcs/core/task';
import { useUserStore } from '@/store/modules/user';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';

const userStore = useUserStore();
const router = useRouter();
const stats = ref<DcsStatsOverview | null>(null);

const isPublisher = computed(() => userStore.permissions?.includes('dcs:task:list') || userStore.permissions?.includes('*:*:*'));
const go = (path: string) => router.push(path);

// 成员/无任务权限角色也能进看板, 统计失败不弹错(全审 P2)
onMounted(async () => {
  stats.value = await statsOverview().catch(() => null);
});
</script>

<style scoped>
.stat-row {
  display: flex;
  gap: 28px;
}
.stat {
  text-align: center;
}
.num {
  font-size: 30px;
  font-weight: 600;
}
.lbl {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.badge {
  margin-left: 4px;
  background: var(--el-color-danger);
  /* 深色下 danger 是浅红，白字读不清；--el-color-white 在深色已取反成黑 */
  color: var(--el-color-white);
  border-radius: 9px;
  padding: 0 6px;
  font-size: 12px;
}
.tip {
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
</style>
