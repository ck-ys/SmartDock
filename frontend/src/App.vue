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

onMounted(() => {
  nextTick(() => {
    // 初始化外观：框架纸（浅）/ 纯黑夜（深），同时把主色与数据强调色落到 :root
    const settingsStore = useSettingsStore();
    applyThemeMode(settingsStore.dark, settingsStore.accent);
  });
});
</script>
