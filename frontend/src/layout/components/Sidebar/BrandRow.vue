<template>
  <div class="sidebar-brand" :class="{ 'is-collapse': collapse }">
    <!-- 超管 + 多单位：整块是单位切换器（参考实现里 workspace switcher 就在这个位置） -->
    <el-dropdown v-if="canSwitch" trigger="click" placement="bottom-start" :disabled="switching" @command="switchTo">
      <button type="button" class="sidebar-brand__row" :title="`${title}${scope ? ' · ' + scope : ''}（点击切换单位）`">
        <img :src="logo" class="sidebar-brand__mark" alt="" />
        <span class="sidebar-brand__text">
          <b class="sidebar-brand__name">{{ title }}</b>
          <span v-if="switching || scope" class="sidebar-brand__scope">{{ switching ? '切换中…' : scope }}</span>
        </span>
        <span v-if="!switching" class="sidebar-brand__switch">切换</span>
        <el-icon class="sidebar-brand__chev"><ArrowDown /></el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <li role="presentation" class="sidebar-brand__caption">切换单位</li>
          <el-dropdown-item v-for="item in otherTenants" :key="item.tenantId" :command="String(item.tenantId)">
            {{ item.companyName }}
          </el-dropdown-item>
          <el-dropdown-item v-if="!isDefaultTenant" divided command="__default">回到默认单位</el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <!-- 普通用户：品牌回首页，不显示切换入口 -->
    <router-link v-else to="/" class="sidebar-brand__row">
      <img :src="logo" class="sidebar-brand__mark" alt="" />
      <span class="sidebar-brand__text">
        <b class="sidebar-brand__name">{{ title }}</b>
        <span v-if="scope" class="sidebar-brand__scope">{{ scope }}</span>
      </span>
    </router-link>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue';
import logo from '@/assets/logo/logo.png';
import { useTenantSwitch } from '@/composables/useTenantSwitch';

defineProps<{ collapse: boolean }>();

const title = import.meta.env.VITE_APP_LOGO_TITLE;
const { canSwitch, switching, currentTenant, otherTenants, isDefaultTenant, load, switchTo } = useTenantSwitch();

// 左侧第二行：当前单位；拿不到就不显示这一行（不编造文案）
const scope = computed(() => currentTenant.value?.companyName || '');

onMounted(load);
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

.sidebar-brand :deep(.el-dropdown) {
  width: 100%;
}

/* 品牌行在普通用户分支是 <a>：sidebar.scss 的菜单链接规则已排除本类，这里保持一行 flex */
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
  text-align: left;
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

.sidebar-brand__text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  /* 文字块占满左侧剩余空间，切换提示与箭头统一靠右 */
  margin-right: auto;
  line-height: 1.25;
}

.sidebar-brand__name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.sidebar-brand__scope {
  overflow: hidden;
  color: var(--app-muted);
  font-size: 12px;
  font-weight: 400;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.sidebar-brand__chev {
  flex: none;
  color: var(--app-faint);
  font-size: 12px;
}

.sidebar-brand__switch {
  flex: none;
  padding: 1px 6px;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  color: var(--app-muted);
  font-size: 11px;
  line-height: 16px;
}

.sidebar-brand.is-collapse {
  padding: 0 4px;

  .sidebar-brand__row {
    justify-content: center;
    padding: 0;
  }

  .sidebar-brand__text,
  .sidebar-brand__switch,
  .sidebar-brand__chev {
    display: none;
  }
}
</style>

<style lang="scss">
/* 下拉是 teleport 到 body 的，scoped 选择器打不到；用带前缀的全局类，只影响本组件的小标题 */
.el-dropdown-menu .sidebar-brand__caption {
  padding: 6px 12px 4px;
  color: var(--app-faint);
  font-size: 12px;
  line-height: 1.4;
  cursor: default;
}
</style>
