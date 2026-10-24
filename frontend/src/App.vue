<template>
  <el-config-provider :locale="appStore.locale" :size="appStore.size">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import { useSettingsStore } from '@/store/modules/settings';
import { applyThemeMode } from '@/utils/theme';
import { useAppStore } from '@/store/modules/app';

const appStore = useAppStore();

// 初始化外观：框架纸（浅）/ 纯黑夜（深），同时把主色与数据强调色落到 :root。
// 必须在 setup 顶层同步执行——放到 onMounted 会晚于子组件的 useChartTokens() 求值，
// computed 缓存了 :root 默认色，刷新后图表会停在默认强调色（界面已换色、图没换）
const settingsStore = useSettingsStore();
applyThemeMode(settingsStore.dark, settingsStore.accent);
</script>
