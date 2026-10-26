// 处理主题样式（U-24 双主题版）
//
// 皮肤与主色全部由 theme-framed.scss 的 4 套令牌（data-skin × html.dark）声明，
// 本文件不再向 :root 写内联主色/强调色——内联值会压过样式表里的皮肤令牌，
// 旧实现（handleThemeStyle 算色阶 + 浅色写内联 acc）已随双主题批退役。
// 残留的色阶计算函数保留：页面里还有按品牌色算 hover 档的调用方。
export const DEFAULT_ACCENT = '#2f6feb';
export const DARK_STORAGE_KEY = 'useDarkKey';
export const SKIN_STORAGE_KEY = 'app-skin';
export type AppSkin = 't1' | 't7';

export const normalizeSkin = (v: unknown): AppSkin => (v === 't7' ? 't7' : 't1');

/** 读本机持久化的皮肤（t1/t7；缺失或非法一律回 t1） */
export const readStoredSkin = (): AppSkin => {
  try {
    return normalizeSkin(localStorage.getItem(SKIN_STORAGE_KEY));
  } catch {
    return 't1';
  }
};

/** 应用皮肤：html[data-skin]（样式表据此选 4 套令牌之一；写属性即生效，无需算色阶） */
export const applySkin = (skin: AppSkin) => {
  document.documentElement.setAttribute('data-skin', skin);
};

/** boot 早期应用皮肤：main.ts 模块求值阶段同步调用，赶在首帧前把 data-skin 挂上 html，
 *  避免刷新时先按 T1 渲染再跳 T7 的闪变 */
export const applyInitialSkin = () => {
  applySkin(readStoredSkin());
};

/**
 * 应用明暗：切换 html.dark 并持久化。
 *
 * 明暗与皮肤正交：深浅的全部色值（含 EP 主色阶梯）都在样式表按组合声明，
 * 这里只搬类名与存储，不写任何内联颜色。
 */
export const applyThemeMode = (dark: boolean) => {
  const root = document.documentElement;
  root.classList.toggle('dark', dark);
  // 清掉旧版本遗留的内联主色/强调色（若有）：它们会压过皮肤令牌
  root.style.removeProperty('--el-color-primary');
  root.style.removeProperty('--app-accent');
  for (let i = 1; i <= 9; i++) {
    root.style.removeProperty(`--el-color-primary-light-${i}`);
    root.style.removeProperty(`--el-color-primary-dark-${i}`);
  }
  try {
    localStorage.setItem(DARK_STORAGE_KEY, dark ? 'dark' : 'light');
  } catch {
    // 隐私模式下 localStorage 可能不可写，不阻断渲染
  }
};

// hex颜色转rgb颜色
export const hexToRgb = (str: string): string[] => {
  str = str.replace('#', '');
  const hexs = str.match(/../g);
  for (let i = 0; i < 3; i++) {
    if (hexs) {
      hexs[i] = String(parseInt(hexs[i], 16));
    }
  }
  return hexs ? hexs : [];
};

// rgb颜色转Hex颜色
export const rgbToHex = (r: string, g: string, b: string) => {
  const hexs = [Number(r).toString(16), Number(g).toString(16), Number(b).toString(16)];
  for (let i = 0; i < 3; i++) {
    if (hexs[i].length == 1) {
      hexs[i] = `0${hexs[i]}`;
    }
  }
  return `#${hexs.join('')}`;
};

// 变浅颜色值
export const getLightColor = (color: string, level: number): string => {
  const rgb = hexToRgb(color);
  for (let i = 0; i < 3; i++) {
    const s = (255 - Number(rgb[i])) * level + Number(rgb[i]);
    rgb[i] = String(Math.floor(s));
  }
  return rgbToHex(rgb[0], rgb[1], rgb[2]);
};

// 变深颜色值
export const getDarkColor = (color: string, level: number): string => {
  const rgb = hexToRgb(color);
  for (let i = 0; i < 3; i++) {
    rgb[i] = String(Math.floor(Number(rgb[i]) * (1 - level)));
  }
  return rgbToHex(rgb[0], rgb[1], rgb[2]);
};
