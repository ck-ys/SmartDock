<template>
  <div class="dcs-ops-page">
    <DcsOperationsPageHeader
      title="账户与账单"
      subtitle="平台侧钱包资金、两本账流水与充值订单的一站式对账入口（原「租户钱包 / 钱包流水 / 充值订单」三菜单合并）。"
      :metrics="metrics"
    />

    <el-tabs v-model="activeTab" class="ab-tabs">
      <el-tab-pane v-if="permOverview" label="账户总览" name="overview">
        <OverviewTab v-if="activeTab === 'overview'" @metrics="setMetrics" />
      </el-tab-pane>
      <el-tab-pane v-if="permLedger" label="钱包流水" name="ledger">
        <LedgerTab v-if="activeTab === 'ledger'" @metrics="setMetrics" />
      </el-tab-pane>
      <el-tab-pane v-if="permOrders" label="充值订单" name="orders">
        <OrdersTab v-if="activeTab === 'orders'" @metrics="setMetrics" />
      </el-tab-pane>
    </el-tabs>
    <!-- 深审回修(P2-6): 三页签权限全无时的兜底空态（深链误入场景），不留纯空白页 -->
    <el-empty v-if="!permOverview && !permLedger && !permOrders" description="当前账号没有账户与账单的查看权限，请联系管理员授权" />
  </div>
</template>

<script setup lang="ts">
/**
 * 账户与账单（菜单IA重组批2，2026-10-16）：三页签壳层。
 * - 页签写进 URL（?tab=overview|ledger|orders），旧菜单地址由 router/index.ts 三条 redirect 兜底
 * - 页签按各自菜单权限渲染（旧三菜单 perms 保留在隐藏菜单 9068/9096 上）
 * - KPI 统计带数据由当前激活页签 emit 上来（切换时先清空避免上个页签数字残留）
 */
import { checkPermi } from '@/utils/permission';
import type { DcsOperationsMetric } from '@/components/DcsOperationsPageHeader/index.vue';
import OverviewTab from './components/OverviewTab.vue';
import LedgerTab from './components/LedgerTab.vue';
import OrdersTab from './components/OrdersTab.vue';

defineOptions({ name: 'DcsAccountBilling' });

const route = useRoute();
const router = useRouter();

const permOverview = checkPermi(['dcs:wallet:list']);
const permLedger = checkPermi(['dcs:wallet-ledger:list']);
const permOrders = checkPermi(['dcs:payment-order:list']);

const TAB_NAMES = ['overview', 'ledger', 'orders'] as const;
const firstAllowed = TAB_NAMES.find((t) => (t === 'overview' ? permOverview : t === 'ledger' ? permLedger : permOrders)) || 'overview';
const validTab = (t: unknown): t is (typeof TAB_NAMES)[number] =>
  (TAB_NAMES as readonly unknown[]).includes(t) && (t === 'overview' ? permOverview : t === 'ledger' ? permLedger : permOrders);

const activeTab = ref(validTab(route.query.tab) ? route.query.tab : firstAllowed);

watch(
  activeTab,
  (tab) => {
    metrics.value = [];
    router.replace({ query: { ...route.query, tab } });
  },
  { flush: 'post' }
);
// 浏览器前进/后退直接改 query 时跟随（replace 不触发本 watch 的场景）
watch(
  () => route.query.tab,
  (t) => {
    if (validTab(t) && t !== activeTab.value) activeTab.value = t;
  }
);

const metrics = ref<DcsOperationsMetric[]>([]);
const setMetrics = (m: DcsOperationsMetric[]) => {
  metrics.value = m;
};
</script>

<style scoped>
.ab-tabs {
  margin-top: 4px;
}
.ab-tabs :deep(.el-tabs__header) {
  margin-bottom: 12px;
}
.ab-tabs :deep(.el-tabs__nav-wrap::after) {
  height: 1px;
}
</style>
