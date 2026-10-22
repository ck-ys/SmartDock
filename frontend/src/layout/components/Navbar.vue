<template>
  <div class="navbar" :class="'nav' + navType">
    <hamburger id="hamburger-container" :is-active="appStore.sidebar.opened" class="hamburger-container" @toggle-click="toggleSideBar" />

    <breadcrumb v-if="navType == NavTypeEnum.LEFT" id="breadcrumb-container" class="breadcrumb-container" />
    <top-nav v-if="navType == NavTypeEnum.MIX" id="topmenu-container" class="topmenu-container" />

    <template v-if="navType == NavTypeEnum.TOP">
      <logo v-show="showLogo" :collapse="false"></logo>
      <top-bar id="topbar-container" class="topbar-container" />
    </template>
    <div class="right-menu flex align-center">
      <!-- 波 0：手机顶栏保留「搜索 + 消息」两条高频入口（此前整块被 device 判断隐藏，
           手机上既看不到未读也搜不到菜单）；租户切换/全屏/语言/字号/仓库链接仍只在桌面出现 -->
      <el-select
        v-if="appStore.device !== 'mobile' && userId === 1 && tenantEnabled"
        v-model="companyName"
        class="min-w-244px mr-2"
        clearable
        filterable
        reserve-keyword
        :loading="tenantSwitching"
        :placeholder="proxy.$t('navbar.selectTenant')"
        @change="dynamicTenantEvent"
        @clear="dynamicClearEvent"
      >
        <el-option v-for="item in tenantList" :key="item.tenantId" :label="item.companyName" :value="item.tenantId"> </el-option>
        <template #prefix><svg-icon icon-class="company" class="el-input__icon input-icon" /></template>
      </el-select>

      <search-menu ref="searchMenuRef" />
      <el-tooltip content="搜索" effect="dark" placement="bottom">
        <div class="right-menu-item hover-effect" @click="openSearchMenu">
          <svg-icon class-name="search-icon" icon-class="search" />
        </div>
      </el-tooltip>
      <!-- 消息 -->
      <el-tooltip :content="proxy.$t('navbar.message')" effect="dark" placement="bottom">
        <div style="display: flex; align-items: center">
          <el-popover placement="bottom" trigger="click" transition="el-zoom-in-top" :width="300" :persistent="false">
            <template #reference>
              <el-badge :value="newNotice > 0 ? newNotice : ''" :max="99">
                <div class="right-menu-item hover-effect"><svg-icon icon-class="message" /></div>
              </el-badge>
            </template>
            <template #default>
              <notice></notice>
            </template>
          </el-popover>
        </div>
      </el-tooltip>
      <template v-if="appStore.device !== 'mobile'">
        <!-- 反馈问题（三件套 2026-09-24：全员入口，弹窗内提交/追踪两页签） -->
        <el-tooltip content="反馈问题" effect="dark" placement="bottom"></el-tooltip>
        <el-tooltip v-if="showRepositoryLink" content="Github" effect="dark" placement="bottom">
          <ruo-yi-git id="ruoyi-git" class="right-menu-item hover-effect" />
        </el-tooltip>

        <!-- RuoYi 框架"项目文档"外链入口已删(2026-09-24 业务反馈#5): 点了跳框架官网, 与本系统无关。
             RuoYiDoc 组件文件保留(框架自动注册, 无引用零开销); Github 入口此前已由 showRepositoryLink=false 隐藏。 -->

        <el-tooltip :content="proxy.$t('navbar.full')" effect="dark" placement="bottom">
          <screenfull id="screenfull" class="right-menu-item hover-effect" />
        </el-tooltip>

        <el-tooltip :content="proxy.$t('navbar.language')" effect="dark" placement="bottom">
          <lang-select id="lang-select" class="right-menu-item hover-effect" />
        </el-tooltip>

        <el-tooltip :content="proxy.$t('navbar.layoutSize')" effect="dark" placement="bottom">
          <size-select id="size-select" class="right-menu-item hover-effect" />
        </el-tooltip>
      </template>
      <div class="avatar-container">
        <el-dropdown class="right-menu-item hover-effect" trigger="click" @command="handleCommand">
          <div class="avatar-wrapper">
            <img :src="userStore.avatar" class="user-avatar" />
            <el-icon><caret-bottom /></el-icon>
          </div>
          <template #dropdown>
            <el-dropdown-menu>
              <router-link v-if="!dynamic" to="/user/profile">
                <el-dropdown-item>{{ proxy.$t('navbar.personalCenter') }}</el-dropdown-item>
              </router-link>
              <el-dropdown-item v-if="settingsStore.showSettings" command="setLayout">
                <span>{{ proxy.$t('navbar.layoutSetting') }}</span>
              </el-dropdown-item>
              <el-dropdown-item divided command="logout">
                <span>{{ proxy.$t('navbar.logout') }}</span>
              </el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import SearchMenu from './TopBar/search.vue';
import { useAppStore } from '@/store/modules/app';
import { useUserStore } from '@/store/modules/user';
import { useSettingsStore } from '@/store/modules/settings';
import { useNoticeStore } from '@/store/modules/notice';
import { getTenantList } from '@/api/login';
import { dynamicClear, dynamicTenant } from '@/api/system/tenant';
import { TenantVO } from '@/api/types';
import notice from './notice/index.vue';
import { ElMessage } from 'element-plus';
import { ElMessageBoxOptions } from 'element-plus/es/components/message-box/src/message-box.type';
import { NavTypeEnum } from '@/enums/NavTypeEnum';
import Logo from '@/layout/components/Sidebar/Logo.vue';
import TopBar from './TopBar/index.vue';

const appStore = useAppStore();
const userStore = useUserStore();
const settingsStore = useSettingsStore();
const noticeStore = useNoticeStore();
const newNotice = computed(() => noticeStore.state.unreadCount);
// 暂时隐藏框架源码仓库入口，保留组件与目标地址，便于后续恢复。
const showRepositoryLink = false;

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
/** 反馈弹窗开关（三件套 2026-09-24） */

const userId = ref(userStore.userId);
const navType = computed(() => settingsStore.navType);
const showLogo = computed(() => settingsStore.sidebarLogo);

const companyName = ref(undefined);
const tenantList = ref<TenantVO[]>([]);
// 是否切换了租户
const dynamic = ref(false);
// 租户开关
const tenantEnabled = ref(true);
// 搜索菜单
const searchMenuRef = ref<InstanceType<typeof SearchMenu>>();

const openSearchMenu = () => {
  searchMenuRef.value?.openSearch();
};

// 动态切换
// 业务反馈批1015b Q7：切租户全程可见——下拉 loading + 切完成功提示（新租户看板冷启约 2~4s，
// 此前零反馈像"卡掉了"；失败提示并回滚选中值）
const tenantSwitching = ref(false);

// 动态切换
const dynamicTenantEvent = async (tenantId: string) => {
  if (companyName.value != null && companyName.value !== '') {
    tenantSwitching.value = true;
    try {
      await dynamicTenant(tenantId);
      dynamic.value = true;
      await proxy?.$router.push('/');
      await proxy?.$tab.closeAllPage();
      await proxy?.$tab.refreshPage();
      const hit = tenantList.value.find((t) => t.tenantId === tenantId);
      ElMessage.success(`已切换到「${hit?.companyName || tenantId}」，数据加载中`);
    } catch {
      ElMessage.error('切换租户失败，请重试');
    } finally {
      tenantSwitching.value = false;
    }
  }
};

const dynamicClearEvent = async () => {
  tenantSwitching.value = true;
  try {
    await dynamicClear();
    dynamic.value = false;
    await proxy?.$router.push('/');
    await proxy?.$tab.closeAllPage();
    await proxy?.$tab.refreshPage();
    ElMessage.success('已回到默认租户');
  } catch {
    ElMessage.error('切回默认租户失败，请重试');
  } finally {
    tenantSwitching.value = false;
  }
};

/** 租户列表 */
const initTenantList = async () => {
  const { data } = await getTenantList(true);
  tenantEnabled.value = data.tenantEnabled === undefined ? true : data.tenantEnabled;
  if (tenantEnabled.value) {
    tenantList.value = data.voList;
  }
};

defineExpose({
  initTenantList
});

const toggleSideBar = () => {
  appStore.toggleSideBar(false);
};

const logout = async () => {
  await ElMessageBox.confirm('确定注销并退出系统吗？', '提示', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  } as ElMessageBoxOptions);
  userStore.logout().then(() => {
    // 换账号弹回无权页面修复(2026-09-29): 主动退出=换人/收工, 整页跳登录页且不带 redirect——
    // 整页刷新一次性清掉上个账号的内存残留(页签卡片/动态路由/字典与通知缓存), 免挨个手工清;
    // 同时清掉 401 场景可能遗留的 redirect 归属章, 防上个账号的章误伤本次登录
    sessionStorage.removeItem('dcs:login-redirect-owner');
    location.href = import.meta.env.VITE_APP_CONTEXT_PATH + 'login';
  });
};

const emits = defineEmits(['setLayout']);
const setLayout = () => {
  emits('setLayout');
};
// 定义Command方法对象 通过key直接调用方法
const commandMap: { [key: string]: any } = {
  setLayout,
  logout
};
const handleCommand = (command: string) => {
  // 判断是否存在该方法
  if (commandMap[command]) {
    commandMap[command]();
  }
};
//用深度监听 消息
onMounted(() => {
  // PERF-HOME(2026-09-19): 只调 fetchRecent——其内部已并行刷未读数; 此处再单发一次会让
  // 冷启动 unread-count 翻倍(实测三路并发之一)
  noticeStore.fetchRecent();
});
</script>

<style lang="scss" scoped>
.navbar.navtop {
  .hamburger-container {
    display: none !important;
  }
}

:deep(.el-select .el-input__wrapper) {
  height: 30px;
}

:deep(.el-badge__content.is-fixed) {
  top: 12px;
  // "99+"等宽角标默认 translateX(100%) 外溢到容器右缘外，会盖住右侧相邻图标
  // （2026-09-24 实锤：反馈入口被消息未读角标完全遮挡）——收回容器右上角内侧，盖自己不盖邻居
  right: 2px;
  transform: translateY(-50%);
}

.flex {
  display: flex;
}

.align-center {
  align-items: center;
}

.navbar {
  height: 50px;
  overflow: hidden;
  position: relative;
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-lighter);
  box-shadow: none;
  display: flex;
  align-items: center;
  // padding: 0 8px;
  box-sizing: border-box;

  .hamburger-container {
    line-height: 46px;
    height: 100%;
    //float: left;
    cursor: pointer;
    transition: background 0.3s;
    -webkit-tap-highlight-color: transparent;
    display: flex;
    align-items: center;
    flex-shrink: 0;
    margin-right: 8px;

    &:hover {
      background: var(--el-fill-color-lighter);
    }
  }

  .breadcrumb-container {
    //float: left;
    flex-shrink: 0;
  }

  .topmenu-container {
    position: absolute;
    left: 50px;
  }

  .topbar-container {
    flex: 1;
    min-width: 0;
    display: flex;
    align-items: center;
    overflow: hidden;
    margin-left: 8px;
  }

  .errLog-container {
    display: inline-block;
    vertical-align: top;
  }

  .right-menu {
    height: 100%;
    display: flex;
    align-items: center;
    margin-left: auto;

    &:focus {
      outline: none;
    }

    .right-menu-item {
      display: inline-flex;
      align-items: center;
      justify-content: center;
      padding: 0 8px;
      height: 32px;
      font-size: 18px;
      color: var(--el-text-color-regular);
      border-radius: var(--app-radius-md);

      &.hover-effect {
        cursor: pointer;
        transition:
          background 0.2s ease,
          color 0.2s ease;

        &:hover {
          background: var(--el-fill-color-light);
          color: var(--el-color-primary);
        }
      }
    }

    .avatar-container {
      margin-right: 40px;

      .avatar-wrapper {
        margin-top: 0;
        position: relative;

        .user-avatar {
          cursor: pointer;
          width: 40px;
          height: 40px;
          border-radius: var(--app-radius-md);
          margin-top: 0;
          display: block;
        }

        i {
          cursor: pointer;
          position: absolute;
          right: -20px;
          top: 25px;
          font-size: 12px;
        }
      }
    }
  }

  /* 波 0：手机顶栏触控面 ≥44px（图标视觉大小不变），并收紧头像右侧留白；
     波 0.1：断点 768→992 对齐壳层设备判定（width-1<992 即手机形态，iPad 竖屏/手机横屏同享补偿） */
  /* 顶部安全区：状态栏/刘海不吃进顶栏内容（env 在桌面/无刘海设备恒 0）；
     AppMain 的高度扣减在所有宽度都消费 --sp-top，这里同口径，避免宽屏错位 */
  height: calc(50px + var(--sp-top, 0px));
  padding-top: var(--sp-top, 0px);

  @media (max-width: 992px) {
    .right-menu {
      gap: 2px;

      .right-menu-item {
        min-width: 44px;
        height: 44px;
        padding: 0 6px;
      }

      .avatar-container {
        /* 头像右侧 22px：给下拉箭头（right:-20px）留位，8px 会让箭头被 navbar overflow 裁掉 */
        margin-right: 22px;
      }
    }
  }
}
</style>
