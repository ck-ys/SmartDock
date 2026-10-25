<template>
  <div class="sidebar-user" :class="{ 'is-collapse': collapse }">
    <dcs-user-menu
      placement="top-start"
      show-tenant-switch
      class="sidebar-user__menu"
      @layout="emit('set-layout')"
      @feedback="emit('feedback')"
    >
      <button type="button" class="sidebar-user__row" aria-label="账号菜单" :title="`${nickname}${meta ? ' · ' + meta : ''}`">
        <img v-if="avatar" :src="avatar" class="sidebar-user__avatar" alt="" />
        <span v-else class="sidebar-user__avatar sidebar-user__avatar--text">{{ initial }}</span>
        <span class="sidebar-user__text">
          <b class="sidebar-user__name">{{ nickname || '当前账号' }}</b>
          <span v-if="meta" class="sidebar-user__meta">{{ meta }}</span>
        </span>
        <span v-if="canSwitch" class="sidebar-user__switch">切换</span>
        <el-icon class="sidebar-user__chev"><DCaret /></el-icon>
      </button>
    </dcs-user-menu>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import DcsUserMenu from '@/components/DcsUserMenu/index.vue';
import { useUserStore } from '@/store/modules/user';
import { useWorkspace } from '@/composables/useWorkspace';
import { useTenantSwitch } from '@/composables/useTenantSwitch';

defineProps<{ collapse: boolean }>();
const emit = defineEmits<{ 'set-layout': []; feedback: [] }>();

const userStore = useUserStore();
const { tenantName } = useWorkspace();
// 租户列表由子菜单加载；这里只取「能不能切」决定要不要在行里给提示
const { canSwitch } = useTenantSwitch();

const nickname = computed(() => userStore.nickname);
const avatar = computed(() => userStore.avatar);
const initial = computed(() => (nickname.value || '?').slice(0, 1).toUpperCase());
// 角色用现有角色码翻译：超管 admin，其余统一叫「成员」，不臆造岗位名
const roleLabel = computed(() => (userStore.roles.includes('superadmin') ? '管理员' : '成员'));
// 昵称本身就叫「管理员」时不再重复角色，避免出现「管理员 / 管理员 · 演示单位」
const meta = computed(() => [roleLabel.value === nickname.value ? '' : roleLabel.value, tenantName.value].filter(Boolean).join(' · '));
</script>

<style lang="scss" scoped>
.sidebar-user {
  flex: none;
  height: var(--app-topbar-h);
  display: flex;
  align-items: center;
  padding: 0 8px;
  border-top: 1px solid var(--app-rule);
  box-sizing: border-box;
}

.sidebar-user__menu {
  width: 100%;
}

.sidebar-user__row {
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
  cursor: pointer;

  &:hover {
    background: var(--app-raised);
  }

  &:focus-visible {
    outline: 2px solid var(--app-control);
    outline-offset: -2px;
  }
}

.sidebar-user__avatar {
  width: 36px;
  height: 36px;
  flex: none;
  border-radius: 12px;
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

.sidebar-user__switch {
  margin-left: auto;
  flex: none;
  padding: 1px 6px;
  border: 1px solid var(--app-border);
  border-radius: 6px;
  color: var(--app-muted);
  font-size: 11px;
  line-height: 16px;
}

.sidebar-user__chev {
  flex: none;
  color: var(--app-faint);
  font-size: 14px;
}

.sidebar-user.is-collapse {
  padding: 0 4px;

  .sidebar-user__row {
    justify-content: center;
    padding: 0;
  }

  .sidebar-user__text,
  .sidebar-user__switch,
  .sidebar-user__chev {
    display: none;
  }
}
</style>
