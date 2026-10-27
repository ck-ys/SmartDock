<template>
  <div class="app-container ops-shell">
    <dcs-operations-page-header
      eyebrow="画像引擎"
      title="引擎运维"
      subtitle="画像引擎的运维都收在这一页：回放重算与数据保留、引擎健康巡检、协议 Schema 与测试发送——原三页合一，动作全部留痕可查。"
    />

    <el-tabs v-model="tab" class="ops-tabs">
      <el-tab-pane label="数据运维" name="ops">
        <OpsPane />
      </el-tab-pane>
      <el-tab-pane v-if="checkPermi(['profile:health:query'])" label="引擎健康" name="health" lazy>
        <HealthPane />
      </el-tab-pane>
      <el-tab-pane v-if="checkPermi(['profile:engine:query'])" label="引擎管理" name="admin" lazy>
        <AdminPane />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts" name="ProfileOps">
import OpsPane from './ops/OpsPane.vue';
import HealthPane from './ops/HealthPane.vue';
import AdminPane from './ops/AdminPane.vue';
import DcsOperationsPageHeader from '@/components/DcsOperationsPageHeader/index.vue';
import { checkPermi } from '@/utils/permission';

// 三页合一(2026-10-27 画像方向重构第一批): 数据运维/引擎健康/引擎管理并为一个「引擎运维」页,
// 后两个 tab 按原页面权限串显隐(权限菜单 12002/11989 已转隐藏按钮挂在本页 12001 下)
// tab 深链: 旧三页地址/空态引导用 ?tab=ops|health|admin 直达对应标签
const route = useRoute();
// 三审(glm53 P2): 深链 tab 同时校验权限——无权限者带着 ?tab=health 进来会落在一个
// 渲染不出来的 tab 上(内容区空白), 夹回第一个 tab
const tabAllowed = (t: string) =>
  t === 'ops' || (t === 'health' && checkPermi(['profile:health:query'])) || (t === 'admin' && checkPermi(['profile:engine:query']));
const tab = ref(tabAllowed(String(route.query.tab)) ? String(route.query.tab) : 'ops');
// 三审 dspro P2: keep-alive 下二次导航同路由只变 query, 初值逻辑不会再跑
watch(
  () => route.query.tab,
  (v) => {
    const next = typeof v === 'string' && tabAllowed(v) ? v : 'ops';
    if (next !== tab.value) tab.value = next;
  }
);
</script>

<style scoped>
.ops-shell {
  display: flex;
  flex-direction: column;
  gap: 12px;
  min-width: 0;
}
.ops-shell :deep(.dcs-ops-header) {
  margin-bottom: 4px;
}
.ops-shell :deep(.el-tabs__content) {
  overflow: visible;
}
</style>
