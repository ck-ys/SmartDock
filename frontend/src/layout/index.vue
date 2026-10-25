<template>
  <div :class="classObj" class="app-wrapper" :style="{ '--current-color': theme }">
    <div v-if="device === 'mobile' && sidebar.opened" class="drawer-bg" @click="handleClickOutside" />
    <side-bar v-if="!sidebar.hide" class="sidebar-container" @set-layout="setLayout" @feedback="openFeedback" />
    <div :class="{ hasTagsView: needTagsView, sidebarHide: sidebar.hide }" class="main-container">
      <!-- <el-scrollbar>
        <div :class="{ 'fixed-header': fixedHeader }">
          <navbar ref="navbarRef" @setLayout="setLayout" />
          <tags-view v-if="needTagsView" />
        </div>
        <app-main />
        <settings ref="settingRef" />
      </el-scrollbar> -->
      <div :class="{ 'fixed-header': fixedHeader }">
        <navbar @set-layout="setLayout" @feedback="openFeedback" />
        <!-- 波 0.1（多审 P2）：手机用 v-show 而非 v-if——addView（visitedViews/cachedViews 的唯一写入点）
             在 TagsView 内部 watch 路由，v-if 卸载后手机 keep-alive 整体失效（返回丢筛选/滚动）；
             保留挂载只藏 DOM，设置关掉页签时仍彻底卸载 -->
        <tags-view v-if="settingsStore.tagsView" v-show="needTagsView" />
      </div>
      <app-main />
      <settings ref="settingRef" />
      <!-- 反馈弹窗归壳层持有：顶栏头像菜单与左下用户行开的是同一个框 -->
      <feedback-dialog v-model:visible="feedbackOpen" :initial-tab="feedbackTab" />
      <!-- DCS AI 助手（批E：悬浮球+抽屉；组件内自判权限与租户开关，未开启=零渲染） -->
      <dcs-assistant />
      <!-- 交互三件 ①：Ctrl+K / Cmd+K 命令面板（数据源=权限过滤后的路由菜单） -->
      <dcs-command-palette />
    </div>
  </div>
</template>

<script setup lang="ts">
import SideBar from './components/Sidebar/index.vue';
import { AppMain, Navbar, Settings, TagsView } from './components';
import FeedbackDialog from '@/views/dcs/feedback/components/FeedbackDialog.vue';
// S-38：悬浮球仅壳常驻，抽屉主体在组件内 dynamic import
const DcsAssistant = defineAsyncComponent(() => import('@/components/DcsAssistant/index.vue'));
// 交互三件 ①：命令面板随壳常驻；异步加载避免与 permission store → router → Layout 形成同步环
const DcsCommandPalette = defineAsyncComponent(() => import('@/components/DcsCommandPalette/index.vue'));
import { useAppStore } from '@/store/modules/app';
import { useSettingsStore } from '@/store/modules/settings';
import { NavTypeEnum } from '@/enums/NavTypeEnum';
import { initWebSocket } from '@/utils/websocket';
import { initSSE } from '@/utils/sse';

const settingsStore = useSettingsStore();
const theme = computed(() => settingsStore.theme);
const sidebar = computed(() => useAppStore().sidebar);
const device = computed(() => useAppStore().device);
// 波 0：手机不显示页签栏（50px 竖向空间让给内容），仍尊重「显示页签」设置；
// 组件本体仍挂载（v-show），否则 addView 停摆、手机 keep-alive 失效——见模板内注释
const needTagsView = computed(() => settingsStore.tagsView && device.value !== 'mobile');
const fixedHeader = computed(() => settingsStore.fixedHeader);
const layout = computed(() => settingsStore.navType);

// 根据布局模式判断是否显示侧边栏
const showSidebar = computed(() => {
  if (sidebar.value.hide) return false;
  return layout.value === NavTypeEnum.LEFT || layout.value === NavTypeEnum.MIX;
});

const classObj = computed(() => ({
  hideSidebar: !sidebar.value.opened,
  openSidebar: sidebar.value.opened,
  withoutAnimation: sidebar.value.withoutAnimation,
  mobile: device.value === 'mobile'
}));

const { width } = useWindowSize();
const WIDTH = 992; // refer to Bootstrap's responsive design

watchEffect(() => {
  if (device.value === 'mobile') {
    useAppStore().closeSideBar({ withoutAnimation: false });
  }
  if (width.value - 1 < WIDTH) {
    useAppStore().toggleDevice('mobile');
    useAppStore().closeSideBar({ withoutAnimation: true });
  } else {
    useAppStore().toggleDevice('desktop');
  }
});

const settingRef = ref<InstanceType<typeof Settings>>();
const feedbackOpen = ref(false);
const feedbackTab = ref<'submit' | 'mine'>('submit');
const openFeedback = (tab: 'submit' | 'mine' = 'submit') => {
  feedbackTab.value = tab;
  feedbackOpen.value = true;
};

onMounted(() => {
  const protocol = window.location.protocol === 'https:' ? 'wss://' : 'ws://';
  initWebSocket(protocol + window.location.host + import.meta.env.VITE_APP_BASE_API + '/resource/websocket');
});

onMounted(() => {
  initSSE(import.meta.env.VITE_APP_BASE_API + '/resource/sse');
});

const handleClickOutside = () => {
  useAppStore().closeSideBar({ withoutAnimation: false });
};

// 题2-① 手机抽屉自动关：点菜单跳转后收起抽屉（原来跳转后抽屉还盖着页面，要再点一次黑遮罩）
const route = useRoute();
watch(
  () => route.fullPath,
  () => {
    if (device.value === 'mobile' && sidebar.value.opened) {
      useAppStore().closeSideBar({ withoutAnimation: false });
    }
  }
);

// 题2-③ Ctrl+B 开合左栏（桌面）；Esc 关手机抽屉（题2-① 的键盘通道）。
// 输入框里不拦 Ctrl+B（不抢系统/输入习惯）；移动端不给 Ctrl+B（抽屉语义与桌面折叠不同）
onMounted(() => {
  const onKeydown = (e: KeyboardEvent) => {
    if (e.key === 'Escape' && device.value === 'mobile' && sidebar.value.opened) {
      useAppStore().closeSideBar({ withoutAnimation: false });
      return;
    }
    if ((e.ctrlKey || e.metaKey) && !e.altKey && !e.shiftKey && (e.key === 'b' || e.key === 'B') && device.value !== 'mobile') {
      const target = e.target as HTMLElement | null;
      // contenteditable="" / plaintext-only 等价可编辑，一并让行
      if (target?.closest?.('input, textarea, select, [contenteditable]:not([contenteditable="false"])')) {
        return;
      }
      e.preventDefault();
      useAppStore().toggleSideBar(false);
    }
  };
  window.addEventListener('keydown', onKeydown);
  onUnmounted(() => window.removeEventListener('keydown', onKeydown));
});

const setLayout = () => {
  settingRef.value?.openSetting();
};
</script>

<style lang="scss" scoped>
@use '@/assets/styles/mixin.scss';
@use '@/assets/styles/variables.module.scss' as *;

.app-wrapper {
  @include mixin.clearfix;
  position: relative;
  height: 100%;
  width: 100%;

  &.mobile.openSidebar {
    position: fixed;
    top: 0;
  }
}

.drawer-bg {
  background: #000;
  opacity: 0.3;
  width: 100%;
  top: 0;
  height: 100%;
  position: absolute;
  z-index: 999;
}

.fixed-header {
  position: fixed;
  top: 0;
  right: 0;
  z-index: 9;
  width: calc(100% - #{$base-sidebar-width});
  transition: width 0.28s;
  background: var(--app-frame);
  box-shadow: none;
}

.hideSidebar .fixed-header {
  width: calc(100% - 48px);
}

.sidebarHide .fixed-header {
  width: 100%;
}

.mobile .fixed-header {
  width: 100%;
}
</style>
