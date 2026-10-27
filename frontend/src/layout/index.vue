<template>
  <div :class="classObj" class="app-wrapper" :style="{ '--current-color': theme }">
    <div v-if="device === 'mobile' && sidebar.opened" class="drawer-bg" @click="handleClickOutside" />
    <side-bar v-if="!sidebar.hide" class="sidebar-container" @set-layout="setLayout" @feedback="openFeedback" />
    <div :class="{ sidebarHide: sidebar.hide }" class="main-container">
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
        <!-- 2026-10-27 重构拍板 Q6=乙：页签整行下线（省 34px），面包屑保留并承担“你在哪”。
             keep-alive 依赖的 addView 已挪到本组件 setup 里的路由 watch——它是 cachedViews 的唯一写入点，
             不能跟着页签组件一起消失 -->
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
import { AppMain, Navbar, Settings } from './components';
import FeedbackDialog from '@/views/dcs/feedback/components/FeedbackDialog.vue';
// S-38：悬浮球仅壳常驻，抽屉主体在组件内 dynamic import
const DcsAssistant = defineAsyncComponent(() => import('@/components/DcsAssistant/index.vue'));
// 交互三件 ①：命令面板随壳常驻；异步加载避免与 permission store → router → Layout 形成同步环
const DcsCommandPalette = defineAsyncComponent(() => import('@/components/DcsCommandPalette/index.vue'));
import { useAppStore } from '@/store/modules/app';
import { useSettingsStore } from '@/store/modules/settings';
import { useTagsViewStore } from '@/store/modules/tagsView';
import { NavTypeEnum } from '@/enums/NavTypeEnum';
import { initWebSocket } from '@/utils/websocket';
import { initSSE } from '@/utils/sse';

const settingsStore = useSettingsStore();
const theme = computed(() => settingsStore.theme);
const sidebar = computed(() => useAppStore().sidebar);
const device = computed(() => useAppStore().device);
const fixedHeader = computed(() => settingsStore.fixedHeader);
const layout = computed(() => settingsStore.navType);

// 2026-10-27 重构拍板 Q6=乙：页签栏下线后，cachedViews / visitedViews 的唯一写入点迁到这里。
// 必须在 layout（父组件）里同步执行——它早于 AppMain 的 router-view 渲染，keep-alive 才能在第一
// 次进入页面时就拿到组件名；漏掉这一步的表现是“切走再回来，筛选条件和滚动位置全丢”，且不报错
const currentRoute = useRoute();
const tagsViewStore = useTagsViewStore();
watch(
  () => currentRoute.fullPath,
  () => {
    if (!currentRoute.name) return;
    if (currentRoute.query.title) {
      currentRoute.meta.title = currentRoute.query.title as string;
    }
    tagsViewStore.addView(currentRoute as any);
  },
  { immediate: true }
);

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
  /* 外卡 margin-top 8px 会穿过非 BFC 父级塌到 body（全站多 8px 整页滚动条）——建 BFC 收住 */
  display: flow-root;
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

/* 壳层顶栏：fixed 定位（滚动不丢顶栏），left/right 跟着 --app-sheet-inset 收边。
   2026-10-27 拍板 Q5=丙 之后 inset=0、外卡圆角=0，顶栏与内容区并进同一片灰画布，
   底部那条 1px rule（theme-framed 里 #app .fixed-header）保留，用来托住页签行 */
.fixed-header {
  position: fixed;
  top: var(--app-sheet-inset, 8px);
  left: calc(#{$base-sidebar-width} + var(--app-sheet-inset, 8px));
  right: var(--app-sheet-inset, 8px);
  z-index: 9;
  width: auto;
  transition: left 0.28s;
  /* 2026-10-27 拍板 Q5=丙：顶栏并进灰画布，不再单独一条白带；
     圆角随 --app-shell-radius 归零（令牌仍在，皮肤层只换值） */
  background: var(--app-ground);
  border-radius: var(--app-shell-radius, 0px) var(--app-shell-radius, 0px) 0 0;
  box-shadow: none;
}

.hideSidebar .fixed-header {
  left: calc(48px + var(--app-sheet-inset, 8px));
}

.sidebarHide .fixed-header {
  left: var(--app-sheet-inset, 8px);
}

.mobile .fixed-header {
  left: var(--app-sheet-inset, 8px);
}

/* 跨 992 断点时 JS 收侧栏带 withoutAnimation，顶栏 left 过渡须同豁免，否则卡瞬移、顶栏飘 0.28s */
.withoutAnimation .fixed-header {
  transition: none;
}
</style>
