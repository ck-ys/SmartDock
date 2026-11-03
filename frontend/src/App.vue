<template>
  <el-config-provider :locale="appStore.locale" :size="appStore.size">
    <!-- 批3：业务壳 ↔ 智能体工作台是顶层换端，给一次轻微淡入（内页切换走 AppMain 既有过渡，不受影响） -->
    <router-view v-slot="{ Component }">
      <transition name="shell-fade" mode="out-in">
        <component :is="Component" />
      </transition>
    </router-view>
  </el-config-provider>
</template>

<script setup lang="ts">
import { useSettingsStore } from '@/store/modules/settings';
import { applyThemeMode } from '@/utils/theme';
import { useAppStore } from '@/store/modules/app';

const appStore = useAppStore();

// 初始化外观：明暗由这里同步（皮肤 skin 在 main.ts 更早的 applyInitialSkin 已挂好）。
// 必须在 setup 顶层同步执行——放到 onMounted 会晚于子组件的 useChartTokens() 求值，
// computed 缓存了 :root 默认色，刷新后图表会停在默认强调色（界面已换色、图没换）
const settingsStore = useSettingsStore();
applyThemeMode(settingsStore.dark);
</script>

<style>
.shell-fade-enter-active,
.shell-fade-leave-active {
  transition: opacity 0.14s ease;
}
.shell-fade-enter-from,
.shell-fade-leave-to {
  opacity: 0;
}
@media (prefers-reduced-motion: reduce) {
  .shell-fade-enter-active,
  .shell-fade-leave-active {
    transition: none;
  }
}
</style>
