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
  /* 波一骨架 inset（2026-10-27 拍板 Q1=甲）：外卡=.main-container（sidebar.scss，四周 8px/圆角 14/软阴影），
     本组件退位为卡内透明滚动区——纸中纸取消；--sp-top=手机顶部安全区，桌面为 0
     U-01 让位令牌 --app-fab-yield 唯一定义在 .main-container（桌面 80/手机 84+safe），此处只消费不重复定义 */
  --app-chrome: var(--app-topbar-h);
  --session-chrome: calc(var(--app-chrome) + 16px + var(--app-fab-yield));
  width: auto;
  height: calc(100% - var(--app-chrome) - var(--sp-top, 0px));
  /* 顶栏 absolute 在卡内顶部（layout/index.vue），滚动区让出壳层高度 */
  margin: 0;
  background: transparent;
  border: none;
  border-radius: 0;
  position: relative;
  /* 只纵向滚：表格自己有横向滚动容器，页面级横向滚动没有必要 */
  overflow-x: hidden;
  overflow-y: auto;
}

/* 顶栏 absolute（脱离文档流）时才让出壳层高度；选择器写在 scoped 样式里编译成 .app-main[data-v-x]，
   兄弟节点 .fixed-header 在父组件，不需要带 scope 属性 */
.fixed-header + .app-main {
  margin-top: calc(var(--app-chrome) + var(--sp-top, 0px));
}

.hasTagsView {
  .app-main {
    /* 98 = 顶栏 64 + 页签 34 */
    --app-chrome: calc(var(--app-topbar-h) + 34px);
    --session-chrome: calc(var(--app-chrome) + 16px + var(--app-fab-yield));
  }
}

@media (max-width: 992px) {
  .app-main {
    /* U-01 手机档让位量由 .main-container 统一定义（84px+安全区，见 sidebar.scss .mobile 段），
       外卡缩进/圆角同样由外卡承担，本组件不自带 */
  }
}
</style>
<style lang="scss">
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
