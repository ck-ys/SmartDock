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
  /* 框架纸：顶栏/侧栏留在浅框上，内容是一张内缩 8px 的白纸
     48=顶栏；--sp-top=手机顶部安全区，桌面为 0 */
  --app-chrome: var(--app-topbar-h);
  /* U-01 让位：右下角常驻 AI 悬浮球（≤992 为 60px、桌面 56px，均贴边 24px），
     纸面底缘整体上抬到球顶之上——任何滚动位置内容都不与球重叠；
     高度同步减去让位量，避免整页多出一条 body 滚动条 */
  --app-fab-yield: 80px;
  --session-chrome: calc(var(--app-chrome) + 16px + var(--app-fab-yield));
  width: auto;
  height: calc(100vh - var(--app-chrome) - var(--app-sheet-inset) - var(--app-fab-yield) - var(--sp-top, 0px));
  height: calc(100dvh - var(--app-chrome) - var(--app-sheet-inset) - var(--app-fab-yield) - var(--sp-top, 0px));
  /* 顶栏默认 fixed（脱离文档流），纸面要让出壳层高度；关掉「固定 Header」后
     顶栏回到文档流里，这里再让一次就会整体下移 82px 并多出一条整页滚动条 */
  margin: 0 var(--app-sheet-inset) calc(var(--app-sheet-inset) + var(--app-fab-yield)) 0;
  background: var(--app-sheet-bg);
  border: 1px solid var(--app-border);
  border-radius: var(--app-sheet-radius);
  position: relative;
  /* 只纵向滚：纸面自带 1px 边框，overflow:auto 会因这 2px 多出一条横向滚动条；
     表格自己有横向滚动容器，页面级横向滚动没必要 */
  overflow-x: hidden;
  overflow-y: auto;
}

/* 顶栏 fixed 时才让出壳层高度；选择器写在 scoped 样式里编译成 .app-main[data-v-x]，
   兄弟节点 .fixed-header 在父组件，不需要带 scope 属性 */
.fixed-header + .app-main {
  margin-top: calc(var(--app-chrome) + var(--sp-top, 0px));
}

.hasTagsView {
  .app-main {
    /* 82 = 顶栏 48 + 页签 34 */
    --app-chrome: calc(var(--app-topbar-h) + 34px);
    --session-chrome: calc(var(--app-chrome) + 16px + var(--app-fab-yield));
  }
}

@media (max-width: 992px) {
  .app-main {
    /* U-01：手机档球 60px + 贴边 24px + 手势条安全区，让位量随之加大（含 8px 呼吸位） */
    --app-fab-yield: calc(84px + env(safe-area-inset-bottom, 0px));
    margin-right: var(--app-sheet-inset);
    margin-left: var(--app-sheet-inset);
    border-radius: var(--app-sheet-radius);
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
