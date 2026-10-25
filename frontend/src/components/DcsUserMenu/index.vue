<template>
  <el-dropdown trigger="click" :placement="placement" @command="onCommand">
    <slot />
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item v-if="showProfile" command="profile">{{ t('navbar.personalCenter') }}</el-dropdown-item>
        <el-dropdown-item v-if="showLayout" command="layout">{{ t('navbar.layoutSetting') }}</el-dropdown-item>
        <el-dropdown-item command="feedback">反馈问题</el-dropdown-item>
        <el-dropdown-item divided command="logout">{{ t('navbar.logout') }}</el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup lang="ts">
import { computed } from 'vue';
import { useRouter } from 'vue-router';
import { useI18n } from 'vue-i18n';
import { useSettingsStore } from '@/store/modules/settings';
import { useUserStore } from '@/store/modules/user';
import { useWorkspace } from '@/composables/useWorkspace';
import { useTenantSwitch } from '@/composables/useTenantSwitch';
import { ElMessageBox } from 'element-plus';
import type { ElMessageBoxOptions } from 'element-plus/es/components/message-box/src/message-box.type';

/**
 * 账号菜单：顶栏头像与左下账号行共用同一份，改一处两处同步。
 * 个人中心 / 退出在这里自处理；布局设置和反馈交给宿主（一个要开设置抽屉，一个要弹反馈框）。
 * 菜单里只有账号的事——单位切换在侧栏底部独立的单位行（2026-10-24 拍板 题1-A）。
 */
const props = withDefaults(
  defineProps<{
    placement?: 'bottom-end' | 'top-start' | 'top' | 'bottom';
    hideProfile?: boolean;
  }>(),
  { placement: 'bottom-end', hideProfile: false }
);

const emit = defineEmits<{ layout: []; feedback: [] }>();

const router = useRouter();
const { t } = useI18n();
const settingsStore = useSettingsStore();
const userStore = useUserStore();

const { tenantSwitched } = useWorkspace();
const { forget } = useTenantSwitch();
const showLayout = computed(() => settingsStore.showSettings);
// 切到非默认租户后不再给「个人中心」入口（与旧顶栏同口径：那里看到的是默认租户的人）
const showProfile = computed(() => !props.hideProfile && !tenantSwitched.value);

const logout = async () => {
  await ElMessageBox.confirm('确定注销并退出系统吗？', '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  } as ElMessageBoxOptions);
  userStore.logout().then(() => {
    // 服务端登出会清动态租户，本地镜像同步清
    forget();
    // 换账号弹回无权页面修复(2026-09-29): 主动退出=换人/收工, 整页跳登录页且不带 redirect——
    // 整页刷新一次性清掉上个账号的内存残留(页签卡片/动态路由/字典与通知缓存), 免挨个手工清;
    // 同时清掉 401 场景可能遗留的 redirect 归属章, 防上个账号的章误伤本次登录
    sessionStorage.removeItem('dcs:login-redirect-owner');
    location.href = import.meta.env.VITE_APP_CONTEXT_PATH + 'login';
  });
};

const onCommand = (command: string) => {
  if (command === 'profile') {
    router.push('/user/profile');
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

