<template>
  <div class="navbar" :class="'nav' + navType">
    <hamburger id="hamburger-container" :is-active="appStore.sidebar.opened" class="hamburger-container" @toggle-click="toggleSideBar" />

    <breadcrumb v-if="navType == NavTypeEnum.LEFT" id="breadcrumb-container" class="breadcrumb-container" />
    <top-nav v-if="navType == NavTypeEnum.MIX" id="topmenu-container" class="topmenu-container" />

    <template v-if="navType == NavTypeEnum.TOP">
      <logo v-show="showLogo" :collapse="false"></logo>
      <top-bar id="topbar-container" class="topbar-container" />
    </template>
    <div class="right-menu flex align-center">
      <!-- 方案一：顶栏只留 5 个——搜索 / 消息 / 外观 / 全屏 / 头像。
           租户搬左上品牌行，反馈回头像菜单，语言/字号/主题收进外观抽屉（见 DcsAppearanceDrawer） -->
      <button v-if="appStore.device !== 'mobile'" type="button" class="nav-search" aria-label="搜索页面" @click="openPalette">
        <svg-icon class="nav-search__icon" icon-class="search" />
        <span class="nav-search__label">搜索页面…</span>
        <kbd class="nav-search__kbd">{{ shortcutLabel }}</kbd>
      </button>
      <el-tooltip v-else :content="proxy.$t('navbar.search')" effect="dark" placement="bottom">
        <div class="right-menu-item hover-effect" @click="openPalette">
          <svg-icon class-name="search-icon" icon-class="search" />
        </div>
      </el-tooltip>

      <!-- 消息 -->
      <el-tooltip :content="proxy.$t('navbar.message')" effect="dark" placement="bottom">
        <div style="display: flex; align-items: center">
          <el-popover placement="bottom" trigger="click" transition="el-zoom-in-top" :width="300" :persistent="false">
            <template #reference>
              <el-badge :value="newNotice > 0 ? newNotice : ''" :max="99">
                <div class="right-menu-item hover-effect"><svg-icon icon-class="message" /></div>
              </el-badge>
            </template>
            <template #default>
              <notice></notice>
            </template>
          </el-popover>
        </div>
      </el-tooltip>

      <!-- 外观抽屉：主题 / 密度 / 语言 / 字号都在里面 -->
      <el-tooltip v-if="appStore.device !== 'mobile'" content="外观" effect="dark" placement="bottom">
        <div class="right-menu-item hover-effect appearance-entry" @click="openAppearance">
          <el-icon><Brush /></el-icon>
        </div>
      </el-tooltip>

      <el-tooltip v-if="appStore.device !== 'mobile'" :content="proxy.$t('navbar.full')" effect="dark" placement="bottom">
        <screenfull id="screenfull" class="right-menu-item hover-effect" />
      </el-tooltip>

      <!-- 头像：与左下用户行共用同一份账号菜单 -->
      <div class="avatar-container">
        <dcs-user-menu @layout="setLayout" @feedback="requestFeedback">
          <div class="right-menu-item hover-effect avatar-wrapper">
            <img :src="userStore.avatar" class="user-avatar" />
            <el-icon><caret-bottom /></el-icon>
          </div>
        </dcs-user-menu>
      </div>
    </div>
    <dcs-appearance-drawer ref="appearanceDrawerRef" />
  </div>
</template>

<script setup lang="ts">
import { useAppStore } from '@/store/modules/app';
import { useUserStore } from '@/store/modules/user';
import { useSettingsStore } from '@/store/modules/settings';
import { useNoticeStore } from '@/store/modules/notice';
import { useAnnouncementStore } from '@/store/modules/announcement';
import notice from './notice/index.vue';
import DcsAppearanceDrawer from '@/components/DcsAppearanceDrawer/index.vue';
import DcsUserMenu from '@/components/DcsUserMenu/index.vue';
import { ElMessage } from 'element-plus';
import { NavTypeEnum } from '@/enums/NavTypeEnum';
import Logo from '@/layout/components/Sidebar/Logo.vue';
import TopBar from './TopBar/index.vue';

const appStore = useAppStore();
const userStore = useUserStore();
const settingsStore = useSettingsStore();
const noticeStore = useNoticeStore();
const announcementStore = useAnnouncementStore();
const newNotice = computed(() => noticeStore.state.unreadCount);
const emit = defineEmits<{ setLayout: []; feedback: [tab: 'submit' | 'mine'] }>();
const setLayout = () => {
  emit('setLayout');
};

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const route = useRoute();
// 反馈弹窗由 layout 持有（左下用户行也要能开同一个框），这里只发事件
const requestFeedback = () => emit('feedback', 'submit');

// 「反馈已处理」通知的落地动作：/index?feedback=mine → 打开「我的反馈」，随后清掉参数
watch(
  () => route.query.feedback,
  (value) => {
    if (value !== 'mine') {
      return;
    }
    emit('feedback', 'mine');
    const query = { ...route.query };
    delete query.feedback;
    proxy?.$router.replace({ path: route.path, query });
  },
  { immediate: true }
);

const navType = computed(() => settingsStore.navType);
const showLogo = computed(() => settingsStore.sidebarLogo);

// 搜索入口：桌面是一条假输入框，点击开命令面板；徽标按平台显示 ⌘K / Ctrl K
const isMac = /mac/i.test(navigator.platform);
const shortcutLabel = isMac ? '⌘ K' : 'Ctrl K';
const openPalette = () => window.dispatchEvent(new Event('dcs:open-palette'));

// 交互三件 ②：外观抽屉（主题 + 密度 + 语言 + 字号）
const appearanceDrawerRef = ref<InstanceType<typeof DcsAppearanceDrawer>>();
const openAppearance = () => appearanceDrawerRef.value?.open();

const toggleSideBar = () => {
  appStore.toggleSideBar(false);
};

//用深度监听 消息
onMounted(() => {
  // PERF-HOME(2026-09-19): 只调 fetchRecent——其内部已并行刷未读数; 此处再单发一次会让
  // 冷启动 unread-count 翻倍(实测三路并发之一)
  noticeStore.fetchRecent();
  // 公告角标独立口径（Q11）：失败静默，不拖慢顶栏
  void announcementStore.refreshUnreadCount();
});
</script>

<style lang="scss" scoped>
.navbar.navtop {
  .hamburger-container {
    display: none !important;
  }
}

:deep(.el-badge__content.is-fixed) {
  top: 12px;
  // "99+"等宽角标默认 translateX(100%) 外溢到容器右缘外，会盖住右侧相邻图标
  // （2026-09-24 实锤：反馈入口被消息未读角标完全遮挡）——收回容器右上角内侧，盖自己不盖邻居
  right: 2px;
  transform: translateY(-50%);
}

.nav-search {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex: none;
  width: clamp(150px, 18vw, 240px);
  height: 32px;
  margin-right: 8px;
  padding: 0 8px 0 10px;
  border: 1px solid var(--app-border);
  border-radius: var(--app-control-radius);
  background: transparent;
  color: var(--app-muted);
  font-family: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  transition:
    border-color 0.15s ease,
    color 0.15s ease;

  &:hover {
    border-color: var(--app-control);
    color: var(--app-text);
  }

  &__icon {
    flex: none;
    width: 14px;
    height: 14px;
    fill: currentColor;
  }

  &__label {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  &__kbd {
    flex: none;
    margin-left: auto;
    padding: 0 4px;
    border: 1px solid var(--app-border);
    border-radius: 4px;
    color: var(--app-faint);
    font-family: inherit;
    font-size: 11px;
    line-height: 17px;
  }
}

.flex {
  display: flex;
}

.align-center {
  align-items: center;
}

.navbar {
  height: var(--app-topbar-h);
  overflow: hidden;
  position: relative;
  background: transparent;
  border-bottom: 0;
  box-shadow: none;
  display: flex;
  align-items: center;
  // padding: 0 8px;
  box-sizing: border-box;

  .hamburger-container {
    line-height: 46px;
    height: 100%;
    //float: left;
    cursor: pointer;
    transition: background 0.3s;
    -webkit-tap-highlight-color: transparent;
    display: flex;
    align-items: center;
    flex-shrink: 0;
    margin-right: 8px;

    &:hover {
      background: var(--el-fill-color-lighter);
    }
  }

  .breadcrumb-container {
    //float: left;
    flex-shrink: 0;
  }

  .topmenu-container {
    position: absolute;
    left: 50px;
  }

  .topbar-container {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    overflow: hidden;
    margin-left: 8px;
  }

  .errLog-container {
    display: inline-block;
    vertical-align: top;
  }

  .right-menu {
    height: 100%;
    display: flex;
    align-items: center;
    margin-left: auto;

    &:focus {
      outline: none;
    }

    .right-menu-item {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 8px;
      height: 32px;
      font-size: 18px;
      color: var(--el-text-color-regular);
      border-radius: var(--app-radius-md);

      &.hover-effect {
        cursor: pointer;
        transition:
          background 0.2s ease,
          color 0.2s ease;

        &:hover {
          background: var(--app-raised);
          color: var(--app-text);
        }
      }
    }

    .avatar-container {
      margin-right: 8px;

      .avatar-wrapper {
        display: inline-flex;
        align-items: center;
        gap: 2px;
        padding-right: 4px;

        .user-avatar {
          cursor: pointer;
          width: 32px;
          height: 32px;
          border-radius: 10px;
          display: block;
        }

        /* 箭头跟头像同一行居中，不再用 absolute + right:-20px 外溢（旧写法逼着给容器留 40px 右边距） */
        i {
          cursor: pointer;
          position: static;
          font-size: 12px;
          color: var(--app-faint);
        }
      }
    }
  }

  /* 波 0：手机顶栏触控面 ≥44px（图标视觉大小不变），并收紧头像右侧留白；
     波 0.1：断点 768→992 对齐壳层设备判定（width-1<992 即手机形态，iPad 竖屏/手机横屏同享补偿） */
  /* 顶部安全区：状态栏/刘海不吃进顶栏内容（env 在桌面/无刘海设备恒 0）；
     AppMain 的高度扣减在所有宽度都消费 --sp-top，这里同口径，避免宽屏错位 */
  height: calc(var(--app-topbar-h) + var(--sp-top, 0px));
  padding-top: var(--sp-top, 0px);

  @media (max-width: 992px) {
    /* 手机只留最后一级做「你在哪」的提示：完整面包屑 + 未读角标 + 头像三者加起来会
       超出 390 视口（实测 413），把头像顶出右边缘；截断后约 294，留足余量 */
    .breadcrumb-container {
      flex: 0 1 auto;
      min-width: 0;
      overflow: hidden;

      :deep(.el-breadcrumb__item) {
        display: none;
      }

      :deep(.el-breadcrumb__item:last-child) {
        display: inline-flex;
        align-items: center;
        max-width: 100%;
      }

      :deep(.el-breadcrumb__separator) {
        display: none;
      }

      :deep(.el-breadcrumb__inner) {
        overflow: hidden;
        text-overflow: ellipsis;
        white-space: nowrap;
      }
    }

    .right-menu {
      gap: 2px;

      .right-menu-item {
        /* 390px 顶栏只有约 137px：44px 会把头像顶出视口（实测 x=393 起点），收到 40px */
        min-width: 40px;
        height: 44px;
        padding: 0 4px;
      }

      .avatar-container {
        /* 头像右侧 8px：箭头已改内联，不再需要给 absolute 箭头留位 */
        margin-right: 8px;
      }
    }
  }
}
</style>
