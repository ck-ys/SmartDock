<template>
  <div class="sidebar-user" :class="{ 'is-collapse': collapse }">
    <dcs-user-menu placement="top-start" class="sidebar-user__menu" @layout="emit('set-layout')" @feedback="emit('feedback')">
      <button type="button" class="sidebar-user__row" :title="`${nickname}${meta ? ' · ' + meta : ''}`">
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
</template>

<script setup lang="ts">
import { computed } from 'vue';
import DcsUserMenu from '@/components/DcsUserMenu/index.vue';
import { useUserStore } from '@/store/modules/user';
import { useWorkspace } from '@/composables/useWorkspace';

defineProps<{ collapse: boolean }>();
const emit = defineEmits<{ 'set-layout': []; feedback: [] }>();

const userStore = useUserStore();
const { tenantName } = useWorkspace();

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
  margin-left: auto;
  flex: none;
  color: var(--app-faint);
  font-size: 12px;
}

.sidebar-user.is-collapse {
  padding: 0 4px;

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
