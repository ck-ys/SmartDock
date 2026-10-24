import { defineStore } from 'pinia';
import defaultSettings from '@/settings';
import { useDynamicTitle } from '@/utils/dynamicTitle';
import { useStorage } from '@vueuse/core';
import { ref } from 'vue';
import { NavTypeEnum } from '@/enums/NavTypeEnum';
import { SideThemeEnum } from '@/enums/SideThemeEnum';
import { applyThemeMode, DARK_STORAGE_KEY, DEFAULT_ACCENT } from '@/utils/theme';

export const useSettingsStore = defineStore('setting', () => {
  const storageSetting = useStorage<LayoutSetting>('layout-setting', {
    tagsView: defaultSettings.tagsView,
    tagsIcon: defaultSettings.tagsIcon,
    fixedHeader: defaultSettings.fixedHeader,
    sidebarLogo: defaultSettings.sidebarLogo,
    dynamicTitle: defaultSettings.dynamicTitle,
    sideTheme: defaultSettings.sideTheme,
    theme: defaultSettings.theme,
    accent: defaultSettings.accent,
    navType: defaultSettings.navType,
    radiusBase: defaultSettings.radiusBase
  });
  const title = ref<string>(defaultSettings.title);
  const theme = ref<string>(defaultSettings.theme);
  const sideTheme = ref<string>(defaultSettings.sideTheme);
  const showSettings = ref<boolean>(defaultSettings.showSettings);
  const tagsView = ref<boolean>(storageSetting.value.tagsView);
  const tagsIcon = ref<boolean>(storageSetting.value.tagsIcon);
  const fixedHeader = ref<boolean>(storageSetting.value.fixedHeader);
  const sidebarLogo = ref<boolean>(storageSetting.value.sidebarLogo);
  const dynamicTitle = ref<boolean>(storageSetting.value.dynamicTitle);
  const animationEnable = ref<boolean>(defaultSettings.animationEnable);
  const accent = ref<string>(storageSetting.value.accent || defaultSettings.accent || DEFAULT_ACCENT);
  // 单一状态源：html.dark 与 localStorage 都由 applyThemeMode 同步，避免 useDark 与 store 各写一份
  const dark = ref<boolean>(readInitialDark());
  const navType = ref<NavTypeEnum>(storageSetting.value.navType || NavTypeEnum.LEFT);
  const radiusBase = ref<number>(storageSetting.value.radiusBase ?? defaultSettings.radiusBase);

  function readInitialDark(): boolean {
    try {
      return localStorage.getItem(DARK_STORAGE_KEY) === 'dark';
    } catch {
      return false;
    }
  }

  const setTitle = (value: string) => {
    title.value = value;
    useDynamicTitle();
  };

  /** 切换框架纸 / 纯黑夜（唯一入口：导航栏按钮、设置抽屉、启动时都走这里） */
  const setDark = (value: boolean) => {
    dark.value = value;
    sideTheme.value = value ? SideThemeEnum.DARK : SideThemeEnum.LIGHT;
    applyThemeMode(value, accent.value);
  };

  /** 数据强调色只影响图表/数据标记；深色主题固定青柠，切换时再回来会恢复 */
  const setAccent = (value: string) => {
    accent.value = value || DEFAULT_ACCENT;
    if (!dark.value) {
      document.documentElement.style.setProperty('--app-accent', accent.value);
    }
  };

  return {
    title,
    theme,
    sideTheme,
    showSettings,
    tagsView,
    tagsIcon,
    fixedHeader,
    sidebarLogo,
    dynamicTitle,
    animationEnable,
    dark,
    accent,
    navType,
    radiusBase,
    setTitle,
    setDark,
    setAccent
  };
});
