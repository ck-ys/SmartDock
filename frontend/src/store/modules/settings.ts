import { defineStore } from 'pinia';
import defaultSettings from '@/settings';
import { useDynamicTitle } from '@/utils/dynamicTitle';
import { useStorage } from '@vueuse/core';
import { ref } from 'vue';
import { NavTypeEnum } from '@/enums/NavTypeEnum';
import { SideThemeEnum } from '@/enums/SideThemeEnum';
import { applyThemeMode, applySkin, DARK_STORAGE_KEY, DEFAULT_ACCENT, readStoredSkin, SKIN_STORAGE_KEY, type AppSkin } from '@/utils/theme';

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
  // 皮肤（U-24 双主题）：html[data-skin] 与 localStorage 都由 setSkin 同步；boot 时 main.ts 已先行应用过
  const skin = ref<AppSkin>(readStoredSkin());
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

  /** 切换明暗（唯一入口：导航栏按钮、外观抽屉、启动时都走这里）；皮肤与之正交，互不清对方状态 */
  const setDark = (value: boolean) => {
    dark.value = value;
    sideTheme.value = value ? SideThemeEnum.DARK : SideThemeEnum.LIGHT;
    applyThemeMode(value);
  };

  /** 切换皮肤（唯一入口：外观抽屉两卡选择器）；令牌全在 theme-framed.scss，写 data-skin 即全站换色 */
  const setSkin = (value: AppSkin) => {
    skin.value = value;
    applySkin(value);
    try {
      localStorage.setItem(SKIN_STORAGE_KEY, value);
    } catch {
      // 隐私模式下 localStorage 可能不可写，不阻断换肤
    }
  };

  /** 兼容保留：双主题后强调色由皮肤令牌固定（acc 逐值来自 themes.mjs），不再写内联覆盖 */
  const setAccent = (value: string) => {
    accent.value = value || DEFAULT_ACCENT;
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
    skin,
    navType,
    radiusBase,
    setTitle,
    setDark,
    setSkin,
    setAccent
  };
});
