<template>
  <div class="app-container dashboard-page">
    <dcs-operations-page-header title="数据看板" subtitle="任务采集的发布与填报总量（内置示例能力的统计）" eyebrow="任务中心">
      <template #actions>
        <el-button :icon="Refresh" :loading="refreshing" @click="load">刷新</el-button>
      </template>
    </dcs-operations-page-header>

    <!-- 取数失败时说清楚，别让 8 个格子一起显示 0——0 是真实业务值，和「没取到」是两回事 -->
    <p v-if="statsError" class="ui-error">统计暂时取不到，点右上角刷新重试。</p>

    <div class="ui-kpi-strip dashboard-kpi">
      <template v-if="isPublisher">
        <div class="ui-kpi">
          <span class="ui-kpi__label">我发布的 · 合计</span>
          <strong class="ui-kpi__value">{{ num('publisher', 'draft') === '—' ? '—' : Number(stats?.publisher?.draft ?? 0) + Number(stats?.publisher?.collecting ?? 0) + Number(stats?.publisher?.closed ?? 0) }}</strong>
        </div>
        <div class="ui-kpi">
          <span class="ui-kpi__label">我发布的 · 草稿</span>
          <strong class="ui-kpi__value">{{ num('publisher', 'draft') }}</strong>
        </div>
        <div class="ui-kpi">
          <span class="ui-kpi__label">我发布的 · 收集中</span>
          <strong class="ui-kpi__value">{{ num('publisher', 'collecting') }}</strong>
        </div>
        <div class="ui-kpi">
          <span class="ui-kpi__label">我发布的 · 已关闭</span>
          <strong class="ui-kpi__value">{{ num('publisher', 'closed') }}</strong>
        </div>
      </template>
      <div class="ui-kpi">
        <span class="ui-kpi__label">待我完成</span>
        <strong class="ui-kpi__value">{{ num('member', 'todo') }}</strong>
      </div>
      <div class="ui-kpi">
        <span class="ui-kpi__label">待我审核</span>
        <strong class="ui-kpi__value">{{ num('member', 'pendingAudit') }}</strong>
      </div>
      <div class="ui-kpi">
        <span class="ui-kpi__label">已通过</span>
        <strong class="ui-kpi__value">{{ num('member', 'approved') }}</strong>
      </div>
      <div class="ui-kpi">
        <span class="ui-kpi__label">被退回</span>
        <strong class="ui-kpi__value">{{ num('member', 'rejected') }}</strong>
      </div>
    </div>

    <el-row :gutter="12">
      <el-col v-if="isPublisher" :xs="24" :md="12">
        <el-card shadow="never">
          <template #header><span>我发布的任务</span></template>
          <el-button @click="go('/dcs/mission')">任务管理</el-button>
          <el-button @click="go('/dcs/audit')">审核队列</el-button>
        </el-card>
      </el-col>
      <el-col :xs="24" :md="isPublisher ? 12 : 24">
        <el-card shadow="never">
          <template #header><span>我的填报</span></template>
          <el-button @click="go('/dcs/todo')">我的待办</el-button>
          <el-button @click="go('/dcs/notice')">
            消息<span v-if="stats?.member?.unreadNotices" class="badge">{{ stats.member.unreadNotices }}</span>
          </el-button>
        </el-card>
      </el-col>
    </el-row>
    <el-card shadow="never">
      <template #header><span>这些数字怎么算的</span></template>
      <p class="tip">
        全部按服务端全量统计。有发布权限的人额外看到「我发布的」按草稿 / 收集中 / 已关闭分组；
        没有发布权限的账号只看自己填报的四种状态。
      </p>
    </el-card>
  </div>
</template>

<script setup name="DcsDashboard" lang="ts">
import { Refresh } from '@element-plus/icons-vue';
import { statsOverview, type DcsStatsOverview } from '@/api/dcs/core/task';
import { useUserStore } from '@/store/modules/user';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';

const userStore = useUserStore();
const router = useRouter();
const stats = ref<DcsStatsOverview | null>(null);
const statsError = ref(false);
const refreshing = ref(false);

const isPublisher = computed(() => userStore.permissions?.includes('dcs:task:list') || userStore.permissions?.includes('*:*:*'));
const go = (path: string) => router.push(path);

/** 取不到时返回破折号：这页 8 个格子全是计数，显示 0 会被当成「真的没有」 */
function num(scope: 'publisher' | 'member', key: string): string {
  if (statsError.value) {
    return '—';
  }
  const bucket = stats.value?.[scope] as Record<string, number> | undefined;
  return String(bucket?.[key] ?? 0);
}

// 成员/无任务权限角色也能进看板, 统计失败不弹错(全审 P2)
async function load() {
  if (refreshing.value) {
    return;
  }
  refreshing.value = true;
  try {
    stats.value = await statsOverview();
    statsError.value = false;
  } catch {
    stats.value = null;
    statsError.value = true;
  } finally {
    refreshing.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.dashboard-page {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.dashboard-page :deep(.dcs-ops-header) {
  margin-bottom: 4px;
}
.dashboard-kpi {
  margin-top: 0;
}
.dashboard-page :deep(.el-card__body) {
  padding: 16px;
  min-width: 0;
}
.dashboard-page :deep(.el-col) {
  min-width: 0;
}
.dashboard-page :deep(.el-card__body > .el-button) {
  margin-top: 0;
}
.badge {
  margin-left: 4px;
  background: var(--dcs-color-danger);
  /* 深色下 danger 是浅红，白字读不清；--el-color-white 在深色已取反成黑 */
  color: var(--el-color-white);
  border-radius: var(--dcs-pill-radius);
  padding: 0 6px;
  font-size: var(--dcs-font-xs);
}
.tip {
  color: var(--app-muted);
  font-size: var(--dcs-font-md);
}
@media (max-width: 992px) {
  .dashboard-page :deep(.el-col:not(:last-child)) {
    margin-bottom: 12px;
  }
}
</style>
