<template>
  <div class="sidebar-brand" :class="{ 'is-collapse': collapse }">
    <!-- 纯品牌行（2026-10-24 二次拍板 题1案2）：切换单位唯一入口在左下工作区切换器，
         这里只留品牌，点了回首页，不再出现第二个切换入口 -->
    <router-link to="/" class="sidebar-brand__row" :title="title">
      <img :src="logo" class="sidebar-brand__mark" alt="" />
      <b class="sidebar-brand__name">{{ title }}</b>
    </router-link>
  </div>
</template>

<script setup lang="ts">
import logo from '@/assets/logo/logo.png';

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

/* 品牌行是 <a>：sidebar.scss 的菜单链接规则已排除本类，这里保持一行 flex */
.sidebar-brand .sidebar-brand__row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 48px;
  padding: 0 8px;
  border: 0;
  border-radius: var(--app-nav-radius);
  background: transparent;
  color: var(--app-text);
  font-family: inherit;
  text-decoration: none;
  cursor: pointer;

  &:hover {
    background: var(--app-raised);
  }
}

.sidebar-brand__mark {
  width: 30px;
  height: 30px;
  flex: none;
  object-fit: contain;
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
