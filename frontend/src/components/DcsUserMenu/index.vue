<template>
  <el-dropdown trigger="click" :placement="placement" @command="onCommand">
    <slot />
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item v-if="showProfile" command="profile">{{ t('navbar.personalCenter') }}</el-dropdown-item>
        <el-dropdown-item v-if="showLayout" command="layout">{{ t('navbar.layoutSetting') }}</el-dropdown-item>
        <el-dropdown-item command="feedback">反馈问题</el-dropdown-item>
        <el-dropdown-item divided command="logout" class="dcs-user-menu__logout">{{ t('navbar.logout') }}</el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup lang="ts">
import { useAccountMenu } from '@/composables/useAccountMenu';

/**
 * 账号菜单（顶栏头像用）：动作逻辑在 useAccountMenu composable，与左下工作区面板共用同一份，
 * 改一处两处同步。个人中心 / 退出在 composable 自处理；布局设置和反馈交给宿主
 * （一个要开设置抽屉，一个要弹反馈框）。
 */
const props = withDefaults(
  defineProps<{
    placement?: 'bottom-end' | 'top-start' | 'top' | 'bottom';
    hideProfile?: boolean;
  }>(),
  { placement: 'bottom-end', hideProfile: false }
);

const emit = defineEmits<{ layout: []; feedback: [] }>();

const { showLayout, showProfile, goProfile, logout, t } = useAccountMenu({ hideProfile: props.hideProfile });

const onCommand = (command: string) => {
  if (command === 'profile') {
    goProfile();
    return;
  }
  if (command === 'layout') {
    emit('layout');
    return;
  }
  if (command === 'feedback') {
    emit('feedback');
    return;
  }
  if (command === 'logout') {
    void logout();
  }
};

defineOptions({ name: 'DcsUserMenu' });
</script>

<style lang="scss">
/* 退出登录警示红：顶栏下拉与左下面板同一口径（浮层 teleport 到 body，全局类只挂本组件前缀） */
.el-dropdown-menu .dcs-user-menu__logout {
  color: var(--el-color-danger);

  &:hover,
  &:not(.is-disabled):focus-visible {
    color: var(--el-color-danger);
  }
}
</style>
