<template>
  <el-menu class="topbar-menu" :ellipsis="false" :default-active="activeMenu" active-text-color="var(--app-text)" mode="horizontal">
    <sidebar-item :key="route.path + index" v-for="(route, index) in topMenus" :item="route" :base-path="route.path" />

    <el-sub-menu index="more" class="el-sub-menu__hide-arrow" v-if="moreRoutes.length > 0">
      <template #title>
        <span>更多菜单</span>
      </template>
      <sidebar-item :key="route.path + index" v-for="(route, index) in moreRoutes" :item="route" :base-path="route.path" />
    </el-sub-menu>
  </el-menu>
</template>

<script setup>
import SidebarItem from '../Sidebar/SidebarItem';
import { useAppStore } from '@/store/modules/app';
import { usePermissionStore } from '@/store/modules/permission';

const route = useRoute();
const appStore = useAppStore();
const permissionStore = usePermissionStore();

const sidebarRouters = computed(() => permissionStore.sidebarRouters);
const device = computed(() => appStore.device);
const activeMenu = computed(() => {
  const { meta, path } = route;
  if (meta.activeMenu) {
    return meta.activeMenu;
  }
  return path;
});

const visibleNumber = ref(5);
const topMenus = computed(() => {
  return permissionStore.sidebarRouters.filter((f) => !f.hidden).slice(0, visibleNumber.value);
});
const moreRoutes = computed(() => {
  return permissionStore.sidebarRouters.filter((f) => !f.hidden).slice(visibleNumber.value, sidebarRouters.value.length - visibleNumber.value);
});
function setVisibleNumber() {
  let width = document.body.getBoundingClientRect().width;
  if (width >= 1000) {
    width -= 500;
  }
  visibleNumber.value = parseInt(width / 3 / 85);
}

onMounted(() => {
  window.addEventListener('resize', setVisibleNumber);
});
onBeforeUnmount(() => {
  window.removeEventListener('resize', setVisibleNumber);
});

onMounted(() => {
  setVisibleNumber();
});
</script>

<style lang="scss">
/* menu item */
#app .topbar-menu.el-menu--horizontal .el-sub-menu__title,
#app .topbar-menu.el-menu--horizontal .el-menu-item {
  padding: 0 10px !important;
}

.topbar-menu.el-menu--horizontal > .el-menu-item {
  float: left;
  height: var(--app-topbar-h) !important;
  line-height: var(--app-topbar-h) !important;
  color: var(--app-text) !important;
  padding: 0 5px !important;
  margin: 0 10px !important;
}

/* 激活项 icon+文字用主文本色——必须限定在 .topbar-menu 内：
   全局裸写 .el-sub-menu.is-active … 会命中侧栏激活组内所有叶子项（svg-icon+span 结构相同），
   T1 浅色档侧栏是深蓝壳，把兄弟菜单染成 --app-text 深墨字等于不可见（2026-10-26 U-24 回归）。
   水平菜单弹层（teleport 到 body）的激活色由 el-menu 的 active-text-color prop 负责，不走这条 CSS */
.topbar-menu .el-sub-menu.is-active .svg-icon,
.topbar-menu .el-menu-item.is-active .svg-icon + span,
.topbar-menu .el-sub-menu.is-active .svg-icon + span,
.topbar-menu .el-sub-menu.is-active .el-sub-menu__title span {
  color: var(--app-text);
}

/* sub-menu item */
.topbar-menu.el-menu--horizontal > .el-sub-menu .el-sub-menu__title {
  float: left;
  line-height: var(--app-topbar-h) !important;
  color: var(--app-text) !important;
  margin: 0 15px -3px !important;
}

/* topbar more arrow */
.topbar-menu .el-sub-menu .el-sub-menu__icon-arrow {
  position: static;
  margin-left: 8px;
  margin-top: 0px;
  display: block !important;
}

/* menu__title el-menu-item */
.topbar-menu.el-menu--horizontal .el-sub-menu__title,
.topbar-menu.el-menu--horizontal .el-menu-item {
  /* 顶栏只有 48px，菜单项写死 60px 会垂到页签行上 */
  height: var(--app-topbar-h);
}
</style>
