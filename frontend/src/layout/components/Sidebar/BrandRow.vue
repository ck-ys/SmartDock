<template>
  <div class="sidebar-brand" :class="{ 'is-collapse': collapse }">
    <!-- 纯品牌行（2026-10-24 二次拍板 题1案2）：切换单位唯一入口在左下工作区切换器，
         这里只留品牌，点了回首页，不再出现第二个切换入口。
         U-24 皮肤批：旧彩色风车 logo.png 退役，改单色几何字母标（颜色随皮肤令牌 mark/markFg） -->
    <router-link to="/" class="sidebar-brand__row" :title="title">
      <i class="dcs-mark sidebar-brand__mark" aria-hidden="true">S</i>
      <b class="sidebar-brand__name">{{ title }}</b>
    </router-link>
  </div>
</template>

<script setup lang="ts">
defineProps<{ collapse: boolean }>();

const title = import.meta.env.VITE_APP_LOGO_TITLE;
</script>

<style lang="scss" scoped>
.sidebar-brand {
  flex: none;
  height: var(--app-topbar-h);
  display: flex;
  align-items: center;
  padding: 0 8px;
  box-sizing: border-box;
}

/* 品牌行是 <a>：sidebar.scss 的菜单链接规则已排除本类，这里保持一行 flex。
   左内边距 10px：外层 .sidebar-brand 8px + 本行 10px = logo 左缘 x=18，与菜单图标左缘同一竖轴（题1-④） */
.sidebar-brand__row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 48px;
  padding: 0 8px 0 10px;
  border: 0;
  border-radius: var(--app-nav-radius);
  background: transparent;
  /* 品牌名随侧栏文字令牌：深蓝侧栏/白侧栏/两套深色都读 sideText */
  color: var(--app-side-text);
  font-family: inherit;
  text-decoration: none;
  cursor: pointer;

  &:hover {
    background: var(--app-side-hover);
  }
}

/* 单色几何字母标：底/字色全走 --app-mark-bg/fg（四套皮肤各一档），字号 28px 盒子配 15px 字 */
.sidebar-brand__mark {
  width: 28px;
  height: 28px;
}

.sidebar-brand__name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.sidebar-brand.is-collapse {
  padding: 0 4px;

  .sidebar-brand__row {
    justify-content: center;
    padding: 0;
  }

  .sidebar-brand__name {
    display: none;
  }
}
</style>
