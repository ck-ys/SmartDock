<template>
  <section class="app-main">
    <router-view v-slot="{ Component, route }">
      <transition :enter-active-class="animate" mode="out-in">
        <keep-alive :include="tagsViewStore.cachedViews">
          <component :is="Component" v-if="!route.meta.link" :key="route.path" />
        </keep-alive>
      </transition>
    </router-view>
    <iframe-toggle />
  </section>
</template>

<script setup name="AppMain" lang="ts">
import { useSettingsStore } from '@/store/modules/settings';
import { useTagsViewStore } from '@/store/modules/tagsView';

import IframeToggle from './IframeToggle/index.vue';
const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const route = useRoute();
const tagsViewStore = useTagsViewStore();

// 随机动画集合
const animate = ref<string>('');
const animationEnable = ref(useSettingsStore().animationEnable);
watch(
  () => useSettingsStore().animationEnable,
  (val: boolean) => {
    animationEnable.value = val;
    if (val) {
      animate.value = proxy?.animate.animateList[Math.round(Math.random() * proxy?.animate.animateList.length)] as string;
    } else {
      animate.value = proxy?.animate.defaultAnimate as string;
    }
  },
  { immediate: true }
);

onMounted(() => {
  addIframe();
});

watchEffect(() => {
  addIframe();
});

function addIframe() {
  if (route.meta.link) {
    useTagsViewStore().addIframeView(route);
  }
}
</script>

<style lang="scss" scoped>
.app-main {
  /* 50= navbar  50；dvh 跟随手机地址栏收放，vh 作旧浏览器兜底；
     --sp-top=顶部安全区（刘海/状态栏），桌面为 0 */
  min-height: calc(100vh - 50px - var(--sp-top, 0px));
  min-height: calc(100dvh - 50px - var(--sp-top, 0px));
  /* 会话页等「满屏内滚」容器用它扣壳层：默认 nav 50 + 8 呼吸位 */
  --session-chrome: 58px;
  width: 100%;
  position: relative;
  overflow: hidden;
}

.fixed-header + .app-main {
  padding-top: calc(50px + var(--sp-top, 0px));
}

.hasTagsView {
  .app-main {
    --session-chrome: 92px;
    /* 84 = navbar + tags-view = 50 + 34；dvh 跟随手机地址栏收放，vh 作旧浏览器兜底 */
    min-height: calc(100vh - 84px - var(--sp-top, 0px));
    min-height: calc(100dvh - 84px - var(--sp-top, 0px));
  }

  .fixed-header + .app-main {
    padding-top: calc(84px + var(--sp-top, 0px));
  }
}
</style>
<style lang="scss">
// fix css style bug in open el-dialog
.el-popup-parent--hidden {
  .fixed-header {
    padding-right: 6px;
  }
}

::-webkit-scrollbar {
  width: 6px;
  height: 6px;
}

::-webkit-scrollbar-track {
  background-color: var(--el-fill-color-lighter);
}

::-webkit-scrollbar-thumb {
  background-color: var(--el-text-color-placeholder);
  border-radius: 999px;
}
</style>
