<template>
  <div class="sidebar-user" :class="{ 'is-collapse': collapse }">
    <!-- 单位行（2026-10-24 拍板 题1-A：账号归账号、单位归单位）：超管且多单位才有 -->
    <el-dropdown v-if="canSwitch" trigger="click" placement="top-start" :disabled="switching" @command="switchTo">
      <button type="button" class="sidebar-user__unit" :title="`${unitName}（点击切换单位）`">
        <el-icon class="sidebar-user__unit-ic"><OfficeBuilding /></el-icon>
        <span class="sidebar-user__unit-name">{{ switching ? '切换中…' : unitName }}</span>
        <span v-if="!switching" class="sidebar-user__switch" aria-hidden="true">切换</span>
        <el-icon class="sidebar-user__unit-chev"><DCaret /></el-icon>
      </button>
      <template #dropdown>
        <el-dropdown-menu>
          <li role="presentation" class="sidebar-user__caption">切换单位</li>
          <li role="presentation" class="sidebar-user__current">
            <el-icon class="sidebar-user__check"><Check /></el-icon>
            <span class="sidebar-user__current-name">{{ unitName }}</span>
            <span v-if="isDefaultTenant" class="sidebar-user__tag">默认</span>
          </li>
          <el-dropdown-item
            v-for="item in unitOptions"
            :key="item.tenantId"
            :command="String(item.tenantId) === defaultId ? '__default' : String(item.tenantId)"
            class="sidebar-user__item"
          >
            <span class="sidebar-user__opt">{{ item.companyName }}</span>
            <span v-if="String(item.tenantId) === defaultId" class="sidebar-user__tag">默认</span>
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
    <!-- 账号行：菜单里只有账号的事（单位切换在上一行） -->
    <div class="sidebar-user__menu">
      <dcs-user-menu placement="top-start" @layout="emit('set-layout')" @feedback="emit('feedback')">
        <button type="button" class="sidebar-user__row" :aria-label="`账号菜单，${nickname}`">
          <img v-if="avatar" :src="avatar" class="sidebar-user__avatar" alt="" />
          <span v-else class="sidebar-user__avatar sidebar-user__avatar--text">{{ initial }}</span>
          <span class="sidebar-user__text">
            <b class="sidebar-user__name">{{ nickname || '当前账号' }}</b>
            <span v-if="meta" class="sidebar-user__meta">{{ meta }}</span>
          </span>
          <el-icon class="sidebar-user__chev"><ArrowUp /></el-icon>
        </button>
      </dcs-user-menu>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue';
import DcsUserMenu from '@/components/DcsUserMenu/index.vue';
import { useUserStore } from '@/store/modules/user';
import { useWorkspace } from '@/composables/useWorkspace';
import { useTenantSwitch } from '@/composables/useTenantSwitch';

defineProps<{ collapse: boolean }>();
const emit = defineEmits<{ 'set-layout': []; feedback: [] }>();

const userStore = useUserStore();
const { tenantName } = useWorkspace();
const { canSwitch, switching, currentTenant, isDefaultTenant, defaultId, unitOptions, load, switchTo } =
  useTenantSwitch();

const nickname = computed(() => userStore.nickname);
const avatar = computed(() => userStore.avatar);
const initial = computed(() => (nickname.value || '?').slice(0, 1).toUpperCase());
// 角色用现有角色码翻译：超管 admin，其余统一叫「成员」，不臆造岗位名
const roleLabel = computed(() => (userStore.roles.includes('superadmin') ? '管理员' : '成员'));
// 超管的单位已在上一行常驻显示，账号行不再重复；普通成员没有单位行，副文字保留单位
const meta = computed(() =>
  canSwitch.value
    ? ''
    : [roleLabel.value === nickname.value ? '' : roleLabel.value, tenantName.value].filter(Boolean).join(' · ')
);

// 单位行显示名：优先租户列表里的当前单位，加载前退回工作区名称，不编造
const unitName = computed(() => currentTenant.value?.companyName || tenantName.value || '当前单位');

// canSwitch 要等租户列表回来才知道，load 内部自判超管，这里无条件触发
onMounted(load);
</script>

<style lang="scss" scoped>
.sidebar-user {
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 8px 0;
  border-top: 1px solid var(--app-rule);
  box-sizing: border-box;
}

.sidebar-user :deep(.el-dropdown) {
  width: 100%;
}

/* 单位行：与菜单行同一套几何（38 高 / 药丸 8 / 左右 inset 6） */
.sidebar-user__unit {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  margin: 0 var(--app-nav-inset);
  height: var(--app-nav-row-h);
  padding: 0 12px;
  border: 0;
  border-radius: var(--app-nav-radius);
  background: transparent;
  color: var(--app-text);
  font-family: inherit;
  text-align: left;
  cursor: pointer;

  &:hover {
    background: var(--app-raised);
  }

  &:focus-visible {
    outline: 2px solid var(--app-accent);
    outline-offset: -2px;
  }
}

.sidebar-user__unit-ic {
  flex: none;
  color: var(--app-muted);
  font-size: var(--app-nav-icon);
}

.sidebar-user__unit-name {
  min-width: 0;
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.sidebar-user__switch {
  flex: none;
  margin-left: auto;
  padding: 1px 6px;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  color: var(--app-muted);
  font-size: 11px;
  line-height: 16px;
}

.sidebar-user__unit-chev {
  flex: none;
  color: var(--app-faint);
  font-size: 12px;
}

.sidebar-user__menu {
  width: 100%;
}

.sidebar-user__row {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  height: 44px;
  margin: 0 var(--app-nav-inset);
  padding: 0 12px;
  border: 0;
  border-radius: var(--app-nav-radius);
  background: transparent;
  color: var(--app-text);
  font-family: inherit;
  text-align: left;
  cursor: pointer;

  &:hover {
    background: var(--app-raised);
  }

  &:focus-visible {
    outline: 2px solid var(--app-accent);
    outline-offset: -2px;
  }
}

.sidebar-user__avatar {
  width: 32px;
  height: 32px;
  flex: none;
  border-radius: 10px;
  object-fit: cover;
  background: var(--app-raised);
}

.sidebar-user__avatar--text {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--app-muted);
  font-size: 12px;
  font-weight: 600;
}

.sidebar-user__text {
  display: flex;
  flex-direction: column;
  min-width: 0;
  /* 文字块占满左侧剩余空间，箭头统一靠右 */
  margin-right: auto;
  line-height: 1.25;
}

.sidebar-user__name {
  overflow: hidden;
  font-size: 14px;
  font-weight: 600;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.sidebar-user__meta {
  overflow: hidden;
  color: var(--app-muted);
  font-size: 12px;
  font-weight: 400;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.sidebar-user__chev {
  flex: none;
  color: var(--app-faint);
  font-size: 12px;
}

.sidebar-user.is-collapse {
  padding: 8px 0;

  .sidebar-user__unit {
    justify-content: center;
    padding: 0;
  }

  .sidebar-user__unit-name,
  .sidebar-user__switch,
  .sidebar-user__unit-chev {
    display: none;
  }

  .sidebar-user__row {
    justify-content: center;
    padding: 0;
  }

  .sidebar-user__text,
  .sidebar-user__chev {
    display: none;
  }
}
</style>

<style lang="scss">
/* 浮层 teleport 到 body，scoped 打不到：全局类都带 sidebar-user__ 前缀，只影响本组件 */
.el-dropdown-menu .sidebar-user__caption {
  padding: 6px 12px 4px;
  color: var(--app-faint);
  font-size: 12px;
  line-height: 1.4;
  cursor: default;
}

/* 当前单位行：不可点，只作「你在哪」的锚 */
.el-dropdown-menu .sidebar-user__current {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 32px;
  padding: 0 12px;
  border-radius: 6px;
  color: var(--app-text);
  font-size: 14px;
  font-weight: 600;
  cursor: default;
}

.el-dropdown-menu .sidebar-user__check {
  flex: none;
  color: var(--app-accent);
  font-size: 14px;
}

.el-dropdown-menu .sidebar-user__current-name {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

/* 「默认」小标记：单位名右侧对齐 */
.el-dropdown-menu .sidebar-user__tag {
  flex: none;
  margin-left: auto;
  padding: 0 5px;
  border-radius: 4px;
  background: color-mix(in srgb, var(--app-accent) 8%, transparent);
  color: var(--app-accent);
  font-size: 11px;
  font-weight: 400;
  line-height: 18px;
}

/* 暗色下提亮一档，避免深底深字（2026-10-24 视觉席 P2 回修） */
html.dark .el-dropdown-menu .sidebar-user__tag {
  background: color-mix(in srgb, var(--app-accent) 22%, transparent);
  color: color-mix(in srgb, var(--app-accent) 55%, #ffffff);
}

/* 可切单位行：名字占左、标记靠右 */
.el-dropdown-menu .el-dropdown-menu__item.sidebar-user__item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.el-dropdown-menu .sidebar-user__opt {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}
</style>
