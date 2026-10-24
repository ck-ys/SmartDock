/**
 * 图表取色（A 框架纸 / D 纯黑夜）
 *
 * ECharts 画在 canvas 上，读不到 CSS 变量，所以统一在这里把 --app-* / --el-color-*
 * 读成 JS 值。页面用 useChartTokens() 拿到 computed：主题切换时 computed 重算，
 * 折线、柱、饼、桑基自动换色，不需要刷新页面。
 *
 * 口径：数据系列只用一个强调色（accent），多系列靠同一色的深浅 + 中性灰区分；
 * 红/黄/绿只留给风险等级等语义，不用于普通系列。
 */
import { computed } from 'vue';
import { useSettingsStore } from '@/store/modules/settings';

export interface ChartTokens {
  /** 唯一数据色 */
  accent: string;
  /** 主文字 */
  ink: string;
  /** 次要文字 / 第二系列 */
  muted: string;
  /** 轴刻度文字 */
  faint: string;
  /** 网格线 */
  rule: string;
  /** 轴线 */
  border: string;
  /** 画布底（饼图扇区描边用） */
  surface: string;
  success: string;
  warning: string;
  danger: string;
  /** 多系列折线/柱状：accent → 灰阶 */
  series: string[];
  /** 饼图/环图：同一色由深到浅的六档 */
  ramp: string[];
}

function cssVar(style: CSSStyleDeclaration, name: string, fallback: string): string {
  const value = style.getPropertyValue(name).trim();
  return value || fallback;
}

/** 把 #rrggbb 转成 rgba()；非法值原样返回，宁可色彩不对也不让图表炸 */
export function alpha(hex: string, a: number): string {
  const m = /^#([0-9a-f]{6})$/i.exec(hex.trim());
  if (!m) {
    return hex;
  }
  const n = parseInt(m[1], 16);
  return `rgba(${(n >> 16) & 255}, ${(n >> 8) & 255}, ${n & 255}, ${a})`;
}

export function readChartTokens(): ChartTokens {
  const root = getComputedStyle(document.documentElement);
  const accent = cssVar(root, '--app-accent', '#1b6c80');
  const muted = cssVar(root, '--app-muted', '#5d5f60');
  const faint = cssVar(root, '--app-faint', '#6b6b6b');
  return {
    accent,
    ink: cssVar(root, '--app-text', '#131416'),
    muted,
    faint,
    rule: cssVar(root, '--app-rule', '#f0f1f2'),
    border: cssVar(root, '--app-border', '#e8eaec'),
    surface: cssVar(root, '--app-sheet-bg', '#ffffff'),
    success: cssVar(root, '--el-color-success', '#2e7d5b'),
    warning: cssVar(root, '--el-color-warning', '#a86f0c'),
    danger: cssVar(root, '--el-color-danger', '#b8333a'),
    series: [accent, alpha(accent, 0.55), muted, faint, alpha(accent, 0.3)],
    ramp: [1, 0.8, 0.62, 0.46, 0.3, 0.16].map((a) => alpha(accent, a))
  };
}

/** 主题切换后自动失效的取色器 */
export function useChartTokens() {
  const settings = useSettingsStore();
  return computed<ChartTokens>(() => {
    // 显式依赖主题与强调色：html.dark 的类名由 applyThemeMode 同步切完，再让 computed 重算；
    // 浅色下改「数据强调色」只写内联 --app-accent，不声明依赖的话 canvas 会一直停在旧色
    void settings.dark;
    void settings.accent;
    return readChartTokens();
  });
}
