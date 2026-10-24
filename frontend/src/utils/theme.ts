// 处理主题样式
export const handleThemeStyle = (theme: string) => {
  document.documentElement.style.setProperty('--el-color-primary', theme);
  for (let i = 1; i <= 9; i++) {
    document.documentElement.style.setProperty(`--el-color-primary-light-${i}`, `${getLightColor(theme, i / 10)}`);
  }
  for (let i = 1; i <= 9; i++) {
    document.documentElement.style.setProperty(`--el-color-primary-dark-${i}`, `${getDarkColor(theme, i / 10)}`);
  }
};

/** 框架纸（浅）的主操作色；纯黑夜（深）由 html.dark 的样式接管主色 */
export const LIGHT_PRIMARY = '#1d1f20';
export const DEFAULT_ACCENT = '#1b6c80';
export const DARK_STORAGE_KEY = 'useDarkKey';

/**
 * 应用外观：切换 html.dark，并写入/清除内联主色。
 *
 * 深浅必须分开写：浅色由 handleThemeStyle 算色阶（写内联变量）；
 * 深色若继续内联，会压过 html.dark 里的白色主色与色阶，所以深色先清掉内联。
 */
export const applyThemeMode = (dark: boolean, accent: string = DEFAULT_ACCENT) => {
  const root = document.documentElement;
  root.classList.toggle('dark', dark);
  if (dark) {
    root.style.removeProperty('--el-color-primary');
    for (let i = 1; i <= 9; i++) {
      root.style.removeProperty(`--el-color-primary-light-${i}`);
      root.style.removeProperty(`--el-color-primary-dark-${i}`);
    }
    // 深色的数据色是设计固定的青柠，不接受自定义强调色
    root.style.removeProperty('--app-accent');
  } else {
    handleThemeStyle(LIGHT_PRIMARY);
    root.style.setProperty('--app-accent', accent || DEFAULT_ACCENT);
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
export const getLightColor = (color: string, level: number) => {
  const rgb = hexToRgb(color);
  for (let i = 0; i < 3; i++) {
    const s = (255 - Number(rgb[i])) * level + Number(rgb[i]);
    rgb[i] = String(Math.floor(s));
  }
  return rgbToHex(rgb[0], rgb[1], rgb[2]);
};

// 变深颜色值
export const getDarkColor = (color: string, level: number) => {
  const rgb = hexToRgb(color);
  for (let i = 0; i < 3; i++) {
    rgb[i] = String(Math.floor(Number(rgb[i]) * (1 - level)));
  }
  return rgbToHex(rgb[0], rgb[1], rgb[2]);
};
