<template>
  <div :class="{ 'has-logo': showLogo }" :style="{ backgroundColor: bgColor }">
    <brand-row v-if="showLogo" :collapse="isCollapse" />
    <el-scrollbar :class="sideTheme" wrap-class="scrollbar-wrapper">
      <transition :enter-active-class="proxy?.animate.menuSearchAnimate.enter" mode="out-in">
        <el-menu
          ref="menuRef"
          :default-active="activeMenu"
          :collapse="isCollapse"
          :background-color="bgColor"
          :text-color="textColor"
          :unique-opened="true"
          active-text-color="var(--app-text)"
          :collapse-transition="false"
          :popper-offset="12"
          mode="vertical"
          @open="onMenuOpen"
          @close="onMenuClose"
          @pointerdown.capture="armMenuTouch"
          @keydown.capture="(e: KeyboardEvent) => (e.key === 'Enter' || e.key === ' ') && armMenuTouch()"
        >
          <sidebar-item v-for="(r, index) in sidebarRouters" :key="r.path + index" :item="r" :base-path="r.path" />
        </el-menu>
      </transition>
    </el-scrollbar>
    <user-row :collapse="isCollapse" @set-layout="emit('set-layout')" @feedback="emit('feedback')" />
  </div>
</template>

<script setup lang="ts">
import BrandRow from './BrandRow.vue';
import UserRow from './UserRow.vue';
import SidebarItem from './SidebarItem.vue';
import variables from '@/assets/styles/variables.module.scss';
import { useAppStore } from '@/store/modules/app';
import { useSettingsStore } from '@/store/modules/settings';
import { usePermissionStore } from '@/store/modules/permission';
import { RouteRecordRaw } from 'vue-router';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const emit = defineEmits<{ 'set-layout': []; feedback: [] }>();

const route = useRoute();
const appStore = useAppStore();
const settingsStore = useSettingsStore();
const permissionStore = usePermissionStore();
const sidebarRouters = computed<RouteRecordRaw[]>(() => permissionStore.getSidebarRoutes());
const showLogo = computed(() => settingsStore.sidebarLogo);
const sideTheme = computed(() => settingsStore.sideTheme);
const theme = computed(() => settingsStore.theme);
const isCollapse = computed(() => !appStore.sidebar.opened);

const activeMenu = computed(() => {
  const { meta, path } = route;
  // if set path, the sidebar will highlight the path you set
  if (meta.activeMenu) {
    return meta.activeMenu;
  }
  return path;
});

const bgColor = computed(() => (settingsStore.dark ? variables.menuBackground : variables.menuLightBackground));
const textColor = computed(() => (settingsStore.dark ? variables.menuColor : variables.menuLightColor));

// 题2-② 展开记忆：折叠再展开会重挂菜单，开着的分组记在 appStore（localStorage 持久化）。
// 臂门：Element Plus 的 initMenu 会在挂载/激活项变化时自动 emit open(激活链)，
// 不加区分会被当成用户操作、每次进页面都覆盖掉真正的记忆，所以只有用户真实
// 交互（指针按下/回车空格）"武装"后的下一次 open/close 才写库，短定时自动缴械。
// el-menu 是 unique-opened 单开语义，EP 静默顶掉前一组时不发 close，所以 open 一律整体覆盖。
let menuTouchArmed = false;
let disarmTimer: ReturnType<typeof setTimeout> | undefined;
const armMenuTouch = () => {
  menuTouchArmed = true;
  clearTimeout(disarmTimer);
  disarmTimer = setTimeout(() => (menuTouchArmed = false), 600);
};
const onMenuOpen = (index: string) => {
  if (!menuTouchArmed) return;
  menuTouchArmed = false;
  appStore.setOpenedMenus([index]);
};
const onMenuClose = (index: string) => {
  if (!menuTouchArmed) return;
  menuTouchArmed = false;
  appStore.setOpenedMenus(appStore.sidebarOpenedMenus.filter((i) => i !== index));
};
// 折叠浮层/手机抽屉的菜单被 teleport 到 body，pointerdown 冒泡不回 el-menu，
// 臂门补一层 document 级监听（只认浮层内的按下）
const onDocPointerDown = (e: PointerEvent) => {
  if ((e.target as Element)?.closest?.('.el-menu--popup')) armMenuTouch();
};
onMounted(() => document.addEventListener('pointerdown', onDocPointerDown, true));
onUnmounted(() => {
  clearTimeout(disarmTimer);
  document.removeEventListener('pointerdown', onDocPointerDown, true);
});

// 题2-② 恢复：default-openeds 在这版 Element Plus 里播种后不生效（initMenu 与子菜单注册
// 的时序对不上），改为挂载/侧栏重新展开后指令式逐个 open() 记住的分组。
// EP 的 open(index) 遇到未注册索引会直接抛 TypeError 中断循环——换账号/菜单变更后
// 存量记忆里可能有失效索引，逐条 try/catch 并顺手剪除。
const menuRef = ref();
const restoreOpenedMenus = () => {
  nextTick(() => {
    appStore.sidebarOpenedMenus.forEach((i) => {
      try {
        menuRef.value?.open(i);
      } catch {
        appStore.setOpenedMenus(appStore.sidebarOpenedMenus.filter((x) => x !== i));
      }
    });
  });
};
onMounted(restoreOpenedMenus);
watch(isCollapse, (collapsed) => {
  if (!collapsed) restoreOpenedMenus();
});
</script>
