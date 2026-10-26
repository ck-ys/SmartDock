<template>
  <el-drawer v-model="showSettings" :with-header="false" direction="rtl" size="300px" close-on-click-modal>
    <h3 class="drawer-title">菜单导航设置</h3>
    <div class="nav-wrap">
      <el-tooltip content="左侧菜单" placement="bottom">
        <div
          class="item left"
          @click="handleNavType(NavTypeEnum.LEFT)"
          :style="{ '--theme': 'var(--app-accent)' }"
          :class="{ activeItem: navType == NavTypeEnum.LEFT }"
        >
          <b></b><b></b>
        </div>
      </el-tooltip>

      <el-tooltip content="混合菜单" placement="bottom">
        <div
          class="item mix"
          @click="handleNavType(NavTypeEnum.MIX)"
          :style="{ '--theme': 'var(--app-accent)' }"
          :class="{ activeItem: navType == NavTypeEnum.MIX }"
        >
          <b></b><b></b>
        </div>
      </el-tooltip>
      <el-tooltip content="顶部菜单" placement="bottom">
        <div
          class="item top"
          @click="handleNavType(NavTypeEnum.TOP)"
          :style="{ '--theme': 'var(--app-accent)' }"
          :class="{ activeItem: navType == NavTypeEnum.TOP }"
        >
          <b></b><b></b>
        </div>
      </el-tooltip>
    </div>

    <div class="drawer-item">
      <span>纯黑夜主题</span>
      <span class="comp-style">
        <el-switch :model-value="settingsStore.dark" class="drawer-switch" @change="onDarkChange" />
      </span>
    </div>
    <div class="drawer-item">
      <span>控件圆角</span>
      <span class="comp-style">
        <el-slider v-model="radiusBase" :min="0" :max="32" :step="2" style="width: 120px" @change="radiusBaseChange" />
      </span>
    </div>

    <el-divider />

    <h3 class="drawer-title">系统布局配置</h3>

    <div class="drawer-item">
      <span>开启 Tags-Views</span>
      <span class="comp-style">
        <el-switch v-model="settingsStore.tagsView" class="drawer-switch" />
      </span>
    </div>

    <div class="drawer-item">
      <span>显示页签图标</span>
      <span class="comp-style">
        <el-switch v-model="settingsStore.tagsIcon" :disabled="!settingsStore.tagsView" class="drawer-switch" />
      </span>
    </div>

    <div class="drawer-item">
      <span>固定 Header</span>
      <span class="comp-style">
        <el-switch v-model="settingsStore.fixedHeader" class="drawer-switch" />
      </span>
    </div>

    <div class="drawer-item">
      <span>显示 Logo</span>
      <span class="comp-style">
        <el-switch v-model="settingsStore.sidebarLogo" class="drawer-switch" />
      </span>
    </div>

    <div class="drawer-item">
      <span>动态标题</span>
      <span class="comp-style">
        <el-switch v-model="settingsStore.dynamicTitle" class="drawer-switch" @change="dynamicTitleChange" />
      </span>
    </div>

    <el-divider />

    <el-button type="primary" plain icon="DocumentAdd" @click="saveSetting">保存配置</el-button>
    <el-button plain icon="Refresh" @click="resetSetting">重置配置</el-button>
  </el-drawer>
</template>

<script setup lang="ts">
import { useDynamicTitle } from '@/utils/dynamicTitle';
import { useAppStore } from '@/store/modules/app';
import { useSettingsStore } from '@/store/modules/settings';
import { usePermissionStore } from '@/store/modules/permission';
import { NavTypeEnum } from '@/enums/NavTypeEnum';
import defaultSettings from '@/settings';

const { proxy } = getCurrentInstance() as ComponentInternalInstance;
const appStore = useAppStore();
const settingsStore = useSettingsStore();
const permissionStore = usePermissionStore();

const showSettings = ref(false);
const storeSettings = computed(() => settingsStore);
const navType = ref(settingsStore.navType);
const radiusBase = ref(settingsStore.radiusBase);
// 外观状态唯一入口在 store：切换时同步 html.dark、localStorage；皮肤与强调色由皮肤令牌固定（U-24）
const onDarkChange = (value: boolean | string | number) => {
  settingsStore.setDark(Boolean(value));
};

/** 菜单导航设置 */
watch(
  () => navType,
  (val: typeof navType) => {
    if (val.value === NavTypeEnum.TOP) {
      appStore.toggleSideBarHide(true);
      permissionStore.setSidebarRouters(permissionStore.defaultRoutes as any);
    } else if (val.value === NavTypeEnum.LEFT) {
      appStore.toggleSideBarHide(false);
      permissionStore.setSidebarRouters(permissionStore.defaultRoutes as any);
    } else if (val.value === NavTypeEnum.MIX) {
      appStore.toggleSideBarHide(false);
    }
  },
  { immediate: true, deep: true }
);

const handleNavType = (val: NavTypeEnum) => {
  settingsStore.navType = val;
  navType.value = val;
};

const dynamicTitleChange = () => {
  // 动态设置网页标题
  useDynamicTitle();
};

const radiusBaseChange = (val: number) => {
  settingsStore.radiusBase = val;
  const el = document.documentElement;
  el.style.setProperty('--app-radius-base', `${val}px`);
  el.style.setProperty('--app-radius-sm', `${Math.round(val * 0.6)}px`);
  el.style.setProperty('--app-radius-md', `${val}px`);
  el.style.setProperty('--app-radius-lg', `${Math.round(val * 1.4)}px`);
  el.style.setProperty('--el-border-radius-base', `${val}px`);
  el.style.setProperty('--el-border-radius-small', `${Math.round(val * 0.6)}px`);
};
const saveSetting = () => {
  proxy?.$modal.loading('正在保存到本地，请稍候...');
  const settings = useStorage<LayoutSetting>('layout-setting', defaultSettings);
  settings.value.tagsView = storeSettings.value.tagsView;
  settings.value.tagsIcon = storeSettings.value.tagsIcon;
  settings.value.fixedHeader = storeSettings.value.fixedHeader;
  settings.value.sidebarLogo = storeSettings.value.sidebarLogo;
  settings.value.dynamicTitle = storeSettings.value.dynamicTitle;
  settings.value.sideTheme = storeSettings.value.sideTheme;
  settings.value.theme = storeSettings.value.theme;
  settings.value.accent = storeSettings.value.accent;
  settings.value.navType = storeSettings.value.navType;
  settings.value.radiusBase = storeSettings.value.radiusBase;
  setTimeout(() => {
    proxy?.$modal.closeLoading();
  }, 1000);
};
const resetSetting = () => {
  proxy?.$modal.loading('正在清除设置缓存并刷新，请稍候...');
  useStorage<any>('layout-setting', null).value = null;
  localStorage.removeItem('useDarkKey');
  setTimeout(() => window.location.reload(), 1000);
};
const openSetting = () => {
  showSettings.value = true;
};

onMounted(() => {
  radiusBaseChange(storeSettings.value.radiusBase);
});

defineExpose({
  openSetting
});
</script>

<style lang="scss" scoped>
.setting-drawer-title {
  margin-bottom: 12px;
  color: rgba(0, 0, 0, 0.85);
  line-height: 22px;
  font-weight: bold;
  .drawer-title {
    font-size: 14px;
  }
}
.setting-drawer-block-checbox {
  display: flex;
  justify-content: flex-start;
  align-items: center;
  margin-top: 10px;
  margin-bottom: 20px;

  .setting-drawer-block-checbox-item {
    position: relative;
    margin-right: 16px;
    border-radius: 2px;
    cursor: pointer;

    img {
      width: 48px;
      height: 48px;
    }

    .custom-img {
      width: 48px;
      height: 38px;
      border-radius: 5px;
      box-shadow: 1px 1px 2px #898484;
    }

    .setting-drawer-block-checbox-selectIcon {
      position: absolute;
      top: 0;
      right: 0;
      width: 100%;
      height: 100%;
      padding-top: 15px;
      padding-left: 24px;
      color: #1890ff;
      font-weight: 700;
      font-size: 14px;
    }
  }
}

.drawer-item {
  padding: 12px 0;
  font-size: 14px;

  .comp-style {
    float: right;
    margin: -3px 8px 0px 0px;
  }
}

// 导航模式
.nav-wrap {
  display: flex;
  justify-content: flex-start;
  align-items: center;
  margin-top: 10px;
  margin-bottom: 20px;

  .activeItem {
    border: 2px solid #{'var(--theme)'} !important;
  }

  .item {
    position: relative;
    margin-right: 16px;
    cursor: pointer;
    width: 56px;
    height: 48px;
    border-radius: 4px;
    /* 缩略图跟随主题：深色下不能还画成亮灰底 + 藏青块 */
    background: var(--app-raised);
    border: 2px solid transparent;
  }

  .left {
    b:first-child {
      display: block;
      height: 30%;
      background: var(--app-sheet-bg);
    }
    b:last-child {
      width: 30%;
      background: var(--app-text);
      position: absolute;
      height: 100%;
      top: 0;
      border-radius: 4px 0 0 4px;
    }
  }
  .mix {
    b:first-child {
      border-radius: 4px 4px 0 0;
      display: block;
      height: 30%;
      background: var(--app-text);
    }
    b:last-child {
      width: 30%;
      background: var(--app-text);
      position: absolute;
      height: 70%;
      border-radius: 0 0 0 4px;
    }
  }
  .top {
    b:first-child {
      display: block;
      height: 30%;
      background: var(--app-text);
      border-radius: 4px 4px 0 0;
    }
  }
}
</style>
